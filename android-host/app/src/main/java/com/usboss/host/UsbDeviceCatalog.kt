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
    private const val USB_CLASS_VENDOR_SPEC = 0xff
    private const val XINPUT_CLASS = 0xff
    private const val XINPUT_SUBCLASS = 0x5d
    private const val XINPUT_PROTOCOL = 0x01
    private const val XUSB_CLASS = 0x58
    private const val XUSB_SUBCLASS = 0x42
    private const val XUSB_PROTOCOL = 0x00
    private const val VENDOR_8BITDO = 0x2dc8

    fun enumerate(context: Context): List<UsbCandidate> {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val devices = usbManager.deviceList.values
            .sortedBy { it.deviceName }
            .flatMap { device ->
                buildCandidatesForDevice(usbManager, device)
            }
            .sortedWith(
            compareByDescending<UsbCandidate> {
                if (it.transport == Protocol.TRANSPORT_XINPUT_360) 1 else 0
            }
                .thenBy { it.deviceName }
                .thenBy { it.interfaceNumber }
                .thenBy { it.vendorId }
                .thenBy { it.productId },
        )
        return devices.mapIndexed { index, candidate ->
            candidate.copy(id = index + 1)
        }
    }

    fun requestPermissions(context: Context, devices: List<UsbCandidate>, action: String) {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        devices
            .filterNot { it.hasPermission }
            .distinctBy { it.deviceName }
            .forEach { candidate ->
                HostRuntime.debug("Requesting permission for ${candidate.displayName} at ${candidate.systemPath}")
                usbManager.deviceList[candidate.deviceName]?.let { device ->
                    usbManager.requestPermission(device, permissionIntent)
                }
            }
    }

    fun open(context: Context, candidate: UsbCandidate): OpenedUsbDevice {
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
            throw IOException("Failed to claim controller interface ${candidate.interfaceNumber}")
        }
        HostRuntime.note(
            "Opening ${candidate.displayName} (${candidate.transportLabel}) on ${candidate.systemPath}",
            addToRecent = true,
        )

        val inputEndpoint = usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_IN)
            ?: run {
                connection.releaseInterface(usbInterface)
                connection.close()
                throw IOException("No interrupt IN endpoint found for ${candidate.displayName}")
            }
        return try {
            val outputEndpoint = usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_OUT)
            HostRuntime.debug(
                "Claimed ${candidate.systemPath}: class=${usbInterface.interfaceClass} " +
                    "subclass=${usbInterface.interfaceSubclass} protocol=${usbInterface.interfaceProtocol} " +
                    "input=${inputEndpoint.maxPacketSize} output=${outputEndpoint?.maxPacketSize ?: 0}",
            )
            when (candidate.transport) {
                Protocol.TRANSPORT_XINPUT_360 -> {
                    OpenedXInputDevice(
                        candidate = candidate,
                        connection = connection,
                        usbInterface = usbInterface,
                        inputEndpoint = inputEndpoint,
                        outputEndpoint = outputEndpoint,
                        versionBcd = UsbHidDescriptorParser.parseBcdVersion(
                            runCatching { device.version }.getOrNull(),
                        ),
                    )
                }

                else -> {
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
                }
            }
        } catch (error: Throwable) {
            connection.releaseInterface(usbInterface)
            connection.close()
            HostRuntime.logError("Failed while opening ${candidate.displayName}", error)
            throw error
        }
    }

    private fun buildCandidatesForDevice(
        usbManager: UsbManager,
        device: UsbDevice,
    ): List<UsbCandidate> {
        val candidates = (0 until device.interfaceCount)
            .map(device::getInterface)
            .filter { usbInterface ->
                usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_IN) != null &&
                    classifyTransport(device, usbInterface) != null
            }
            .mapIndexed { interfaceOffset, usbInterface ->
                val transport = checkNotNull(classifyTransport(device, usbInterface))
                val input = usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_IN)
                val output = usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_OUT)
                UsbCandidate(
                    id = interfaceOffset + 1,
                    deviceName = device.deviceName,
                    transport = transport,
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
        val hasXInput = candidates.any { it.transport == Protocol.TRANSPORT_XINPUT_360 }
        if (!hasXInput) {
            return candidates
        }
        val filtered = candidates.filter { it.transport == Protocol.TRANSPORT_XINPUT_360 }
        HostRuntime.debug(
            "Preferring XInput interfaces for ${device.deviceName}; hiding ${candidates.size - filtered.size} HID sibling(s)",
        )
        return filtered
    }

    private fun classifyTransport(device: UsbDevice, usbInterface: UsbInterface): Int? {
        if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID) {
            return Protocol.TRANSPORT_HID
        }

        val isKnownXInputSignature =
            (usbInterface.interfaceClass == XINPUT_CLASS &&
                usbInterface.interfaceSubclass == XINPUT_SUBCLASS &&
                usbInterface.interfaceProtocol == XINPUT_PROTOCOL) ||
                (usbInterface.interfaceClass == XUSB_CLASS &&
                    usbInterface.interfaceSubclass == XUSB_SUBCLASS &&
                    usbInterface.interfaceProtocol == XUSB_PROTOCOL)

        val isLikely8BitDoXInput =
            device.vendorId == VENDOR_8BITDO &&
                usbInterface.interfaceClass == USB_CLASS_VENDOR_SPEC &&
                usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_OUT) != null

        return if (isKnownXInputSignature || isLikely8BitDoXInput) {
            Protocol.TRANSPORT_XINPUT_360
        } else {
            null
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
