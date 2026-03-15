use std::fs::{File, OpenOptions};
use std::io::{self, Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use crate::protocol::{Message, OpenDeviceAck};

const UHID_DATA_MAX: usize = 4096;
const UHID_NAME_SIZE: usize = 128;
const UHID_PHYS_SIZE: usize = 64;
const UHID_UNIQ_SIZE: usize = 64;
const UHID_EVENT_SIZE: usize = 4380;

const UHID_DESTROY: u32 = 1;
const UHID_START: u32 = 2;
const UHID_STOP: u32 = 3;
const UHID_OPEN: u32 = 4;
const UHID_CLOSE: u32 = 5;
const UHID_OUTPUT: u32 = 6;
const UHID_GET_REPORT: u32 = 9;
const UHID_GET_REPORT_REPLY: u32 = 10;
const UHID_CREATE2: u32 = 11;
const UHID_INPUT2: u32 = 12;
const UHID_SET_REPORT: u32 = 13;
const UHID_SET_REPORT_REPLY: u32 = 14;

const UHID_FEATURE_REPORT: u8 = 0;
const UHID_OUTPUT_REPORT: u8 = 1;
const UHID_INPUT_REPORT: u8 = 2;

pub struct UhidDevice {
    writer: Arc<Mutex<File>>,
    reader: Arc<Mutex<File>>,
    shutting_down: Arc<AtomicBool>,
}

impl UhidDevice {
    pub fn create(spec: &OpenDeviceAck) -> io::Result<Self> {
        if spec.report_descriptor.is_empty() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "device has no HID report descriptor",
            ));
        }
        if spec.report_descriptor.len() > UHID_DATA_MAX {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "report descriptor exceeds UHID maximum",
            ));
        }

        let file = OpenOptions::new().read(true).write(true).open("/dev/uhid")?;
        let writer = file.try_clone()?;
        let device = Self {
            writer: Arc::new(Mutex::new(writer)),
            reader: Arc::new(Mutex::new(file)),
            shutting_down: Arc::new(AtomicBool::new(false)),
        };
        device.write_create2(spec)?;
        Ok(device)
    }

    pub fn send_input(&self, data: &[u8]) -> io::Result<()> {
        if data.len() > UHID_DATA_MAX {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "input report exceeds UHID max size",
            ));
        }
        let mut event = vec![0u8; UHID_EVENT_SIZE];
        write_u32(&mut event, 0, UHID_INPUT2);
        write_u16(&mut event, 4, data.len() as u16);
        event[6..6 + data.len()].copy_from_slice(data);
        self.write_event(&event)
    }

    pub fn reply_get_report(&self, request_id: u32, status: i32, data: &[u8]) -> io::Result<()> {
        if data.len() > UHID_DATA_MAX {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "feature report exceeds UHID max size",
            ));
        }
        let mut event = vec![0u8; UHID_EVENT_SIZE];
        write_u32(&mut event, 0, UHID_GET_REPORT_REPLY);
        write_u32(&mut event, 4, request_id);
        write_u16(&mut event, 8, clamp_errno(status));
        write_u16(&mut event, 10, data.len() as u16);
        event[12..12 + data.len()].copy_from_slice(data);
        self.write_event(&event)
    }

    pub fn reply_set_report(&self, request_id: u32, status: i32) -> io::Result<()> {
        let mut event = vec![0u8; UHID_EVENT_SIZE];
        write_u32(&mut event, 0, UHID_SET_REPORT_REPLY);
        write_u32(&mut event, 4, request_id);
        write_u16(&mut event, 8, clamp_errno(status));
        self.write_event(&event)
    }

    pub fn destroy(&self) -> io::Result<()> {
        self.shutting_down.store(true, Ordering::SeqCst);
        let mut event = vec![0u8; UHID_EVENT_SIZE];
        write_u32(&mut event, 0, UHID_DESTROY);
        self.write_event(&event)
    }

    pub fn run_event_loop<F>(&self, mut on_message: F) -> io::Result<()>
    where
        F: FnMut(Message) -> io::Result<()>,
    {
        let mut buffer = vec![0u8; UHID_EVENT_SIZE];
        loop {
            let read_result = {
                let mut reader = lock(&self.reader)?;
                reader.read_exact(&mut buffer)
            };
            if let Err(error) = read_result {
                if self.shutting_down.load(Ordering::SeqCst) {
                    return Ok(());
                }
                return Err(error);
            }

            match read_u32(&buffer, 0) {
                UHID_START => {
                    let dev_flags = read_u64(&buffer, 4);
                    eprintln!("USBoss: UHID device started (flags=0x{dev_flags:x})");
                }
                UHID_STOP => {
                    eprintln!("USBoss: UHID device stopped");
                    if self.shutting_down.load(Ordering::SeqCst) {
                        return Ok(());
                    }
                }
                UHID_OPEN => {
                    eprintln!("USBoss: Linux opened the virtual HID device");
                }
                UHID_CLOSE => {
                    eprintln!("USBoss: Linux closed the virtual HID device");
                    if self.shutting_down.load(Ordering::SeqCst) {
                        return Ok(());
                    }
                }
                UHID_OUTPUT => {
                    let size = read_u16(&buffer, 4) as usize;
                    let report_type = read_u8(&buffer, 6);
                    let end = 7 + size;
                    if end > buffer.len() {
                        continue;
                    }
                    let data = buffer[7..end].to_vec();
                    let report_id = data.first().copied().unwrap_or(0);
                    on_message(Message::OutputReport {
                        report_type: uhid_to_usb_report_type(report_type),
                        report_id,
                        data,
                    })?;
                }
                UHID_GET_REPORT => {
                    let request_id = read_u32(&buffer, 4);
                    let report_id = read_u8(&buffer, 8);
                    let report_type = read_u8(&buffer, 9);
                    on_message(Message::GetReportRequest {
                        request_id,
                        report_type: uhid_to_usb_report_type(report_type),
                        report_id,
                    })?;
                }
                UHID_SET_REPORT => {
                    let request_id = read_u32(&buffer, 4);
                    let report_id = read_u8(&buffer, 8);
                    let report_type = read_u8(&buffer, 9);
                    let size = read_u16(&buffer, 10) as usize;
                    let end = 12 + size;
                    if end > buffer.len() {
                        continue;
                    }
                    let data = buffer[12..end].to_vec();
                    on_message(Message::SetReportRequest {
                        request_id,
                        report_type: uhid_to_usb_report_type(report_type),
                        report_id,
                        data,
                    })?;
                }
                other => {
                    eprintln!("USBoss: ignoring UHID event type {other}");
                }
            }
        }
    }

    fn write_create2(&self, spec: &OpenDeviceAck) -> io::Result<()> {
        let mut event = vec![0u8; UHID_EVENT_SIZE];
        write_u32(&mut event, 0, UHID_CREATE2);
        write_padded_string(&mut event[4..4 + UHID_NAME_SIZE], &spec.name);
        write_padded_string(
            &mut event[4 + UHID_NAME_SIZE..4 + UHID_NAME_SIZE + UHID_PHYS_SIZE],
            &spec.phys,
        );
        write_padded_string(
            &mut event[4 + UHID_NAME_SIZE + UHID_PHYS_SIZE
                ..4 + UHID_NAME_SIZE + UHID_PHYS_SIZE + UHID_UNIQ_SIZE],
            &spec.uniq,
        );
        let cursor = 4 + UHID_NAME_SIZE + UHID_PHYS_SIZE + UHID_UNIQ_SIZE;
        write_u16(&mut event, cursor, spec.report_descriptor.len() as u16);
        write_u16(&mut event, cursor + 2, spec.bus_type);
        write_u32(&mut event, cursor + 4, spec.vendor_id as u32);
        write_u32(&mut event, cursor + 8, spec.product_id as u32);
        write_u32(&mut event, cursor + 12, spec.version_bcd as u32);
        write_u32(&mut event, cursor + 16, spec.country_code as u32);
        let rd_offset = cursor + 20;
        event[rd_offset..rd_offset + spec.report_descriptor.len()]
            .copy_from_slice(&spec.report_descriptor);
        self.write_event(&event)
    }

    fn write_event(&self, bytes: &[u8]) -> io::Result<()> {
        let mut writer = lock(&self.writer)?;
        writer.write_all(bytes)?;
        writer.flush()
    }
}

