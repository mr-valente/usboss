package com.usboss.host

import android.hardware.usb.UsbConstants
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

private const val USB_RECIP_INTERFACE = 0x01

class OpenedHidDevice(
    private val candidate: UsbCandidate,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inputEndpoint: UsbEndpoint,
    private val outputEndpoint: UsbEndpoint?,
    private val reportDescriptor: ByteArray,
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
            transport = Protocol.TRANSPORT_HID,
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
            reportDescriptor = reportDescriptor,
        )
    }

    override fun startInputPump(
        scope: CoroutineScope,
        onReport: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ): Job = scope.launch(Dispatchers.IO) {
        HostRuntime.debug("Starting HID input pump for ${candidate.systemPath}")
        val request = UsbRequest()
        if (!request.initialize(connection, inputEndpoint)) {
            onError(IOException("Failed to initialize interrupt reader"))
            return@launch
        }

        val packetSize = inputEndpoint.maxPacketSize.coerceAtLeast(8)
        val buffer = ByteBuffer.allocateDirect(packetSize)

        try {
            while (isActive && !closed.get()) {
                buffer.clear()
                if (!request.queue(buffer)) {
                    throw IOException("Failed to queue USB interrupt read")
                }

                // A controller can sit idle for long stretches, so wait for the next packet
                // instead of treating a timed wait as a fatal transport error.
                val completed = connection.requestWait() ?: continue
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
                HostRuntime.logError("HID input pump failed for ${candidate.systemPath}", error)
                onError(error)
            }
        } finally {
            HostRuntime.debug("Stopping HID input pump for ${candidate.systemPath}")
            request.close()
        }
    }

    override fun sendOutputReport(reportType: Int, reportId: Int, data: ByteArray): Int {
        if (closed.get()) {
            return 5
        }

        if (outputEndpoint != null && data.isNotEmpty()) {
            val written = connection.bulkTransfer(outputEndpoint, data, data.size, 50)
            if (written >= 0) {
                HostRuntime.debug(
                    "Sent HID interrupt OUT report for ${candidate.systemPath}: type=$reportType id=$reportId size=${data.size}",
                )
                return 0
            }
        }

        return setReport(reportType, reportId, data)
    }

    override fun getReport(reportType: Int, reportId: Int): ByteArray {
        if (closed.get()) {
            throw IOException("USB device is closed")
        }
        val buffer = ByteArray(4_096)
        val result = connection.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE,
            0x01,
            ((usbReportType(reportType) and 0xff) shl 8) or (reportId and 0xff),
            usbInterface.id,
            buffer,
            buffer.size,
            1_000,
        )
        if (result < 0) {
            throw IOException("GET_REPORT failed for interface ${usbInterface.id}")
        }
        return buffer.copyOf(result)
    }

    override fun setReport(reportType: Int, reportId: Int, data: ByteArray): Int {
        if (closed.get()) {
            return 5
        }

        val result = connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE,
            0x09,
            ((usbReportType(reportType) and 0xff) shl 8) or (reportId and 0xff),
            usbInterface.id,
            data,
            data.size,
            1_000,
        )
        HostRuntime.debug(
            "Sent HID control SET_REPORT for ${candidate.systemPath}: type=$reportType id=$reportId size=${data.size} result=$result",
        )
        return if (result >= 0) 0 else 5
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

    private fun usbReportType(reportType: Int): Int = when (reportType) {
        Protocol.REPORT_TYPE_INPUT -> 1
        Protocol.REPORT_TYPE_OUTPUT -> 2
        Protocol.REPORT_TYPE_FEATURE -> 3
        else -> 3
    }
}
