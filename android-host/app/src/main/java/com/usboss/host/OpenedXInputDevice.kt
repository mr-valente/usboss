package com.usboss.host

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

private const val XINPUT_REPORT_LOG_LIMIT = 12
private const val XINPUT_PLAYER_ONE_COMMAND = 0x02
private const val XINPUT_REQUEST_WAIT_TIMEOUT_MS = 1_000L
private const val XINPUT_RECOVERY_INTERVAL_MS = 2_000L
private const val XINPUT_ZERO_LENGTH_REINIT_EVERY = 3
private const val XINPUT_ZERO_LENGTH_RESET_THRESHOLD = 12

class OpenedXInputDevice(
    private val candidate: UsbCandidate,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inputEndpoint: UsbEndpoint,
    private val outputEndpoint: UsbEndpoint?,
    private val versionBcd: Int,
) : OpenedUsbDevice {
    private val closed = AtomicBoolean(false)

    override val displayName: String
        get() = candidate.displayName

    override val systemPath: String
        get() = candidate.systemPath

    override fun protocolSpec(): Protocol.OpenDeviceSpec {
        val serial = candidate.serial.ifBlank { "" }
        return Protocol.OpenDeviceSpec(
            transport = Protocol.TRANSPORT_XINPUT_360,
            vendorId = candidate.vendorId,
            productId = candidate.productId,
            versionBcd = versionBcd,
            countryCode = 0,
            busType = 0x03,
            interfaceNumber = candidate.interfaceNumber,
            inputPacketSize = candidate.inputPacketSize,
            outputPacketSize = candidate.outputPacketSize,
            hasInterruptOut = candidate.hasInterruptOut,
            name = "USBoss ${candidate.displayName}",
            phys = "android:${candidate.systemPath}",
            uniq = serial,
            reportDescriptor = ByteArray(0),
        )
    }

    override fun startInputPump(
        scope: CoroutineScope,
        onReport: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ): Job = scope.launch(Dispatchers.IO) {
        HostRuntime.debug("Starting XInput input pump for ${candidate.systemPath}")
        val request = UsbRequest()
        if (!request.initialize(connection, inputEndpoint)) {
            onError(IOException("Failed to initialize XInput interrupt reader"))
            return@launch
        }

        val packetSize = inputEndpoint.maxPacketSize.coerceAtLeast(32)
        val buffer = ByteBuffer.allocate(packetSize)
        var reportCount = 0
        var zeroLengthCompletions = 0
        var recoveryAttempts = 0
        var lastRecoveryAt = 0L

        sendInitPacketIfNeeded(reason = "open")

        try {
            while (isActive && !closed.get()) {
                buffer.clear()
                if (!request.queue(buffer)) {
                    throw IOException("Failed to queue XInput interrupt read")
                }

                val completed = try {
                    connection.requestWait(XINPUT_REQUEST_WAIT_TIMEOUT_MS)
                } catch (_: TimeoutException) {
                    runCatching { request.cancel() }
                    if (zeroLengthCompletions > 0) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRecoveryAt >= XINPUT_RECOVERY_INTERVAL_MS) {
                            recoveryAttempts += 1
                            lastRecoveryAt = now
                            sendInitPacketIfNeeded(reason = "zero-length-timeout-recovery-$recoveryAttempts")
                        }
                    }
                    continue
                } ?: continue
                if (completed != request) {
                    continue
                }

                val size = buffer.position()
                if (size <= 0) {
                    zeroLengthCompletions += 1
                    if (zeroLengthCompletions <= XINPUT_REPORT_LOG_LIMIT) {
                        HostRuntime.debug(
                            "XInput read for ${candidate.systemPath} completed with a zero reported length " +
                                "(count=$zeroLengthCompletions)",
                        )
                    }
                    if (zeroLengthCompletions % XINPUT_ZERO_LENGTH_REINIT_EVERY == 0) {
                        recoveryAttempts += 1
                        lastRecoveryAt = SystemClock.elapsedRealtime()
                        sendInitPacketIfNeeded(reason = "zero-length-recovery-$recoveryAttempts")
                    }
                    if (zeroLengthCompletions >= XINPUT_ZERO_LENGTH_RESET_THRESHOLD) {
                        throw IOException(
                            "XInput receiver stopped producing usable reports; resetting the session",
                        )
                    }
                    continue
                }
                buffer.flip()

                val data = ByteArray(size)
                buffer.get(data)
                reportCount += 1
                zeroLengthCompletions = 0
                recoveryAttempts = 0
                if (shouldLogReport(reportCount)) {
                    HostRuntime.debug(
                        "XInput report #$reportCount for ${candidate.systemPath} (${data.size} bytes): ${data.hexSnippet()}",
                    )
                }
                onReport(data)
            }
        } catch (error: Throwable) {
            if (!closed.get()) {
                HostRuntime.logError("XInput input pump failed for ${candidate.systemPath}", error)
                onError(error)
            }
        } finally {
            HostRuntime.debug("Stopping XInput input pump for ${candidate.systemPath}")
            request.close()
        }
    }

    override fun sendOutputReport(reportType: Int, reportId: Int, data: ByteArray): Int {
        if (closed.get()) {
            return 5
        }
        val endpoint = outputEndpoint ?: return 95
        if (data.isEmpty()) {
            return 0
        }
        val request = UsbRequest()
        return try {
            if (!request.initialize(connection, endpoint)) {
                return 5
            }
            val buffer = ByteBuffer.allocateDirect(data.size)
            buffer.put(data)
            buffer.flip()
            if (!queueInterruptRequest(request, buffer)) {
                return 5
            }
            val completed = connection.requestWait(250)
            HostRuntime.debug(
                "Sent XInput output report for ${candidate.systemPath}: type=$reportType id=$reportId size=${data.size} completed=${completed == request}",
            )
            if (completed == request) 0 else 5
        } catch (_: Throwable) {
            5
        } finally {
            request.close()
        }
    }

    override fun getReport(reportType: Int, reportId: Int): ByteArray {
        throw IOException("XInput GET_REPORT is not supported")
    }

    override fun setReport(reportType: Int, reportId: Int, data: ByteArray): Int {
        return 95
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        runCatching {
            connection.releaseInterface(usbInterface)
        }
        runCatching {
            connection.close()
        }
    }

    private fun sendInitPacketIfNeeded(reason: String) {
        val endpoint = outputEndpoint ?: return
        val wiredPacket = byteArrayOf(
            0x01.toByte(),
            0x03.toByte(),
            XINPUT_PLAYER_ONE_COMMAND.toByte(),
        )
        val wirelessPacket = byteArrayOf(
            0x00.toByte(),
            0x00.toByte(),
            0x08.toByte(),
            (0x40 + (XINPUT_PLAYER_ONE_COMMAND % 0x0e)).toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
        )
        val packets = if (prefersWiredXInputInit()) {
            listOf(wiredPacket, wirelessPacket)
        } else {
            listOf(wirelessPacket, wiredPacket)
        }

        packets.forEachIndexed { index, packet ->
            val request = UsbRequest()
            try {
                if (!request.initialize(connection, endpoint)) {
                    HostRuntime.debug("Failed to initialize XInput init request for ${candidate.systemPath}")
                    return
                }
                val buffer = ByteBuffer.allocate(packet.size)
                buffer.put(packet)
                buffer.flip()
                val queued = queueInterruptRequest(request, buffer)
                val completed = if (queued) connection.requestWait(250) else null
                HostRuntime.debug(
                    "Sent XInput init packet ${index + 1}/${packets.size} for ${candidate.systemPath} " +
                        "($reason): " +
                        "${packet.hexSnippet()} completed=${completed == request}",
                )
            } catch (error: Throwable) {
                HostRuntime.logError("Failed to send XInput init packet for ${candidate.systemPath}", error)
            } finally {
                request.close()
            }
        }
    }

    private fun prefersWiredXInputInit(): Boolean {
        return candidate.vendorId == 0x2dc8 && candidate.productId == 0x310a ||
            (
                candidate.interfaceClass == 0xff &&
                    candidate.inputPacketSize >= 32 &&
                    candidate.outputPacketSize >= 32 &&
                    candidate.interfaceProtocol != 0x81
                )
    }

    private fun queueInterruptRequest(
        request: UsbRequest,
        buffer: ByteBuffer,
    ): Boolean = request.queue(buffer)

    private fun shouldLogReport(reportCount: Int): Boolean {
        return reportCount <= XINPUT_REPORT_LOG_LIMIT || reportCount % 100 == 0
    }

    private fun ByteArray.hexSnippet(limit: Int = 24): String {
        return take(limit)
            .joinToString(" ") { byte -> "%02x".format(byte.toInt() and 0xff) } +
            if (size > limit) " ..." else ""
    }
}
