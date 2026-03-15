use std::fmt;
use std::io::{self, Read, Write};

pub const PROTOCOL_VERSION: u16 = 2;
pub const DEFAULT_DISCOVERY_PORT: u16 = 35_354;
pub const DEFAULT_TCP_PORT: u16 = 35_355;
pub const DISCOVERY_REQUEST: &[u8] = b"USBOSS_DISCOVER_V1";
pub const DISCOVERY_RESPONSE_PREFIX: &str = "USBOSS|1|";

const TYPE_HELLO: u32 = 1;
const TYPE_HELLO_ACK: u32 = 2;
const TYPE_LIST_DEVICES: u32 = 10;
const TYPE_DEVICES: u32 = 11;
const TYPE_OPEN_DEVICE: u32 = 12;
const TYPE_OPEN_DEVICE_ACK: u32 = 13;
const TYPE_ERROR: u32 = 14;
const TYPE_INPUT_REPORT: u32 = 20;
const TYPE_OUTPUT_REPORT: u32 = 21;
const TYPE_GET_REPORT_REQUEST: u32 = 22;
const TYPE_GET_REPORT_RESPONSE: u32 = 23;
const TYPE_SET_REPORT_REQUEST: u32 = 24;
const TYPE_SET_REPORT_RESPONSE: u32 = 25;
const TYPE_PING: u32 = 26;
const TYPE_PONG: u32 = 27;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DeviceTransport {
    Hid = 1,
    XInput360 = 2,
}

impl DeviceTransport {
    fn from_u8(value: u8) -> io::Result<Self> {
        match value {
            1 => Ok(DeviceTransport::Hid),
            2 => Ok(DeviceTransport::XInput360),
            other => Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("unknown device transport {other}"),
            )),
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            DeviceTransport::Hid => "hid",
            DeviceTransport::XInput360 => "xinput-360",
        }
    }
}

#[derive(Clone, Debug)]
pub struct DeviceSummary {
    pub device_id: u32,
    pub transport: DeviceTransport,
    pub vendor_id: u16,
    pub product_id: u16,
    pub interface_number: u8,
    pub interface_class: u8,
    pub interface_subclass: u8,
    pub interface_protocol: u8,
    pub input_packet_size: u16,
    pub output_packet_size: u16,
    pub has_interrupt_out: bool,
    pub manufacturer: String,
    pub product: String,
    pub serial: String,
    pub system_path: String,
}

#[derive(Clone, Debug)]
pub struct OpenDeviceAck {
    pub transport: DeviceTransport,
    pub vendor_id: u16,
    pub product_id: u16,
    pub version_bcd: u16,
    pub country_code: u16,
    pub bus_type: u16,
    pub interface_number: u8,
    pub input_packet_size: u16,
    pub output_packet_size: u16,
    pub has_interrupt_out: bool,
    pub name: String,
    pub phys: String,
    pub uniq: String,
    pub report_descriptor: Vec<u8>,
}

#[derive(Clone, Debug)]
pub enum Message {
    Hello { client_name: String },
    HelloAck { server_name: String },
    ListDevices,
    Devices { devices: Vec<DeviceSummary> },
    OpenDevice { device_id: u32 },
    OpenDeviceAck(OpenDeviceAck),
    Error { message: String },
    InputReport { data: Vec<u8> },
    OutputReport {
        report_type: u8,
        report_id: u8,
        data: Vec<u8>,
    },
    GetReportRequest {
        request_id: u32,
        report_type: u8,
        report_id: u8,
    },
    GetReportResponse {
        request_id: u32,
        status: i32,
        data: Vec<u8>,
    },
    SetReportRequest {
        request_id: u32,
        report_type: u8,
        report_id: u8,
        data: Vec<u8>,
    },
    SetReportResponse {
        request_id: u32,
        status: i32,
    },
    Ping,
    Pong,
}

impl fmt::Display for DeviceSummary {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "#{:02} {:04x}:{:04x} iface {} {} {} {}",
            self.device_id,
            self.vendor_id,
            self.product_id,
            self.interface_number,
            if self.manufacturer.is_empty() {
                "Unknown".to_string()
            } else {
                self.manufacturer.clone()
            },
            if self.product.is_empty() {
                match self.transport {
                    DeviceTransport::Hid => "USB HID".to_string(),
                    DeviceTransport::XInput360 => "USB XInput".to_string(),
                }
            } else {
                self.product.clone()
            },
            format!("{} [{}]", self.system_path, self.transport.label())
        )
    }
}

