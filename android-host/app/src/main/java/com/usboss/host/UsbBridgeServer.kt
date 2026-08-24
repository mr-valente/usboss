package com.usboss.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.SocketException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue

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
            releaseDeviceForSession(session, reason = "device path disappeared")
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
                    if (!shouldSuppressSessionError(error, socket)) {
                        onError(error.message ?: "Client session failed")
                    } else {
                        HostRuntime.debug("Ignoring expected session shutdown for session $sessionId: ${error.javaClass.simpleName}")
                    }
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
        val writer = SessionWriter(output)

        // One coroutine owns the output stream for the whole session, so frames can
        // never interleave or be reordered no matter who produces them.
        val writerJob = ioScope.launch(Dispatchers.IO) {
            try {
                writer.run()
            } catch (_: CancellationException) {
                // Normal session teardown; the reader owns closing the socket.
            } catch (error: Throwable) {
                if (!shouldSuppressSessionError(error, socket)) {
                    HostRuntime.debug("Session $sessionId writer ended: ${error.javaClass.simpleName}")
                }
                // The socket is unusable, so unblock the reader and let it tear down.
                runCatching { socket.close() }
            }
        }

        try {
            runClientSession(sessionId, socket, input, writer)
        } finally {
            writerJob.cancel()
        }
    }

    private suspend fun runClientSession(
        sessionId: Long,
        socket: Socket,
        input: BufferedInputStream,
        writer: SessionWriter,
    ) {
        fun writeFrame(message: Protocol.Message) = writer.sendControl(message)

        val hello = Protocol.read(input)
        require(hello is Protocol.Message.Hello) { "Expected USBoss hello frame" }
        synchronized(sessionLock) {
            sessions[sessionId]?.clientName = hello.clientName
        }
        HostRuntime.note("Session $sessionId hello from ${hello.clientName}")
        HostRuntime.debug("Session $sessionId completed handshake from ${hello.clientName}")
        writeFrame(Protocol.Message.HelloAck(serverName()))

        var openedDevice: OpenedUsbDevice? = null
        var inputPump: Job? = null
        var forwardedReportCount = 0

        fun closeActiveDevice(reason: String) {
            val device = openedDevice ?: return
            HostRuntime.debug("Session $sessionId releasing ${device.systemPath} ($reason)")
            openedDevice = null
            releaseDevice(sessionId)
            runCatching { device.close() }
        }

        try {
            session@ while (scope.isActive && !socket.isClosed) {
                when (val message = Protocol.read(input)) {
                    Protocol.Message.ListDevices -> {
                        HostRuntime.debug("Session $sessionId requested device list")
                        writeFrame(Protocol.Message.Devices(devicesProvider()))
                    }

                    is Protocol.Message.OpenDevice -> {
                        HostRuntime.note("Session $sessionId requested device ${message.deviceId}", addToRecent = true)
                        inputPump?.cancelAndJoin()
                        closeActiveDevice("open-device reset")
                        // Anything still pending belongs to the device we just released.
                        writer.discardPendingInputReports()

                        val deviceResult = runCatching {
                            openDevice(message.deviceId)
                        }
                        if (deviceResult.isFailure) {
                            val error = deviceResult.exceptionOrNull()
                            val errorMessage = error?.message ?: "Failed to open USB device"
                            onStatus("Open request failed; waiting for a usable USB device")
                            onError(errorMessage)
                            writeFrame(Protocol.Message.Error(errorMessage))
                            continue@session
                        }
                        val device = deviceResult.getOrThrow()
                        if (!tryReserveDevice(sessionId, device.systemPath)) {
                            device.close()
                            val errorMessage = "USB device is already being forwarded by another client"
                            HostRuntime.note("Session $sessionId could not reserve ${device.systemPath}: already in use")
                            writeFrame(Protocol.Message.Error(errorMessage))
                            continue@session
                        }
                        openedDevice = device
                        val spec = device.protocolSpec()
                        forwardedReportCount = 0

                        writeFrame(Protocol.Message.OpenDeviceAck(spec))

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
                                    // Keep USB reads decoupled from socket flushes; synchronous
                                    // writes here add noticeable controller latency on Shield.
                                    // This hands off without blocking and without queueing:
                                    // see SessionWriter for why a stalled socket cannot build
                                    // a backlog of stale controller state.
                                    writer.sendInputReport(report)
                                },
                                onError = { error ->
                                    if (!stopping) {
                                        onError(error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
                                        onStatus("USB device disconnected; waiting for Linux to reconnect")
                                    }
                                    closeActiveDevice("input pump error")
                                    runCatching { socket.close() }
                                },
                            ).join()
                        }

                        onStatus("Forwarding ${spec.name}")
                    }

                    is Protocol.Message.OutputReport -> {
                        if (openedDevice == null) {
                            writeFrame(Protocol.Message.Error("No USB device is currently open"))
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
                            writeFrame(
                                Protocol.Message.GetReportResponse(
                                    requestId = message.requestId,
                                    status = 5,
                                    data = ByteArray(0),
                                ),
                            )
                            continue@session
                        }
                        val device = checkNotNull(openedDevice)
                        val data = try {
                            device.getReport(message.reportType, message.reportId)
                        } catch (_: Throwable) {
                            ByteArray(0)
                        }
                        writeFrame(
                            Protocol.Message.GetReportResponse(
                                requestId = message.requestId,
                                status = if (data.isEmpty()) 5 else 0,
                                data = data,
                            ),
                        )
                    }

                    is Protocol.Message.SetReportRequest -> {
                        HostRuntime.debug(
                            "Session $sessionId received SET_REPORT request type=${message.reportType} id=${message.reportId} request=${message.requestId} size=${message.data.size}",
                        )
                        if (openedDevice == null) {
                            writeFrame(
                                Protocol.Message.SetReportResponse(
                                    requestId = message.requestId,
                                    status = 5,
                                ),
                            )
                            continue@session
                        }
                        val device = checkNotNull(openedDevice)
                        val status = device.setReport(
                            reportType = message.reportType,
                            reportId = message.reportId,
                            data = message.data,
                        )
                        writeFrame(
                            Protocol.Message.SetReportResponse(
                                requestId = message.requestId,
                                status = status,
                            ),
                        )
                    }

                    Protocol.Message.Ping -> {
                        writeFrame(Protocol.Message.Pong)
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
            val superseded = writer.supersededReportCount()
            if (superseded > 0) {
                // Should be zero on a healthy link, so make it visible without
                // requiring verbose logging -- it is the signal that the socket
                // was falling behind the controller.
                HostRuntime.note(
                    "Session $sessionId superseded $superseded stale input report(s) while the socket was behind",
                )
            }
            closeActiveDevice("session cleanup")
            inputPump?.cancel()
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
        // Carries the host version into discovery listings and the Linux
        // client's "connected to" line.
        return "USBoss ${AppVersion.name} on $model"
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
            val conflictingSession = sessions.entries.firstOrNull { (id, session) ->
                id != sessionId && session.activeDeviceSystemPath == systemPath
            }?.value
            if (conflictingSession != null && !isSessionSocketStale(conflictingSession)) {
                false
            } else {
                if (conflictingSession != null) {
                    HostRuntime.debug(
                        "Session $sessionId reclaimed stale reservation for $systemPath " +
                            "from ${conflictingSession.clientLabel}",
                    )
                    conflictingSession.activeDeviceSystemPath = null
                    conflictingSession.role = SessionRole.Monitoring
                }
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

    private fun releaseDeviceForSession(session: ClientSession, reason: String) {
        val changed = synchronized(sessionLock) {
            val hadDevice = session.activeDeviceSystemPath != null
            val roleChanged = session.role != SessionRole.Monitoring
            session.activeDeviceSystemPath = null
            session.role = SessionRole.Monitoring
            hadDevice || roleChanged
        }
        if (changed) {
            HostRuntime.debug(
                "Released device reservation for ${session.clientLabel} " +
                    "(${session.clientName}; $reason)",
            )
            updateSessionPresentation()
        }
    }

    private fun isSessionSocketStale(session: ClientSession): Boolean {
        return session.socket.isClosed || session.socket.isInputShutdown || session.socket.isOutputShutdown
    }

    private fun sessionRoleLabel(sessionId: Long): String {
        return synchronized(sessionLock) {
            sessions[sessionId]?.role?.label ?: "unknown"
        }
    }

    private fun updateSessionPresentation() {
        val presentation = synchronized(sessionLock) {
            val activeSessions = sessions.values.filterNot(::isSessionSocketStale)
            val monitoring = activeSessions.filter { it.role == SessionRole.Monitoring }
            val forwarding = activeSessions.filter { it.role == SessionRole.Forwarding }
            SessionPresentation(
                summary = summarizeSessions(forwarding),
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

    private fun summarizeSessions(forwarding: List<ClientSession>): String? {
        return when {
            forwarding.isNotEmpty() -> describeSessionGroup("Forwarding to", forwarding)
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
            labels.size == 1 && sessions.size == 1 ->
                "$prefix ${labels.first()}${clientVersionSuffix(sessions.first())}"
            labels.size == 1 -> "$prefix ${labels.first()} (${sessions.size} sessions)"
            else -> "$prefix ${labels.size} clients (${sessions.size} sessions)"
        }
    }

    // Hello names arrive as "usboss-client/<version> <role>". Older clients send
    // no version, so anything that does not look like one is left out.
    private fun clientVersionSuffix(session: ClientSession): String {
        val version = CLIENT_VERSION_PATTERN.find(session.clientName)?.groupValues?.get(1)
        return if (version == null) "" else " (client $version)"
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

    private fun shouldSuppressSessionError(error: Throwable, socket: Socket): Boolean {
        if (stopping) {
            return true
        }
        return error is SocketException &&
            (socket.isClosed || error.message.equals("Socket closed", ignoreCase = true))
    }

    companion object {
        private const val TAG = "USBoss"
        private val CLIENT_VERSION_PATTERN = Regex("""^usboss-client/(\d+\.\d+\.\d+\S*)""")
    }

    /**
     * Owns one session's output stream.
     *
     * Control frames are queued losslessly and always drained ahead of input, so a
     * reply can never overtake the `OPEN_DEVICE_ACK` or `HELLO_ACK` that preceded it.
     *
     * Input reports are different: each one is a complete state snapshot that
     * supersedes the last, so they are held in a conflating slot keyed by report id
     * rather than a queue. If the socket stalls -- a congested link, a Wi-Fi
     * retransmit burst, the Shield descheduling us -- at most one report per id is
     * ever pending, so the stall cannot build a backlog of stale controller state
     * that has to be replayed afterwards. Before this, every USB report spawned its
     * own coroutine and a stall of N milliseconds became N milliseconds of permanent
     * added input latency that only a reconnect could clear.
     *
     * Keying on the first byte is safe for both transports: for HID reports that
     * carry a report id it conflates exactly the reports that supersede each other,
     * and for reports that do not it simply conflates less often.
     */
    private class SessionWriter(private val output: OutputStream) {
        private val controlQueue = ConcurrentLinkedQueue<Protocol.Message>()
        private val inputLock = Any()
        private val pendingInputs = LinkedHashMap<Int, ByteArray>()
        private val wakeup = Channel<Unit>(Channel.CONFLATED)

        @Volatile
        private var supersededReports = 0L

        fun sendControl(message: Protocol.Message) {
            controlQueue.add(message)
            wakeup.trySend(Unit)
        }

        fun sendInputReport(report: ByteArray) {
            synchronized(inputLock) {
                val reportId = if (report.isEmpty()) -1 else report[0].toInt() and 0xff
                if (pendingInputs.put(reportId, report) != null) {
                    supersededReports += 1
                }
            }
            wakeup.trySend(Unit)
        }

        fun discardPendingInputReports() {
            synchronized(inputLock) {
                pendingInputs.clear()
            }
        }

        fun supersededReportCount(): Long = supersededReports

        /**
         * Drains and writes until cancelled. Blocking writes happen here and nowhere
         * else, which is what keeps them off the USB read path.
         */
        suspend fun run() {
            while (true) {
                drain()
                wakeup.receive()
            }
        }

        private fun drain() {
            while (true) {
                val control = controlQueue.poll()
                if (control != null) {
                    Protocol.write(output, control)
                    continue
                }

                val reports: List<ByteArray> = synchronized(inputLock) {
                    if (pendingInputs.isEmpty()) {
                        emptyList()
                    } else {
                        val snapshot = pendingInputs.values.toList()
                        pendingInputs.clear()
                        snapshot
                    }
                }
                if (reports.isEmpty()) {
                    return
                }
                reports.forEach { report ->
                    Protocol.write(output, Protocol.Message.InputReport(report))
                }
            }
        }
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
