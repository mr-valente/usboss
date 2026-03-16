package com.usboss.host

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.min

object Protocol {
    const val PROTOCOL_VERSION = 2
    const val DEFAULT_DISCOVERY_PORT = 35_354
    const val DEFAULT_TCP_PORT = 35_355
    const val DISCOVERY_REQUEST = "USBOSS_DISCOVER_V1"

    const val TRANSPORT_HID = 1
    const val TRANSPORT_XINPUT_360 = 2

    const val REPORT_TYPE_INPUT = 1
    const val REPORT_TYPE_OUTPUT = 2
    const val REPORT_TYPE_FEATURE = 3

    private const val TYPE_HELLO = 1
    private const val TYPE_HELLO_ACK = 2
    private const val TYPE_LIST_DEVICES = 10
    private const val TYPE_DEVICES = 11
    private const val TYPE_OPEN_DEVICE = 12
    private const val TYPE_OPEN_DEVICE_ACK = 13
    private const val TYPE_ERROR = 14
    private const val TYPE_INPUT_REPORT = 20
    private const val TYPE_OUTPUT_REPORT = 21
    private const val TYPE_GET_REPORT_REQUEST = 22
    private const val TYPE_GET_REPORT_RESPONSE = 23
    private const val TYPE_SET_REPORT_REQUEST = 24
    private const val TYPE_SET_REPORT_RESPONSE = 25
    private const val TYPE_PING = 26
    private const val TYPE_PONG = 27

    fun discoveryResponse(name: String, port: Int): String = "USBOSS|1|$port|$name"

    data class DeviceSummary(
        val deviceId: Int,
        val transport: Int,
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
        val systemPath: String,
    )

    data class OpenDeviceSpec(
        val transport: Int,
        val vendorId: Int,
        val productId: Int,
        val versionBcd: Int,
        val countryCode: Int,
        val busType: Int,
        val interfaceNumber: Int,
        val inputPacketSize: Int,
        val outputPacketSize: Int,
        val hasInterruptOut: Boolean,
        val name: String,
        val phys: String,
        val uniq: String,
        val reportDescriptor: ByteArray,
    )

    sealed interface Message {
        data class Hello(val clientName: String) : Message
        data class HelloAck(val serverName: String) : Message
        data object ListDevices : Message
        data class Devices(val devices: List<DeviceSummary>) : Message
        data class OpenDevice(val deviceId: Int) : Message
        data class OpenDeviceAck(val spec: OpenDeviceSpec) : Message
        data class Error(val message: String) : Message
        data class InputReport(val data: ByteArray) : Message
        data class OutputReport(
            val reportType: Int,
            val reportId: Int,
            val data: ByteArray,
        ) : Message

        data class GetReportRequest(
            val requestId: Int,
            val reportType: Int,
            val reportId: Int,
        ) : Message

        data class GetReportResponse(
            val requestId: Int,
            val status: Int,
            val data: ByteArray,
        ) : Message

        data class SetReportRequest(
            val requestId: Int,
            val reportType: Int,
            val reportId: Int,
            val data: ByteArray,
        ) : Message

        data class SetReportResponse(
            val requestId: Int,
            val status: Int,
        ) : Message

        data object Ping : Message
        data object Pong : Message
    }