pub fn write_message(writer: &mut impl Write, message: &Message) -> io::Result<()> {
    let mut payload = Vec::new();
    let message_type = match message {
        Message::Hello { client_name } => {
            write_u16(&mut payload, PROTOCOL_VERSION);
            write_string(&mut payload, client_name)?;
            TYPE_HELLO
        }
        Message::HelloAck { server_name } => {
            write_u16(&mut payload, PROTOCOL_VERSION);
            write_string(&mut payload, server_name)?;
            TYPE_HELLO_ACK
        }
        Message::ListDevices => TYPE_LIST_DEVICES,
        Message::Devices { devices } => {
            write_u16(&mut payload, try_u16(devices.len())?);
            for device in devices {
                write_u32(&mut payload, device.device_id);
                payload.push(device.transport as u8);
                payload.push(device.interface_number);
                payload.push(device.interface_class);
                payload.push(device.interface_subclass);
                payload.push(device.interface_protocol);
                write_u16(&mut payload, device.vendor_id);
                write_u16(&mut payload, device.product_id);
                write_u16(&mut payload, device.input_packet_size);
                write_u16(&mut payload, device.output_packet_size);
                payload.push(u8::from(device.has_interrupt_out));
                write_string(&mut payload, &device.manufacturer)?;
                write_string(&mut payload, &device.product)?;
                write_string(&mut payload, &device.serial)?;
                write_string(&mut payload, &device.system_path)?;
            }
            TYPE_DEVICES
        }
        Message::OpenDevice { device_id } => {
            write_u32(&mut payload, *device_id);
            TYPE_OPEN_DEVICE
        }
        Message::OpenDeviceAck(spec) => {
            payload.push(spec.transport as u8);
            write_u16(&mut payload, spec.vendor_id);
            write_u16(&mut payload, spec.product_id);
            write_u16(&mut payload, spec.version_bcd);
            write_u16(&mut payload, spec.country_code);
            write_u16(&mut payload, spec.bus_type);
            payload.push(spec.interface_number);
            write_u16(&mut payload, spec.input_packet_size);
            write_u16(&mut payload, spec.output_packet_size);
            payload.push(u8::from(spec.has_interrupt_out));
            write_string(&mut payload, &spec.name)?;
            write_string(&mut payload, &spec.phys)?;
            write_string(&mut payload, &spec.uniq)?;
            write_bytes(&mut payload, &spec.report_descriptor)?;
            TYPE_OPEN_DEVICE_ACK
        }
        Message::Error { message } => {
            write_string(&mut payload, message)?;
            TYPE_ERROR
        }
        Message::InputReport { data } => {
            write_bytes(&mut payload, data)?;
            TYPE_INPUT_REPORT
        }
        Message::OutputReport {
            report_type,
            report_id,
            data,
        } => {
            payload.push(*report_type);
            payload.push(*report_id);
            write_bytes(&mut payload, data)?;
            TYPE_OUTPUT_REPORT
        }
        Message::GetReportRequest {
            request_id,
            report_type,
            report_id,
        } => {
            write_u32(&mut payload, *request_id);
            payload.push(*report_type);
            payload.push(*report_id);
            TYPE_GET_REPORT_REQUEST
        }
        Message::GetReportResponse {
            request_id,
            status,
            data,
        } => {
            write_u32(&mut payload, *request_id);
            write_i32(&mut payload, *status);
            write_bytes(&mut payload, data)?;
            TYPE_GET_REPORT_RESPONSE
        }
        Message::SetReportRequest {
            request_id,
            report_type,
            report_id,
            data,
        } => {
            write_u32(&mut payload, *request_id);
            payload.push(*report_type);
            payload.push(*report_id);
            write_bytes(&mut payload, data)?;
            TYPE_SET_REPORT_REQUEST
        }
        Message::SetReportResponse { request_id, status } => {
            write_u32(&mut payload, *request_id);
            write_i32(&mut payload, *status);
            TYPE_SET_REPORT_RESPONSE
        }
        Message::Ping => TYPE_PING,
        Message::Pong => TYPE_PONG,
    };

    let mut header = [0u8; 8];
    header[0..4].copy_from_slice(&message_type.to_le_bytes());
    header[4..8].copy_from_slice(&(payload.len() as u32).to_le_bytes());
    writer.write_all(&header)?;
    writer.write_all(&payload)?;
    writer.flush()?;
    Ok(())
}

