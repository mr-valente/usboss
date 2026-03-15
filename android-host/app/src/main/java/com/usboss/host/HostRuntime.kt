package com.usboss.host

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object HostRuntime {
    const val ACTION_USB_PERMISSION = "com.usboss.host.USB_PERMISSION"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(HostUiState())
    val state = mutableState.asStateFlow()

    @Volatile
    private var server: UsbBridgeServer? = null

    @Volatile
    private var candidatesById: Map<Int, HidCandidate> = emptyMap()

    fun start(context: Context) {
        updateError(null)
        refreshDevices(context)
        if (server != null) {
            mutableState.update { it.copy(serviceRunning = true) }
            return
        }

        val applicationContext = context.applicationContext
        server = UsbBridgeServer(
            scope = scope,
            onStatus = { updateStatus(it) },
            onError = { updateError(it) },
            onClientChanged = { client ->
                mutableState.update { state ->
                    state.copy(connectedClient = client)
                }
            },
            devicesProvider = { state.value.devices.map { it.toSummary() } },
            openDevice = { id ->
                val candidate = candidatesById[id]
                    ?: throw IllegalArgumentException("Unknown HID candidate id $id")
                UsbDeviceCatalog.open(applicationContext, candidate)
            },
        ).also { it.start() }

        mutableState.update {
            it.copy(
                serviceRunning = true,
                status = "Listening for Linux clients",
                serverIp = findLocalIpv4Address().orEmpty(),
            )
        }
    }

    fun stop() {
        server?.stop()
        server = null
        mutableState.update {
            it.copy(
                serviceRunning = false,
                connectedClient = null,
                status = "Stopped",
            )
        }
    }

    fun refreshDevices(context: Context) {
        runCatching {
            val devices = UsbDeviceCatalog.enumerate(context)
            candidatesById = devices.associateBy { it.id }
            mutableState.update {
                it.copy(
                    devices = devices,
                    serverIp = findLocalIpv4Address().orEmpty(),
                    lastError = null,
                )
            }
        }.onFailure { error ->
            mutableState.update {
                it.copy(
                    lastError = "USB refresh failed: ${error.message}",
                )
            }
        }
    }

    fun requestPermissions(context: Context) {
        runCatching {
            UsbDeviceCatalog.requestPermissions(context, state.value.devices, ACTION_USB_PERMISSION)
        }.onFailure { error ->
            updateError("USB permission request failed: ${error.message}")
        }
    }

    fun updateStatus(message: String) {
        mutableState.update { it.copy(status = message) }
    }

    fun updateError(message: String?) {
        mutableState.update { it.copy(lastError = message) }
    }

    private fun findLocalIpv4Address(): String? {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrElse {
            emptyList()
        }
        return interfaces
            .asSequence()
            .filter { !it.isLoopback && it.isUp }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun HidCandidate.toSummary(): Protocol.DeviceSummary {
        return Protocol.DeviceSummary(
            deviceId = id,
            vendorId = vendorId,
            productId = productId,
            interfaceNumber = interfaceNumber,
            interfaceClass = interfaceClass,
            interfaceSubclass = interfaceSubclass,
            interfaceProtocol = interfaceProtocol,
            inputPacketSize = inputPacketSize,
            outputPacketSize = outputPacketSize,
            hasInterruptOut = hasInterruptOut,
            manufacturer = manufacturer,
            product = product,
            serial = serial,
            systemPath = systemPath,
        )
    }
}
