package com.usboss.host

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

object UsbDeviceCatalog {
    fun enumerate(context: Context): List<HidCandidate> {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val devices = mutableListOf<HidCandidate>()
        usbManager.deviceList.values.forEachIndexed { deviceIndex, device ->
            devices += buildCandidatesForDevice(usbManager, device, deviceIndex)
        }
        return devices
    }

    fun requestPermissions(context: Context, devices: List<HidCandidate>, action: String) {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        devices
            .filterNot { it.hasPermission }
            .forEach { candidate ->
                usbManager.deviceList[candidate.deviceName]?.let { device ->
                    usbManager.requestPermission(device, permissionIntent)
                }
            }
    }

    fun open(context: Context, candidate: HidCandidate): OpenedHidDevice {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val device = usbManager.deviceList[candidate.deviceName]
            ?: throw IOException("USB device ${candidate.deviceName} is no longer attached")
        if (!usbManager.hasPermission(device)) {
            throw IOException("USB permission has not been granted for ${candidate.displayName}")
        }

        val usbInterface = (0 until device.interfaceCount)
            .map(device::getInterface)
            .firstOrNull { it.id == candidate.interfaceNumber }
            ?: throw IOException("USB interface ${candidate.interfaceNumber} disappeared")

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open ${candidate.displayName}")
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            throw IOException("Failed to claim HID interface ${candidate.interfaceNumber}")
        }

        val inputEndpoint = usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_IN)
            ?: run {
                connection.releaseInterface(usbInterface)
                connection.close()
                throw IOException("No interrupt IN endpoint found for ${candidate.displayName}")
            }
        return try {
            val outputEndpoint = usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_OUT)
            val descriptor = UsbHidDescriptorParser.fetchReportDescriptor(connection, usbInterface)

            OpenedHidDevice(
                candidate = candidate,
                connection = connection,
                usbInterface = usbInterface,
                inputEndpoint = inputEndpoint,
                outputEndpoint = outputEndpoint,
                reportDescriptor = descriptor,
                versionBcd = UsbHidDescriptorParser.parseBcdVersion(
                    runCatching { device.version }.getOrNull(),
                ),
            )
        } catch (error: Throwable) {
            connection.releaseInterface(usbInterface)
            connection.close()
            throw error
        }
    }

    private fun buildCandidatesForDevice(
        usbManager: UsbManager,
        device: UsbDevice,
        deviceIndex: Int,
    ): List<HidCandidate> {
        return (0 until device.interfaceCount)
            .map(device::getInterface)
            .filter { usbInterface ->
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_IN) != null
            }
            .mapIndexed { interfaceOffset, usbInterface ->
                val input = usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_IN)
                val output = usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_OUT)
                HidCandidate(
                    id = (deviceIndex * 10) + interfaceOffset + 1,
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    interfaceNumber = usbInterface.id,
                    interfaceClass = usbInterface.interfaceClass,
                    interfaceSubclass = usbInterface.interfaceSubclass,
                    interfaceProtocol = usbInterface.interfaceProtocol,
                    inputPacketSize = input?.maxPacketSize ?: 0,
                    outputPacketSize = output?.maxPacketSize ?: 0,
                    hasInterruptOut = output != null,
                    manufacturer = runCatching { device.manufacturerName }.getOrNull().orEmpty(),
                    product = runCatching { device.productName }.getOrNull().orEmpty(),
                    serial = runCatching { device.serialNumber }.getOrNull().orEmpty(),
                    hasPermission = usbManager.hasPermission(device),
                )
            }
    }

    private fun UsbInterface.findInterruptEndpoint(direction: Int): UsbEndpoint? {
        return (0 until endpointCount)
            .map(::getEndpoint)
            .firstOrNull { endpoint ->
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    endpoint.direction == direction
            }
    }
}