pub fn read_message(reader: &mut impl Read) -> io::Result<Message> {
    let mut header = [0u8; 8];
    reader.read_exact(&mut header)?;
    let message_type = read_u32_at(&header, 0);
    let payload_len = read_u32_at(&header, 4) as usize;
    let mut payload = vec![0u8; payload_len];
    reader.read_exact(&mut payload)?;
    let mut cursor = 0usize;

    match message_type {
        TYPE_HELLO => {
            let version = read_u16(&payload, &mut cursor)?;
            expect_version(version)?;
            Ok(Message::Hello {
                client_name: read_string(&payload, &mut cursor)?,
            })
        }
        TYPE_HELLO_ACK => {
            let version = read_u16(&payload, &mut cursor)?;
            expect_version(version)?;
            Ok(Message::HelloAck {
                server_name: read_string(&payload, &mut cursor)?,
            })
        }
        TYPE_LIST_DEVICES => Ok(Message::ListDevices),
        TYPE_DEVICES => {
            let count = read_u16(&payload, &mut cursor)? as usize;
            let mut devices = Vec::with_capacity(count);
            for _ in 0..count {
                let device_id = read_u32(&payload, &mut cursor)?;
                let transport = DeviceTransport::from_u8(read_u8(&payload, &mut cursor)?)?;
                let interface_number = read_u8(&payload, &mut cursor)?;
                let interface_class = read_u8(&payload, &mut cursor)?;
                let interface_subclass = read_u8(&payload, &mut cursor)?;
                let interface_protocol = read_u8(&payload, &mut cursor)?;
                let vendor_id = read_u16(&payload, &mut cursor)?;
                let product_id = read_u16(&payload, &mut cursor)?;
                let input_packet_size = read_u16(&payload, &mut cursor)?;
                let output_packet_size = read_u16(&payload, &mut cursor)?;
                let has_interrupt_out = read_u8(&payload, &mut cursor)? != 0;
                let manufacturer = read_string(&payload, &mut cursor)?;
                let product = read_string(&payload, &mut cursor)?;
                let serial = read_string(&payload, &mut cursor)?;
                let system_path = read_string(&payload, &mut cursor)?;
                devices.push(DeviceSummary {
                    device_id,
                    transport,
                    vendor_id,
                    product_id,
                    interface_number,
                    interface_class,
                    interface_subclass,
                    interface_protocol,
                    input_packet_size,
                    output_packet_size,
                    has_interrupt_out,
                    manufacturer,
                    product,
                    serial,
                    system_path,
                });
            }
            Ok(Message::Devices { devices })
        }
        TYPE_OPEN_DEVICE => Ok(Message::OpenDevice {
            device_id: read_u32(&payload, &mut cursor)?,
        }),
        TYPE_OPEN_DEVICE_ACK => Ok(Message::OpenDeviceAck(OpenDeviceAck {
            transport: DeviceTransport::from_u8(read_u8(&payload, &mut cursor)?)?,
            vendor_id: read_u16(&payload, &mut cursor)?,
            product_id: read_u16(&payload, &mut cursor)?,
            version_bcd: read_u16(&payload, &mut cursor)?,
            country_code: read_u16(&payload, &mut cursor)?,
            bus_type: read_u16(&payload, &mut cursor)?,
            interface_number: read_u8(&payload, &mut cursor)?,
            input_packet_size: read_u16(&payload, &mut cursor)?,
            output_packet_size: read_u16(&payload, &mut cursor)?,
            has_interrupt_out: read_u8(&payload, &mut cursor)? != 0,
            name: read_string(&payload, &mut cursor)?,
            phys: read_string(&payload, &mut cursor)?,
            uniq: read_string(&payload, &mut cursor)?,
            report_descriptor: read_bytes(&payload, &mut cursor)?,
        })),
        TYPE_ERROR => Ok(Message::Error {
            message: read_string(&payload, &mut cursor)?,
        }),
        TYPE_INPUT_REPORT => Ok(Message::InputReport {
            data: read_bytes(&payload, &mut cursor)?,
        }),
        TYPE_OUTPUT_REPORT => Ok(Message::OutputReport {
            report_type: read_u8(&payload, &mut cursor)?,
            report_id: read_u8(&payload, &mut cursor)?,
            data: read_bytes(&payload, &mut cursor)?,
        }),
        TYPE_GET_REPORT_REQUEST => Ok(Message::GetReportRequest {
            request_id: read_u32(&payload, &mut cursor)?,
            report_type: read_u8(&payload, &mut cursor)?,
            report_id: read_u8(&payload, &mut cursor)?,
        }),
        TYPE_GET_REPORT_RESPONSE => Ok(Message::GetReportResponse {
            request_id: read_u32(&payload, &mut cursor)?,
            status: read_i32(&payload, &mut cursor)?,
            data: read_bytes(&payload, &mut cursor)?,
        }),
        TYPE_SET_REPORT_REQUEST => Ok(Message::SetReportRequest {
            request_id: read_u32(&payload, &mut cursor)?,
            report_type: read_u8(&payload, &mut cursor)?,
            report_id: read_u8(&payload, &mut cursor)?,
            data: read_bytes(&payload, &mut cursor)?,
        }),
        TYPE_SET_REPORT_RESPONSE => Ok(Message::SetReportResponse {
            request_id: read_u32(&payload, &mut cursor)?,
            status: read_i32(&payload, &mut cursor)?,
        }),
        TYPE_PING => Ok(Message::Ping),
        TYPE_PONG => Ok(Message::Pong),
        other => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("unknown frame type {other}"),
        )),
    }
}

