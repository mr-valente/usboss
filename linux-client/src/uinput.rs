use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::mem::{size_of, zeroed};
use std::os::fd::AsRawFd;
use std::thread;
use std::time::Duration;

use libc::{c_int, timeval};

use crate::protocol::OpenDeviceAck;

const UINPUT_MAX_NAME_SIZE: usize = 80;
const ABS_CNT: usize = 64;

const EV_SYN: u16 = 0x00;
const EV_KEY: u16 = 0x01;
const EV_ABS: u16 = 0x03;

const SYN_REPORT: u16 = 0;

const BTN_A: u16 = 304;
const BTN_B: u16 = 305;
const BTN_X: u16 = 307;
const BTN_Y: u16 = 308;
const BTN_TL: u16 = 310;
const BTN_TR: u16 = 311;
const BTN_SELECT: u16 = 314;
const BTN_START: u16 = 315;
const BTN_MODE: u16 = 316;
const BTN_THUMBL: u16 = 317;
const BTN_THUMBR: u16 = 318;

const ABS_X: usize = 0;
const ABS_Y: usize = 1;
const ABS_Z: usize = 2;
const ABS_RX: usize = 3;
const ABS_RY: usize = 4;
const ABS_RZ: usize = 5;
const ABS_HAT0X: usize = 16;
const ABS_HAT0Y: usize = 17;

const UINPUT_IOCTL_BASE: u32 = b'U' as u32;
const IOC_NRBITS: u32 = 8;
const IOC_TYPEBITS: u32 = 8;
const IOC_SIZEBITS: u32 = 14;
const IOC_NRSHIFT: u32 = 0;
const IOC_TYPESHIFT: u32 = IOC_NRSHIFT + IOC_NRBITS;
const IOC_SIZESHIFT: u32 = IOC_TYPESHIFT + IOC_TYPEBITS;
const IOC_DIRSHIFT: u32 = IOC_SIZESHIFT + IOC_SIZEBITS;
const IOC_NONE: u32 = 0;
const IOC_WRITE: u32 = 1;

const fn ioc(dir: u32, ty: u32, nr: u32, size: u32) -> libc::c_ulong {
    ((dir << IOC_DIRSHIFT)
        | (ty << IOC_TYPESHIFT)
        | (nr << IOC_NRSHIFT)
        | (size << IOC_SIZESHIFT)) as libc::c_ulong
}

const fn io(ty: u32, nr: u32) -> libc::c_ulong {
    ioc(IOC_NONE, ty, nr, 0)
}

const fn iow_int(ty: u32, nr: u32) -> libc::c_ulong {
    ioc(IOC_WRITE, ty, nr, size_of::<c_int>() as u32)
}

const UI_DEV_CREATE: libc::c_ulong = io(UINPUT_IOCTL_BASE, 1);
const UI_DEV_DESTROY: libc::c_ulong = io(UINPUT_IOCTL_BASE, 2);
const UI_SET_EVBIT: libc::c_ulong = iow_int(UINPUT_IOCTL_BASE, 100);
const UI_SET_KEYBIT: libc::c_ulong = iow_int(UINPUT_IOCTL_BASE, 101);
const UI_SET_ABSBIT: libc::c_ulong = iow_int(UINPUT_IOCTL_BASE, 103);

#[repr(C)]
struct InputId {
    bustype: u16,
    vendor: u16,
    product: u16,
    version: u16,
}

#[repr(C)]
struct UinputUserDev {
    name: [u8; UINPUT_MAX_NAME_SIZE],
    id: InputId,
    ff_effects_max: u32,
    absmax: [i32; ABS_CNT],
    absmin: [i32; ABS_CNT],
    absfuzz: [i32; ABS_CNT],
    absflat: [i32; ABS_CNT],
}

#[repr(C)]
struct InputEvent {
    time: timeval,
    type_: u16,
    code: u16,
    value: i32,
}