impl Drop for UhidDevice {
    fn drop(&mut self) {
        let _ = self.destroy();
    }
}

fn write_padded_string(buffer: &mut [u8], value: &str) {
    buffer.fill(0);
    let bytes = value.as_bytes();
    let len = bytes.len().min(buffer.len().saturating_sub(1));
    buffer[..len].copy_from_slice(&bytes[..len]);
}

fn write_u16(buffer: &mut [u8], offset: usize, value: u16) {
    buffer[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn write_u32(buffer: &mut [u8], offset: usize, value: u32) {
    buffer[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn read_u8(buffer: &[u8], offset: usize) -> u8 {
    buffer[offset]
}

fn read_u16(buffer: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([buffer[offset], buffer[offset + 1]])
}

fn read_u32(buffer: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        buffer[offset],
        buffer[offset + 1],
        buffer[offset + 2],
        buffer[offset + 3],
    ])
}

fn read_u64(buffer: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes([
        buffer[offset],
        buffer[offset + 1],
        buffer[offset + 2],
        buffer[offset + 3],
        buffer[offset + 4],
        buffer[offset + 5],
        buffer[offset + 6],
        buffer[offset + 7],
    ])
}

fn uhid_to_usb_report_type(report_type: u8) -> u8 {
    match report_type {
        UHID_INPUT_REPORT => 1,
        UHID_OUTPUT_REPORT => 2,
        UHID_FEATURE_REPORT => 3,
        _ => 0,
    }
}

fn clamp_errno(status: i32) -> u16 {
    if status <= 0 {
        0
    } else {
        status.min(u16::MAX as i32) as u16
    }
}

fn lock<T>(mutex: &Mutex<T>) -> io::Result<std::sync::MutexGuard<'_, T>> {
    mutex
        .lock()
        .map_err(|_| io::Error::new(io::ErrorKind::Other, "mutex poisoned"))
}