    fun write(output: OutputStream, message: Message) {
        val payload = ByteArrayOutputStream()
        val type = when (message) {
            is Message.Hello -> {
                payload.writeU16(PROTOCOL_VERSION)
                payload.writeString(message.clientName)
                TYPE_HELLO
            }

            is Message.HelloAck -> {
                payload.writeU16(PROTOCOL_VERSION)
                payload.writeString(message.serverName)
                TYPE_HELLO_ACK
            }

            Message.ListDevices -> TYPE_LIST_DEVICES
            is Message.Devices -> {
                payload.writeU16(message.devices.size)
                message.devices.forEach { device ->
                    payload.writeU32(device.deviceId)
                    payload.writeU8(device.transport)
                    payload.writeU8(device.interfaceNumber)
                    payload.writeU8(device.interfaceClass)
                    payload.writeU8(device.interfaceSubclass)
                    payload.writeU8(device.interfaceProtocol)
                    payload.writeU16(device.vendorId)
                    payload.writeU16(device.productId)
                    payload.writeU16(device.inputPacketSize)
                    payload.writeU16(device.outputPacketSize)
                    payload.writeU8(if (device.hasInterruptOut) 1 else 0)
                    payload.writeString(device.manufacturer)
                    payload.writeString(device.product)
                    payload.writeString(device.serial)
                    payload.writeString(device.systemPath)
                }
                TYPE_DEVICES
            }

            is Message.OpenDevice -> {
                payload.writeU32(message.deviceId)
                TYPE_OPEN_DEVICE
            }

            is Message.OpenDeviceAck -> {
                val spec = message.spec
                payload.writeU8(spec.transport)
                payload.writeU16(spec.vendorId)
                payload.writeU16(spec.productId)
                payload.writeU16(spec.versionBcd)
                payload.writeU16(spec.countryCode)
                payload.writeU16(spec.busType)
                payload.writeU8(spec.interfaceNumber)
                payload.writeU16(spec.inputPacketSize)
                payload.writeU16(spec.outputPacketSize)
                payload.writeU8(if (spec.hasInterruptOut) 1 else 0)
                payload.writeString(spec.name)
                payload.writeString(spec.phys)
                payload.writeString(spec.uniq)
                payload.writeSizedBytes(spec.reportDescriptor)
                TYPE_OPEN_DEVICE_ACK
            }

            is Message.Error -> {
                payload.writeString(message.message)
                TYPE_ERROR
            }

            is Message.InputReport -> {
                payload.writeSizedBytes(message.data)
                TYPE_INPUT_REPORT
            }

            is Message.OutputReport -> {
                payload.writeU8(message.reportType)
                payload.writeU8(message.reportId)
                payload.writeSizedBytes(message.data)
                TYPE_OUTPUT_REPORT
            }

            is Message.GetReportRequest -> {
                payload.writeU32(message.requestId)
                payload.writeU8(message.reportType)
                payload.writeU8(message.reportId)
                TYPE_GET_REPORT_REQUEST
            }

            is Message.GetReportResponse -> {
                payload.writeU32(message.requestId)
                payload.writeI32(message.status)
                payload.writeSizedBytes(message.data)
                TYPE_GET_REPORT_RESPONSE
            }

            is Message.SetReportRequest -> {
                payload.writeU32(message.requestId)
                payload.writeU8(message.reportType)
                payload.writeU8(message.reportId)
                payload.writeSizedBytes(message.data)
                TYPE_SET_REPORT_REQUEST
            }

            is Message.SetReportResponse -> {
                payload.writeU32(message.requestId)
                payload.writeI32(message.status)
                TYPE_SET_REPORT_RESPONSE
            }

            Message.Ping -> TYPE_PING
            Message.Pong -> TYPE_PONG
        }

        output.writeU32(type)
        output.writeU32(payload.size())
        payload.writeTo(output)
        output.flush()
    }