pub struct XInput360Device {
    file: File,
}

impl XInput360Device {
    pub fn create(spec: &OpenDeviceAck) -> io::Result<Self> {
        let mut file = open_uinput()?;
        let fd = file.as_raw_fd();

        ioctl_int(fd, UI_SET_EVBIT, EV_KEY as i32)?;
        ioctl_int(fd, UI_SET_EVBIT, EV_ABS as i32)?;

        for key in [
            BTN_A,
            BTN_B,
            BTN_X,
            BTN_Y,
            BTN_TL,
            BTN_TR,
            BTN_SELECT,
            BTN_START,
            BTN_MODE,
            BTN_THUMBL,
            BTN_THUMBR,
        ] {
            ioctl_int(fd, UI_SET_KEYBIT, key as i32)?;
        }

        for abs in [ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y] {
            ioctl_int(fd, UI_SET_ABSBIT, abs as i32)?;
        }

        let mut dev: UinputUserDev = unsafe { zeroed() };
        write_padded_string(&mut dev.name, &spec.name);
        dev.id = InputId {
            bustype: spec.bus_type,
            vendor: spec.vendor_id,
            product: spec.product_id,
            version: spec.version_bcd,
        };

        dev.absmin[ABS_X] = i16::MIN as i32;
        dev.absmax[ABS_X] = i16::MAX as i32;
        dev.absmin[ABS_Y] = i16::MIN as i32;
        dev.absmax[ABS_Y] = i16::MAX as i32;
        dev.absmin[ABS_RX] = i16::MIN as i32;
        dev.absmax[ABS_RX] = i16::MAX as i32;
        dev.absmin[ABS_RY] = i16::MIN as i32;
        dev.absmax[ABS_RY] = i16::MAX as i32;
        dev.absmin[ABS_Z] = 0;
        dev.absmax[ABS_Z] = 255;
        dev.absmin[ABS_RZ] = 0;
        dev.absmax[ABS_RZ] = 255;
        dev.absmin[ABS_HAT0X] = -1;
        dev.absmax[ABS_HAT0X] = 1;
        dev.absmin[ABS_HAT0Y] = -1;
        dev.absmax[ABS_HAT0Y] = 1;

        file.write_all(as_bytes(&dev))?;
        ioctl_none(fd, UI_DEV_CREATE)?;
        thread::sleep(Duration::from_millis(50));

        Ok(Self { file })
    }

    pub fn send_input_report(&mut self, data: &[u8]) -> io::Result<()> {
        let state = match parse_xinput360_report(data) {
            Some(state) => state,
            None => return Ok(()),
        };

        self.emit_key(BTN_START, state.start)?;
        self.emit_key(BTN_SELECT, state.back)?;
        self.emit_key(BTN_MODE, state.guide)?;
        self.emit_key(BTN_THUMBL, state.thumb_l)?;
        self.emit_key(BTN_THUMBR, state.thumb_r)?;
        self.emit_key(BTN_TL, state.lb)?;
        self.emit_key(BTN_TR, state.rb)?;
        self.emit_key(BTN_A, state.a)?;
        self.emit_key(BTN_B, state.b)?;
        self.emit_key(BTN_X, state.x)?;
        self.emit_key(BTN_Y, state.y)?;

        self.emit_abs(ABS_HAT0X as u16, state.hat_x)?;
        self.emit_abs(ABS_HAT0Y as u16, state.hat_y)?;
        self.emit_abs(ABS_Z as u16, state.lt)?;
        self.emit_abs(ABS_RZ as u16, state.rt)?;
        self.emit_abs(ABS_X as u16, state.lx)?;
        self.emit_abs(ABS_Y as u16, state.ly)?;
        self.emit_abs(ABS_RX as u16, state.rx)?;
        self.emit_abs(ABS_RY as u16, state.ry)?;
        self.emit(EV_SYN, SYN_REPORT, 0)
    }

