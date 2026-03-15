package com.usboss.host

data class HostUiState(
    val serviceRunning: Boolean = false,
    val status: String = "Idle",
    val serverIp: String = "",
    val tcpPort: Int = Protocol.DEFAULT_TCP_PORT,
    val discoveryPort: Int = Protocol.DEFAULT_DISCOVERY_PORT,
    val connectedClient: String? = null,
    val devices: List<HidCandidate> = emptyList(),
    val lastError: String? = null,
)

data class HidCandidate(
    val id: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val interfaceNumber: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val inputPacketSize: Int,
    val outputPacketSize: Int,
    val hasInterruptOut: Boolean,
    val manufacturer: String,
    val product: String,
    val serial: String,
    val hasPermission: Boolean,
) {
    val displayName: String
        get() = listOf(manufacturer, product)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "USB HID ${vendorId.toString(16)}:${productId.toString(16)}" }

    val systemPath: String
        get() = "$deviceName#if$interfaceNumber"
}
