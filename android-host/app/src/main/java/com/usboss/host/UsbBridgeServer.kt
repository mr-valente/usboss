package com.usboss.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class UsbBridgeServer(
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
    private val onError: (String?) -> Unit,
    private val onClientChanged: (String?) -> Unit,
    private val devicesProvider: () -> List<Protocol.DeviceSummary>,
    private val openDevice: (Int) -> OpenedHidDevice,
) {
    @Volatile
    private var stopping = false
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoverySocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private var discoveryJob: Job? = null
    private var acceptJob: Job? = null
    @Volatile
    private var activeClientSocket: Socket? = null

    fun start() {
        stopping = false
        discoveryJob = ioScope.launch {
            try {
                runDiscoveryLoop()
            } catch (error: Throwable) {
                if (!stopping) {
                    Log.e(TAG, "Discovery loop failed", error)
                    onError("Discovery error: ${error.message}")
                    onStatus("Discovery unavailable")
                }
            }
        }
        acceptJob = ioScope.launch {
            try {
                runAcceptLoop()
            } catch (error: Throwable) {
                if (!stopping) {
                    Log.e(TAG, "Accept loop failed", error)
                    onError("Server error: ${error.message}")
                    onStatus("Server unavailable")
                }
            }
        }
    }

    fun stop() {
        stopping = true
        discoverySocket?.close()
        serverSocket?.close()
        activeClientSocket?.close()
        ioScope.launch {
            discoveryJob?.cancelAndJoin()
            acceptJob?.cancelAndJoin()
        }
        onClientChanged(null)
    }

    private suspend fun runAcceptLoop() {
        serverSocket = ServerSocket(Protocol.DEFAULT_TCP_PORT).apply {
            reuseAddress = true
        }
        onStatus("Listening on ${Protocol.DEFAULT_TCP_PORT}")

        while (ioScope.isActive) {
            val socket = try {
                serverSocket?.accept()
            } catch (_: Throwable) {
                null
            } ?: break

            activeClientSocket = socket
            try {
                handleClient(socket)
            } catch (error: Throwable) {
                onError(error.message ?: "Client session failed")
                onStatus("Client disconnected")
            } finally {
                runCatching { socket.close() }
                activeClientSocket = null
                onClientChanged(null)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        onClientChanged(socket.inetAddress.hostAddress)
        onStatus("Client connected: ${socket.inetAddress.hostAddress}")

        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val writerMutex = Mutex()

        val hello = Protocol.read(input)
        require(hello is Protocol.Message.Hello) { "Expected USBoss hello frame" }
        writerMutex.withLock {
            Protocol.write(output, Protocol.Message.HelloAck(serverName()))
        }

        var openedDevice: OpenedHidDevice? = null
        var inputPump: Job? = null

        try {
            while (scope.isActive && !socket.isClosed) {
                when (val message = Protocol.read(input)) {
                    Protocol.Message.ListDevices -> {
                        writerMutex.withLock {
                            Protocol.write(
                                output,
                                Protocol.Message.Devices(devicesProvider()),
                            )
                        }
                    }

                    is Protocol.Message.OpenDevice -> {
                        inputPump?.cancelAndJoin()
                        openedDevice?.close()

                        openedDevice = openDevice(message.deviceId)
                        val device = checkNotNull(openedDevice)
                        val spec = device.protocolSpec()

                        writerMutex.withLock {
                            Protocol.write(output, Protocol.Message.OpenDeviceAck(spec))
                        }

                        inputPump = ioScope.launch {
                            device.startInputPump(
                                scope = this,
                                onReport = { report ->
                                    launch {
                                        writerMutex.withLock {
                                            Protocol.write(
                                                output,
                                                Protocol.Message.InputReport(report),
                                            )
                                        }
                                    }
                                },
                                onError = { error ->
                                    onError(error.message ?: "USB read failed")
                                    runCatching { socket.close() }
                                },
                            ).join()
                        }

                        onStatus("Forwarding ${spec.name}")
                    }

                    is Protocol.Message.OutputReport -> {
                        val device = openedDevice ?: throw IllegalStateException("No device is open")
                        val status = device.sendOutputReport(
                            reportType = message.reportType,
                            reportId = message.reportId,
                            data = message.data,
                        )
                        if (status != 0) {
                            onStatus("Output report fallback returned errno $status")
                        }
                    }

                    is Protocol.Message.GetReportRequest -> {
                        val device = openedDevice ?: throw IllegalStateException("No device is open")
                        val data = try {
                            device.getReport(message.reportType, message.reportId)
                        } catch (_: Throwable) {
                            ByteArray(0)
                        }
                        writerMutex.withLock {
                            Protocol.write(
                                output,
                                Protocol.Message.GetReportResponse(
                                    requestId = message.requestId,
                                    status = if (data.isEmpty()) 5 else 0,
                                    data = data,
                                ),
                            )
                        }
                    }

                    is Protocol.Message.SetReportRequest -> {
                        val device = openedDevice ?: throw IllegalStateException("No device is open")
                        val status = device.setReport(
                            reportType = message.reportType,
                            reportId = message.reportId,
                            data = message.data,
                        )
                        writerMutex.withLock {
                            Protocol.write(
                                output,
                                Protocol.Message.SetReportResponse(
                                    requestId = message.requestId,
                                    status = status,
                                ),
                            )
                        }
                    }

                    Protocol.Message.Ping -> {
                        writerMutex.withLock {
                            Protocol.write(output, Protocol.Message.Pong)
                        }
                    }

                    Protocol.Message.Pong -> Unit
                    is Protocol.Message.Error -> throw IllegalStateException(message.message)
                    else -> throw IllegalStateException("Unexpected USBoss frame: $message")
                }
            }
        } catch (_: EOFException) {
            onStatus("Client disconnected")
        } finally {
            inputPump?.cancelAndJoin()
            openedDevice?.close()
        }
    }

    private fun runDiscoveryLoop() {
        discoverySocket = DatagramSocket(Protocol.DEFAULT_DISCOVERY_PORT).apply {
            broadcast = true
            reuseAddress = true
        }

        val receiveBuffer = ByteArray(256)
        while (ioScope.isActive) {
            val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
            try {
                discoverySocket?.receive(packet) ?: break
            } catch (_: Throwable) {
                break
            }

            val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
            if (payload.trim() != Protocol.DISCOVERY_REQUEST) {
                continue
            }

            val response = Protocol.discoveryResponse(serverName(), Protocol.DEFAULT_TCP_PORT)
                .encodeToByteArray()
            val responsePacket = DatagramPacket(
                response,
                response.size,
                packet.address,
                packet.port,
            )
            runCatching {
                discoverySocket?.send(responsePacket)
            }
        }
    }

    private fun serverName(): String {
        val model = android.os.Build.MODEL ?: "Android Host"
        return "USBoss on $model"
    }

    companion object {
        private const val TAG = "USBoss"
    }
}