    pub fn destroy(&self) -> io::Result<()> {
        ioctl_none(self.file.as_raw_fd(), UI_DEV_DESTROY)
    }

    fn emit_key(&mut self, code: u16, pressed: bool) -> io::Result<()> {
        self.emit(EV_KEY, code, i32::from(pressed))
    }

    fn emit_abs(&mut self, code: u16, value: i32) -> io::Result<()> {
        self.emit(EV_ABS, code, value)
    }

    fn emit(&mut self, event_type: u16, code: u16, value: i32) -> io::Result<()> {
        let event = InputEvent {
            time: timeval { tv_sec: 0, tv_usec: 0 },
            type_: event_type,
            code,
            value,
        };
        self.file.write_all(as_bytes(&event))
    }
}

impl Drop for XInput360Device {
    fn drop(&mut self) {
        let _ = self.destroy();
    }
}

struct XInput360State {
    hat_x: i32,
    hat_y: i32,
    start: bool,
    back: bool,
    thumb_l: bool,
    thumb_r: bool,
    lb: bool,
    rb: bool,
    guide: bool,
    a: bool,
    b: bool,
    x: bool,
    y: bool,
    lt: i32,
    rt: i32,
    lx: i32,
    ly: i32,
    rx: i32,
    ry: i32,
}

fn parse_xinput360_report(data: &[u8]) -> Option<XInput360State> {
    if data.len() < 14 {
        return None;
    }

    let buttons = data[2];
    let buttons_hi = data[3];

    Some(XInput360State {
        hat_x: pressed(buttons, 0x08) - pressed(buttons, 0x04),
        hat_y: pressed(buttons, 0x02) - pressed(buttons, 0x01),
        start: bit(buttons, 0x10),
        back: bit(buttons, 0x20),
        thumb_l: bit(buttons, 0x40),
        thumb_r: bit(buttons, 0x80),
        lb: bit(buttons_hi, 0x01),
        rb: bit(buttons_hi, 0x02),
        guide: bit(buttons_hi, 0x04),
        a: bit(buttons_hi, 0x10),
        b: bit(buttons_hi, 0x20),
        x: bit(buttons_hi, 0x40),
        y: bit(buttons_hi, 0x80),
        lt: data[4] as i32,
        rt: data[5] as i32,
        lx: read_i16(data, 6) as i32,
        ly: (!read_i16(data, 8)) as i32,
        rx: read_i16(data, 10) as i32,
        ry: (!read_i16(data, 12)) as i32,
    })
}

fn open_uinput() -> io::Result<File> {
    OpenOptions::new()
        .read(true)
        .write(true)
        .open("/dev/uinput")
        .or_else(|_| OpenOptions::new().read(true).write(true).open("/dev/input/uinput"))
}

fn bit(byte: u8, mask: u8) -> bool {
    byte & mask != 0
}

fn pressed(byte: u8, mask: u8) -> i32 {
    if bit(byte, mask) { 1 } else { 0 }
}

fn read_i16(data: &[u8], offset: usize) -> i16 {
    i16::from_le_bytes([data[offset], data[offset + 1]])
}

fn ioctl_int(fd: i32, request: libc::c_ulong, value: i32) -> io::Result<()> {
    let result = unsafe { libc::ioctl(fd, request, value) };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn ioctl_none(fd: i32, request: libc::c_ulong) -> io::Result<()> {
    let result = unsafe { libc::ioctl(fd, request) };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn write_padded_string(buffer: &mut [u8], value: &str) {
    buffer.fill(0);
    let bytes = value.as_bytes();
    let len = bytes.len().min(buffer.len().saturating_sub(1));
    buffer[..len].copy_from_slice(&bytes[..len]);
}

fn as_bytes<T>(value: &T) -> &[u8] {
    unsafe { std::slice::from_raw_parts((value as *const T).cast::<u8>(), size_of::<T>()) }
}
