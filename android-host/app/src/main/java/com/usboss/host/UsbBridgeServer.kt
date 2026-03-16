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
    private val openDevice: (Int) -> OpenedUsbDevice,
) {
    @Volatile
    private var stopping = false
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoverySocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private var discoveryJob: Job? = null
    private var acceptJob: Job? = null
    private val sessionLock = Any()
    private var nextSessionId = 1L
    private val sessions = mutableMapOf<Long, ClientSession>()

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
        snapshotSessions().forEach { session ->
            runCatching { session.socket.close() }
        }
        clearSessions()
        ioScope.launch {
            discoveryJob?.cancelAndJoin()
            acceptJob?.cancelAndJoin()
        }
        onClientChanged(null)
    }

    fun onAvailableDevicesChanged(availablePaths: Set<String>) {
        val affectedSessions = synchronized(sessionLock) {
            sessions.values
                .filter { session ->
                    val activePath = session.activeDeviceSystemPath
                    activePath != null && activePath !in availablePaths
                }
                .toList()
        }
        if (affectedSessions.isEmpty()) {
            return
        }
        onStatus("One or more active USB devices disconnected; waiting for them to return")
        onError("A currently forwarded USB device was unplugged.")
        affectedSessions.forEach { session ->
            runCatching { session.socket.close() }
        }
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

            val sessionId = registerSession(socket)
            ioScope.launch {
                try {
                    handleClient(sessionId, socket)
                } catch (error: Throwable) {
                    onError(error.message ?: "Client session failed")
                    onStatus("Client disconnected")
                } finally {
                    runCatching { socket.close() }
                    removeSession(sessionId)
                }
            }
        }
    }

    private suspend fun handleClient(sessionId: Long, socket: Socket) {
        socket.tcpNoDelay = true
        onStatus("Client connected: ${socket.inetAddress.hostAddress}")

        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val writerMutex = Mutex()

        val hello = Protocol.read(input)
        require(hello is Protocol.Message.Hello) { "Expected USBoss hello frame" }
        writerMutex.withLock {
            Protocol.write(output, Protocol.Message.HelloAck(serverName()))
        }

        var openedDevice: OpenedUsbDevice? = null
        var inputPump: Job? = null

        try {
            session@ while (scope.isActive && !socket.isClosed) {
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
                        releaseDevice(sessionId)
                        openedDevice = null

                        val deviceResult = runCatching {
                            openDevice(message.deviceId)
                        }
                        if (deviceResult.isFailure) {
                            val error = deviceResult.exceptionOrNull()
                            val errorMessage = error?.message ?: "Failed to open USB device"
                            onStatus("Open request failed; waiting for a usable USB device")
                            onError(errorMessage)
                            writerMutex.withLock {
                                Protocol.write(
                                    output,
                                    Protocol.Message.Error(
                                        errorMessage,
                                    ),
                                )
                            }
                            continue@session
                        }
                        val device = deviceResult.getOrThrow()
                        if (!tryReserveDevice(sessionId, device.systemPath)) {
                            device.close()
                            val errorMessage = "USB device is already being forwarded by another client"
                            writerMutex.withLock {
                                Protocol.write(output, Protocol.Message.Error(errorMessage))
                            }
                            continue@session
                        }
                        openedDevice = device
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
                                    onStatus("USB device disconnected; waiting for Linux to reconnect")
                                    runCatching { socket.close() }
                                },
                            ).join()
                        }

                        onStatus("Forwarding ${spec.name}")
                    }

                    is Protocol.Message.OutputReport -> {
                        if (openedDevice == null) {
                            writerMutex.withLock {
                                Protocol.write(output, Protocol.Message.Error("No USB device is currently open"))
                            }
                            continue@session
                        }
                        val device = checkNotNull(openedDevice)
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
                        if (openedDevice == null) {
                            writerMutex.withLock {
                                Protocol.write(
                                    output,
                                    Protocol.Message.GetReportResponse(
                                        requestId = message.requestId,
                                        status = 5,
                                        data = ByteArray(0),
                                    ),
                                )
                            }
                            continue@session
                        }
                        val device = checkNotNull(openedDevice)
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
                        if (openedDevice == null) {
                            writerMutex.withLock {
                                Protocol.write(
                                    output,
                                    Protocol.Message.SetReportResponse(
                                        requestId = message.requestId,
                                        status = 5,
                                    ),
                                )
                            }
                            continue@session
                        }
                        val device = checkNotNull(openedDevice)
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
            releaseDevice(sessionId)
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

    private fun registerSession(socket: Socket): Long {
        val sessionId = synchronized(sessionLock) {
            val id = nextSessionId++
            sessions[id] = ClientSession(
                socket = socket,
                clientLabel = socket.inetAddress.hostAddress.orEmpty(),
            )
            id
        }
        updateConnectedClientSummary()
        return sessionId
    }

    private fun removeSession(sessionId: Long) {
        synchronized(sessionLock) {
            sessions.remove(sessionId)
        }
        updateConnectedClientSummary()
    }

    private fun tryReserveDevice(sessionId: Long, systemPath: String): Boolean {
        val reserved = synchronized(sessionLock) {
            val alreadyInUse = sessions.any { (id, session) ->
                id != sessionId && session.activeDeviceSystemPath == systemPath
            }
            if (alreadyInUse) {
                false
            } else {
                sessions[sessionId]?.activeDeviceSystemPath = systemPath
                true
            }
        }
        updateConnectedClientSummary()
        return reserved
    }

    private fun releaseDevice(sessionId: Long) {
        synchronized(sessionLock) {
            sessions[sessionId]?.activeDeviceSystemPath = null
        }
        updateConnectedClientSummary()
    }

    private fun updateConnectedClientSummary() {
        val summary = synchronized(sessionLock) {
            when (val activeSessions = sessions.size) {
                0 -> null
                1 -> sessions.values.first().clientLabel
                else -> {
                    val labels = sessions.values.map { it.clientLabel }.distinct()
                    if (labels.size == 1) {
                        "${labels.first()} ($activeSessions sessions)"
                    } else {
                        "$activeSessions clients"
                    }
                }
            }
        }
        onClientChanged(summary)
    }

    private fun snapshotSessions(): List<ClientSession> {
        return synchronized(sessionLock) {
            sessions.values.toList()
        }
    }

    private fun clearSessions() {
        synchronized(sessionLock) {
            sessions.clear()
        }
    }

    companion object {
        private const val TAG = "USBoss"
    }

    private data class ClientSession(
        val socket: Socket,
        val clientLabel: String,
        var activeDeviceSystemPath: String? = null,
    )
}
