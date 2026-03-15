package com.usboss.host

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

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
        val request = UsbRequest()
        if (!request.initialize(connection, inputEndpoint)) {
            onError(IOException("Failed to initialize XInput interrupt reader"))
            return@launch
        }

        val packetSize = inputEndpoint.maxPacketSize.coerceAtLeast(32)
        val buffer = ByteBuffer.allocateDirect(packetSize)

        try {
            while (isActive && !closed.get()) {
                buffer.clear()
                if (!request.queue(buffer)) {
                    throw IOException("Failed to queue XInput interrupt read")
                }

                val completed = connection.requestWait(1_000) ?: continue
                if (completed != request) {
                    continue
                }

                val size = buffer.position()
                if (size <= 0) {
                    continue
                }
                val data = ByteArray(size)
                buffer.flip()
                buffer.get(data)
                onReport(data)
            }
        } catch (error: Throwable) {
            if (!closed.get()) {
                onError(error)
            }
        } finally {
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
        val written = connection.bulkTransfer(endpoint, data, data.size, 100)
        return if (written >= 0) 0 else 5
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
}
