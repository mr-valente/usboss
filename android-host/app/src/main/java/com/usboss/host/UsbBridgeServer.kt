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
        HostRuntime.note("Starting TCP/UDP bridge server", addToRecent = true)
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
        HostRuntime.note("Stopping TCP/UDP bridge server", addToRecent = true)
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
        HostRuntime.debug("Available device paths changed: ${availablePaths.joinToString()}")
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
        HostRuntime.note("TCP bridge listening on ${Protocol.DEFAULT_TCP_PORT}")
        onStatus("Listening on ${Protocol.DEFAULT_TCP_PORT}")

        while (ioScope.isActive) {
            val socket = try {
                serverSocket?.accept()
            } catch (_: Throwable) {
                null
            } ?: break

            val sessionId = registerSession(socket)
            HostRuntime.note(
                "Accepted Linux client session $sessionId from ${socket.inetAddress.hostAddress}",
                addToRecent = true,
            )
            ioScope.launch {
                try {
                    handleClient(sessionId, socket)
                } catch (error: Throwable) {
                    onError(error.message ?: "Client session failed")
                } finally {
                    runCatching { socket.close() }
                    removeSession(sessionId)
                }
            }
        }
    }

    private suspend fun handleClient(sessionId: Long, socket: Socket) {
        socket.tcpNoDelay = true

        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val writerMutex = Mutex()

        val hello = Protocol.read(input)
        require(hello is Protocol.Message.Hello) { "Expected USBoss hello frame" }
        synchronized(sessionLock) {
            sessions[sessionId]?.clientName = hello.clientName
        }
        HostRuntime.note("Session $sessionId hello from ${hello.clientName}")
        HostRuntime.debug("Session $sessionId completed handshake from ${hello.clientName}")
        writerMutex.withLock {
            Protocol.write(output, Protocol.Message.HelloAck(serverName()))
        }

        var openedDevice: OpenedUsbDevice? = null
        var inputPump: Job? = null
        var forwardedReportCount = 0

        try {
            session@ while (scope.isActive && !socket.isClosed) {
                when (val message = Protocol.read(input)) {
                    Protocol.Message.ListDevices -> {
                        HostRuntime.debug("Session $sessionId requested device list")
                        writerMutex.withLock {
                            Protocol.write(
                                output,
                                Protocol.Message.Devices(devicesProvider()),
                            )
                        }
                    }

                    is Protocol.Message.OpenDevice -> {
                        HostRuntime.note("Session $sessionId requested device ${message.deviceId}", addToRecent = true)
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
                            HostRuntime.note("Session $sessionId could not reserve ${device.systemPath}: already in use")
                            writerMutex.withLock {
                                Protocol.write(output, Protocol.Message.Error(errorMessage))
                            }
                            continue@session
                        }
                        openedDevice = device
                        val spec = device.protocolSpec()
                        forwardedReportCount = 0

                        writerMutex.withLock {
                            Protocol.write(output, Protocol.Message.OpenDeviceAck(spec))
                        }

                        inputPump = ioScope.launch {
                            HostRuntime.debug("Session $sessionId started input pump for ${spec.name}")
                            device.startInputPump(
                                scope = this,
                                onReport = { report ->
                                    forwardedReportCount += 1
                                    if (forwardedReportCount <= 12 || forwardedReportCount % 100 == 0) {
                                        HostRuntime.debug(
                                            "Session $sessionId forwarding input report #$forwardedReportCount " +
                                                "for ${device.systemPath} (${report.size} bytes)",
                                        )
                                    }
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
                                    onError(error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
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
                        HostRuntime.debug(
                            "Session $sessionId sent output report type=${message.reportType} id=${message.reportId} size=${message.data.size} status=$status",
                        )
                        if (status != 0) {
                            onStatus("Output report fallback returned errno $status")
                        }
                    }

                    is Protocol.Message.GetReportRequest -> {
                        HostRuntime.debug(
                            "Session $sessionId received GET_REPORT request type=${message.reportType} id=${message.reportId} request=${message.requestId}",
                        )
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
                        HostRuntime.debug(
                            "Session $sessionId received SET_REPORT request type=${message.reportType} id=${message.reportId} request=${message.requestId} size=${message.data.size}",
                        )
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
            HostRuntime.note(
                "Linux client session $sessionId disconnected (${sessionRoleLabel(sessionId)})",
            )
        } finally {
            HostRuntime.debug("Cleaning up session $sessionId")
            inputPump?.cancelAndJoin()
            openedDevice?.close()
            releaseDevice(sessionId, updatePresentation = false)
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
            HostRuntime.debug("Received UDP discovery probe from ${packet.address.hostAddress}:${packet.port}")

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
                HostRuntime.debug("Sent discovery response to ${packet.address.hostAddress}:${packet.port}")
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
                role = SessionRole.Monitoring,
            )
            id
        }
        updateSessionPresentation()
        return sessionId
    }

    private fun removeSession(sessionId: Long) {
        synchronized(sessionLock) {
            sessions.remove(sessionId)
        }
        updateSessionPresentation()
    }

    private fun tryReserveDevice(sessionId: Long, systemPath: String): Boolean {
        val reserved = synchronized(sessionLock) {
            val alreadyInUse = sessions.any { (id, session) ->
                id != sessionId && session.activeDeviceSystemPath == systemPath
            }
            if (alreadyInUse) {
                false
            } else {
                sessions[sessionId]?.let { session ->
                    session.activeDeviceSystemPath = systemPath
                    session.role = SessionRole.Forwarding
                }
                true
            }
        }
        if (reserved) {
            HostRuntime.debug("Session $sessionId role changed to forwarding for $systemPath")
        }
        updateSessionPresentation()
        return reserved
    }

    private fun releaseDevice(sessionId: Long, updatePresentation: Boolean = true) {
        val changed = synchronized(sessionLock) {
            sessions[sessionId]?.let { session ->
                val hadDevice = session.activeDeviceSystemPath != null
                val roleChanged = session.role != SessionRole.Monitoring
                session.activeDeviceSystemPath = null
                session.role = SessionRole.Monitoring
                hadDevice || roleChanged
            } ?: false
        }
        if (changed) {
            HostRuntime.debug("Session $sessionId role changed to monitoring")
        }
        if (updatePresentation) {
            updateSessionPresentation()
        }
    }

    private fun sessionRoleLabel(sessionId: Long): String {
        return synchronized(sessionLock) {
            sessions[sessionId]?.role?.label ?: "unknown"
        }
    }

    private fun updateSessionPresentation() {
        val presentation = synchronized(sessionLock) {
            val monitoring = sessions.values.filter { it.role == SessionRole.Monitoring }
            val forwarding = sessions.values.filter { it.role == SessionRole.Forwarding }
            SessionPresentation(
                summary = summarizeSessions(monitoring, forwarding),
                status = summarizeStatus(monitoring, forwarding),
                monitoringCount = monitoring.size,
                forwardingCount = forwarding.size,
            )
        }
        HostRuntime.debug(
            "Session presentation updated: monitoring=${presentation.monitoringCount} " +
                "forwarding=${presentation.forwardingCount} summary=${presentation.summary ?: "none"} " +
                "status=${presentation.status ?: "preserve"}",
        )
        onClientChanged(presentation.summary)
        presentation.status?.let(onStatus)
    }

    private fun summarizeSessions(
        monitoring: List<ClientSession>,
        forwarding: List<ClientSession>,
    ): String? {
        return when {
            forwarding.isNotEmpty() && monitoring.isNotEmpty() ->
                "${describeSessionGroup("Forwarding to", forwarding)}; monitoring active (${monitoring.size})"
            forwarding.isNotEmpty() -> describeSessionGroup("Forwarding to", forwarding)
            monitoring.isNotEmpty() -> describeSessionGroup("Monitoring from", monitoring)
            else -> null
        }
    }

    private fun summarizeStatus(
        monitoring: List<ClientSession>,
        forwarding: List<ClientSession>,
    ): String? {
        return when {
            forwarding.isNotEmpty() -> null
            monitoring.isNotEmpty() -> describeSessionGroup("Linux monitor connected from", monitoring)
            else -> "Listening on ${Protocol.DEFAULT_TCP_PORT}"
        }
    }

    private fun describeSessionGroup(prefix: String, sessions: List<ClientSession>): String {
        val labels = sessions.map { it.clientLabel }.distinct()
        return when {
            sessions.isEmpty() -> "$prefix nobody"
            labels.size == 1 && sessions.size == 1 -> "$prefix ${labels.first()}"
            labels.size == 1 -> "$prefix ${labels.first()} (${sessions.size} sessions)"
            else -> "$prefix ${labels.size} clients (${sessions.size} sessions)"
        }
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
        var role: SessionRole = SessionRole.Monitoring,
        var clientName: String = "unknown",
    )

    private data class SessionPresentation(
        val summary: String?,
        val status: String?,
        val monitoringCount: Int,
        val forwardingCount: Int,
    )

    private enum class SessionRole(val label: String) {
        Monitoring("monitoring"),
        Forwarding("forwarding"),
    }
}