fn expect_version(version: u16) -> io::Result<()> {
    if version != PROTOCOL_VERSION {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("protocol version mismatch: expected {PROTOCOL_VERSION}, got {version}"),
        ));
    }
    Ok(())
}

fn try_u16(value: usize) -> io::Result<u16> {
    u16::try_from(value).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "value too large"))
}

fn write_u16(buffer: &mut Vec<u8>, value: u16) {
    buffer.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(buffer: &mut Vec<u8>, value: u32) {
    buffer.extend_from_slice(&value.to_le_bytes());
}

fn write_i32(buffer: &mut Vec<u8>, value: i32) {
    buffer.extend_from_slice(&value.to_le_bytes());
}

fn write_string(buffer: &mut Vec<u8>, value: &str) -> io::Result<()> {
    write_bytes(buffer, value.as_bytes())
}

fn write_bytes(buffer: &mut Vec<u8>, value: &[u8]) -> io::Result<()> {
    write_u16(buffer, try_u16(value.len())?);
    buffer.extend_from_slice(value);
    Ok(())
}

fn read_u8(payload: &[u8], cursor: &mut usize) -> io::Result<u8> {
    if *cursor >= payload.len() {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "short payload"));
    }
    let value = payload[*cursor];
    *cursor += 1;
    Ok(value)
}

fn read_u16(payload: &[u8], cursor: &mut usize) -> io::Result<u16> {
    let end = *cursor + 2;
    if end > payload.len() {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "short payload"));
    }
    let value = u16::from_le_bytes([payload[*cursor], payload[*cursor + 1]]);
    *cursor = end;
    Ok(value)
}

fn read_u32(payload: &[u8], cursor: &mut usize) -> io::Result<u32> {
    let end = *cursor + 4;
    if end > payload.len() {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "short payload"));
    }
    let value = u32::from_le_bytes([
        payload[*cursor],
        payload[*cursor + 1],
        payload[*cursor + 2],
        payload[*cursor + 3],
    ]);
    *cursor = end;
    Ok(value)
}

fn read_i32(payload: &[u8], cursor: &mut usize) -> io::Result<i32> {
    Ok(read_u32(payload, cursor)? as i32)
}

fn read_string(payload: &[u8], cursor: &mut usize) -> io::Result<String> {
    let bytes = read_bytes(payload, cursor)?;
    String::from_utf8(bytes).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

fn read_bytes(payload: &[u8], cursor: &mut usize) -> io::Result<Vec<u8>> {
    let len = read_u16(payload, cursor)? as usize;
    let end = *cursor + len;
    if end > payload.len() {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "short payload"));
    }
    let bytes = payload[*cursor..end].to_vec();
    *cursor = end;
    Ok(bytes)
}

fn read_u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}
