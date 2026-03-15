package com.usboss.host

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import java.io.IOException

object UsbHidDescriptorParser {
    private const val DESCRIPTOR_TYPE_INTERFACE = 0x04
    private const val DESCRIPTOR_TYPE_HID = 0x21
    private const val DESCRIPTOR_TYPE_REPORT = 0x22
    private const val REQUEST_GET_DESCRIPTOR = 0x06

    fun findReportDescriptorLength(rawDescriptors: ByteArray, interfaceNumber: Int): Int? {
        var offset = 0
        var currentInterface = -1
        while (offset + 1 < rawDescriptors.size) {
            val length = rawDescriptors[offset].toInt() and 0xff
            val type = rawDescriptors[offset + 1].toInt() and 0xff
            if (length <= 0 || offset + length > rawDescriptors.size) {
                break
            }

            when (type) {
                DESCRIPTOR_TYPE_INTERFACE -> {
                    if (length >= 9) {
                        currentInterface = rawDescriptors[offset + 2].toInt() and 0xff
                    }
                }

                DESCRIPTOR_TYPE_HID -> {
                    if (currentInterface == interfaceNumber && length >= 9) {
                        val reportLengthLo = rawDescriptors[offset + 7].toInt() and 0xff
                        val reportLengthHi = rawDescriptors[offset + 8].toInt() and 0xff
                        return reportLengthLo or (reportLengthHi shl 8)
                    }
                }
            }

            offset += length
        }

        return null
    }

    fun fetchReportDescriptor(
        connection: UsbDeviceConnection,
        usbInterface: UsbInterface,
    ): ByteArray {
        val rawDescriptors = connection.rawDescriptors
        val expectedLength = findReportDescriptorLength(rawDescriptors, usbInterface.id)
            ?: throw IOException("No HID descriptor found for interface ${usbInterface.id}")
        val buffer = ByteArray(expectedLength)
        val requestType = UsbConstants.USB_DIR_IN or
            UsbConstants.USB_TYPE_STANDARD or
            UsbConstants.USB_RECIP_INTERFACE
        val value = (DESCRIPTOR_TYPE_REPORT shl 8)
        val result = connection.controlTransfer(
            requestType,
            REQUEST_GET_DESCRIPTOR,
            value,
            usbInterface.id,
            buffer,
            buffer.size,
            1_000,
        )
        if (result <= 0) {
            throw IOException("Unable to fetch HID report descriptor for interface ${usbInterface.id}")
        }
        return buffer.copyOf(result)
    }

    fun parseBcdVersion(version: String?): Int {
        if (version.isNullOrBlank()) {
            return 0
        }
        val parts = version.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return 0
        val minor = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toIntOrNull() ?: 0
        return ((major and 0xff) shl 8) or (minor and 0xff)
    }
}