    fun read(input: InputStream): Message {
        val type = input.readU32()
        val payload = input.readExact(input.readU32())
        val reader = PayloadReader(payload)
        return when (type) {
            TYPE_HELLO -> {
                reader.expectVersion()
                Message.Hello(reader.readString())
            }

            TYPE_HELLO_ACK -> {
                reader.expectVersion()
                Message.HelloAck(reader.readString())
            }

            TYPE_LIST_DEVICES -> Message.ListDevices
            TYPE_DEVICES -> {
                val count = reader.readU16()
                val devices = buildList(count) {
                    repeat(count) {
                        add(
                            DeviceSummary(
                                deviceId = reader.readU32(),
                                transport = reader.readU8(),
                                interfaceNumber = reader.readU8(),
                                interfaceClass = reader.readU8(),
                                interfaceSubclass = reader.readU8(),
                                interfaceProtocol = reader.readU8(),
                                vendorId = reader.readU16(),
                                productId = reader.readU16(),
                                inputPacketSize = reader.readU16(),
                                outputPacketSize = reader.readU16(),
                                hasInterruptOut = reader.readU8() != 0,
                                manufacturer = reader.readString(),
                                product = reader.readString(),
                                serial = reader.readString(),
                                systemPath = reader.readString(),
                            ),
                        )
                    }
                }
                Message.Devices(devices)
            }

            TYPE_OPEN_DEVICE -> Message.OpenDevice(reader.readU32())
            TYPE_OPEN_DEVICE_ACK -> {
                Message.OpenDeviceAck(
                    OpenDeviceSpec(
                        transport = reader.readU8(),
                        vendorId = reader.readU16(),
                        productId = reader.readU16(),
                        versionBcd = reader.readU16(),
                        countryCode = reader.readU16(),
                        busType = reader.readU16(),
                        interfaceNumber = reader.readU8(),
                        inputPacketSize = reader.readU16(),
                        outputPacketSize = reader.readU16(),
                        hasInterruptOut = reader.readU8() != 0,
                        name = reader.readString(),
                        phys = reader.readString(),
                        uniq = reader.readString(),
                        reportDescriptor = reader.readBytes(),
                    ),
                )
            }

            TYPE_ERROR -> Message.Error(reader.readString())
            TYPE_INPUT_REPORT -> Message.InputReport(reader.readBytes())
            TYPE_OUTPUT_REPORT -> Message.OutputReport(
                reportType = reader.readU8(),
                reportId = reader.readU8(),
                data = reader.readBytes(),
            )

            TYPE_GET_REPORT_REQUEST -> Message.GetReportRequest(
                requestId = reader.readU32(),
                reportType = reader.readU8(),
                reportId = reader.readU8(),
            )

            TYPE_GET_REPORT_RESPONSE -> Message.GetReportResponse(
                requestId = reader.readU32(),
                status = reader.readI32(),
                data = reader.readBytes(),
            )

            TYPE_SET_REPORT_REQUEST -> Message.SetReportRequest(
                requestId = reader.readU32(),
                reportType = reader.readU8(),
                reportId = reader.readU8(),
                data = reader.readBytes(),
            )

            TYPE_SET_REPORT_RESPONSE -> Message.SetReportResponse(
                requestId = reader.readU32(),
                status = reader.readI32(),
            )

            TYPE_PING -> Message.Ping
            TYPE_PONG -> Message.Pong
            else -> throw IllegalArgumentException("Unknown USBoss frame type $type")
        }
    }

    private class PayloadReader(private val payload: ByteArray) {
        private var cursor = 0

        fun expectVersion() {
            val version = readU16()
            require(version == PROTOCOL_VERSION) {
                "Protocol mismatch. Expected $PROTOCOL_VERSION, got $version."
            }
        }

        fun readU8(): Int {
            ensureRemaining(1)
            return payload[cursor++].toInt() and 0xff
        }

        fun readU16(): Int {
            ensureRemaining(2)
            val value = (payload[cursor].toInt() and 0xff) or
                ((payload[cursor + 1].toInt() and 0xff) shl 8)
            cursor += 2
            return value
        }

        fun readU32(): Int {
            ensureRemaining(4)
            val value = (payload[cursor].toInt() and 0xff) or
                ((payload[cursor + 1].toInt() and 0xff) shl 8) or
                ((payload[cursor + 2].toInt() and 0xff) shl 16) or
                ((payload[cursor + 3].toInt() and 0xff) shl 24)
            cursor += 4
            return value
        }

        fun readI32(): Int = readU32()

        fun readBytes(): ByteArray {
            val size = readU16()
            ensureRemaining(size)
            val bytes = payload.copyOfRange(cursor, cursor + size)
            cursor += size
            return bytes
        }

        fun readString(): String = readBytes().decodeToString()

        private fun ensureRemaining(length: Int) {
            if (cursor + length > payload.size) {
                throw EOFException("USBoss payload truncated")
            }
        }
    }

    private fun InputStream.readExact(length: Int): ByteArray {
        var offset = 0
        val data = ByteArray(length)
        while (offset < length) {
            val count = read(data, offset, length - offset)
            if (count < 0) {
                throw EOFException("USBoss frame truncated")
            }
            offset += count
        }
        return data
    }

    private fun InputStream.readU32(): Int {
        val bytes = readExact(4)
        return (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24)
    }

    private fun OutputStream.writeU8(value: Int) {
        write(value and 0xff)
    }

    private fun OutputStream.writeU16(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }

    private fun OutputStream.writeU32(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }

    private fun OutputStream.writeI32(value: Int) {
        writeU32(value)
    }

    private fun OutputStream.writeString(value: String) {
        writeSizedBytes(value.encodeToByteArray())
    }

    private fun OutputStream.writeSizedBytes(value: ByteArray) {
        writeU16(min(value.size, 0xffff))
        write(value, 0, min(value.size, 0xffff))
    }
}
