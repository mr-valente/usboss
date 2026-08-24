use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::mem::{size_of, zeroed};
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

use libc::{c_int, timeval};

use crate::logging::debug;
use crate::protocol::OpenDeviceAck;

const UINPUT_MAX_NAME_SIZE: usize = 80;
const ABS_CNT: usize = 64;

const EV_SYN: u16 = 0x00;
const EV_KEY: u16 = 0x01;
const EV_ABS: u16 = 0x03;
const EV_FF: u16 = 0x15;
const EV_UINPUT: u16 = 0x0101;

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

const FF_RUMBLE: u16 = 0x50;
const FF_GAIN: u16 = 0x60;
const FF_AUTOCENTER: u16 = 0x61;

const UI_FF_UPLOAD: u16 = 1;
const UI_FF_ERASE: u16 = 2;
const XINPUT_FF_EFFECTS_MAX: u32 = 16;
const XINPUT_RUMBLE_PACKET_LEN: usize = 8;

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
const IOC_READ: u32 = 2;

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

const fn iowr<T>(ty: u32, nr: u32) -> libc::c_ulong {
    ioc(IOC_READ | IOC_WRITE, ty, nr, size_of::<T>() as u32)
}

const fn iow<T>(ty: u32, nr: u32) -> libc::c_ulong {
    ioc(IOC_WRITE, ty, nr, size_of::<T>() as u32)
}

const UI_DEV_CREATE: libc::c_ulong = io(UINPUT_IOCTL_BASE, 1);
const UI_DEV_DESTROY: libc::c_ulong = io(UINPUT_IOCTL_BASE, 2);
const UI_SET_EVBIT: libc::c_ulong = iow_int(UINPUT_IOCTL_BASE, 100);
const UI_SET_KEYBIT: libc::c_ulong = iow_int(UINPUT_IOCTL_BASE, 101);
const UI_SET_ABSBIT: libc::c_ulong = iow_int(UINPUT_IOCTL_BASE, 103);
const UI_SET_FFBIT: libc::c_ulong = iow_int(UINPUT_IOCTL_BASE, 107);
const UI_BEGIN_FF_UPLOAD: libc::c_ulong = iowr::<UinputFfUpload>(UINPUT_IOCTL_BASE, 200);
const UI_END_FF_UPLOAD: libc::c_ulong = iow::<UinputFfUpload>(UINPUT_IOCTL_BASE, 201);
const UI_BEGIN_FF_ERASE: libc::c_ulong = iowr::<UinputFfErase>(UINPUT_IOCTL_BASE, 202);
const UI_END_FF_ERASE: libc::c_ulong = iow::<UinputFfErase>(UINPUT_IOCTL_BASE, 203);

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

#[repr(C)]
#[derive(Clone, Copy)]
struct FfReplay {
    length: u16,
    delay: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct FfTrigger {
    button: u16,
    interval: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct FfEnvelope {
    attack_length: u16,
    attack_level: u16,
    fade_length: u16,
    fade_level: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct FfConstantEffect {
    level: i16,
    envelope: FfEnvelope,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct FfRampEffect {
    start_level: i16,
    end_level: i16,
    envelope: FfEnvelope,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct FfConditionEffect {
    right_saturation: u16,
    left_saturation: u16,
    right_coeff: i16,
    left_coeff: i16,
    deadband: u16,
    center: i16,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct FfPeriodicEffect {
    waveform: u16,
    period: u16,
    magnitude: i16,
    offset: i16,
    phase: u16,
    envelope: FfEnvelope,
    custom_len: u32,
    custom_data: *mut i16,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct FfRumbleEffect {
    strong_magnitude: u16,
    weak_magnitude: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
union FfEffectData {
    constant: FfConstantEffect,
    ramp: FfRampEffect,
    periodic: FfPeriodicEffect,
    condition: [FfConditionEffect; 2],
    rumble: FfRumbleEffect,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct FfEffect {
    type_: u16,
    id: i16,
    direction: u16,
    trigger: FfTrigger,
    replay: FfReplay,
    u: FfEffectData,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct UinputFfUpload {
    request_id: u32,
    retval: i32,
    effect: FfEffect,
    old: FfEffect,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct UinputFfErase {
    request_id: u32,
    retval: i32,
    effect_id: u32,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct RumbleCommand {
    pub strong_magnitude: u16,
    pub weak_magnitude: u16,
}

impl RumbleCommand {
    pub fn to_xinput_packet(self) -> [u8; XINPUT_RUMBLE_PACKET_LEN] {
        [
            0x00,
            0x08,
            0x00,
            (self.strong_magnitude / 256) as u8,
            (self.weak_magnitude / 256) as u8,
            0x00,
            0x00,
            0x00,
        ]
    }
}

pub struct XInput360Device {
    file: File,
    input_reports_seen: usize,
    unknown_reports_seen: usize,
}

impl XInput360Device {
    pub fn create(spec: &OpenDeviceAck) -> io::Result<Self> {
        let mut file = open_uinput()?;
        let fd = file.as_raw_fd();
        debug(&format!(
            "Opened uinput for {} transport={} input={} output={}",
            spec.name,
            spec.transport.label(),
            spec.input_packet_size,
            spec.output_packet_size
        ));

        ioctl_int(fd, UI_SET_EVBIT, EV_KEY as i32)?;
        ioctl_int(fd, UI_SET_EVBIT, EV_ABS as i32)?;
        ioctl_int(fd, UI_SET_EVBIT, EV_FF as i32)?;
        ioctl_int(fd, UI_SET_FFBIT, FF_RUMBLE as i32)?;

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
        dev.ff_effects_max = XINPUT_FF_EFFECTS_MAX;

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

        Ok(Self {
            file,
            input_reports_seen: 0,
            unknown_reports_seen: 0,
        })
    }

    pub fn spawn_rumble_loop<F>(
        &self,
        stop_flag: Arc<AtomicBool>,
        on_rumble: F,
    ) -> io::Result<thread::JoinHandle<io::Result<()>>>
    where
        F: FnMut(RumbleCommand) -> io::Result<()> + Send + 'static,
    {
        let file = self.file.try_clone()?;
        Ok(thread::spawn(move || run_rumble_loop(file, stop_flag, on_rumble)))
    }

    pub fn send_input_report(&mut self, data: &[u8]) -> io::Result<()> {
        self.input_reports_seen += 1;
        let parsed = match parse_xinput360_report(data) {
            Some(parsed) => parsed,
            None => {
                self.unknown_reports_seen += 1;
                if should_log_report(self.unknown_reports_seen) {
                    debug(&format!(
                        "Ignoring unrecognized XInput report #{} ({} bytes): {}",
                        self.input_reports_seen,
                        data.len(),
                        hex_snippet(data, 24)
                    ));
                }
                return Ok(());
            }
        };
        if should_log_report(self.input_reports_seen) {
            debug(&format!(
                "Accepted XInput report #{} as {} ({} bytes, payload offset {}): {}",
                self.input_reports_seen,
                parsed.layout,
                data.len(),
                parsed.payload_offset,
                hex_snippet(data, 24)
            ));
        }
        let state = parsed.state;

        // uinput accepts an array of input_events in a single write, and the kernel
        // only publishes the frame once it reaches SYN_REPORT. Emitting these one at
        // a time cost 20 syscalls per controller report for no benefit.
        let events = [
            input_event(EV_KEY, BTN_START, i32::from(state.start)),
            input_event(EV_KEY, BTN_SELECT, i32::from(state.back)),
            input_event(EV_KEY, BTN_MODE, i32::from(state.guide)),
            input_event(EV_KEY, BTN_THUMBL, i32::from(state.thumb_l)),
            input_event(EV_KEY, BTN_THUMBR, i32::from(state.thumb_r)),
            input_event(EV_KEY, BTN_TL, i32::from(state.lb)),
            input_event(EV_KEY, BTN_TR, i32::from(state.rb)),
            input_event(EV_KEY, BTN_A, i32::from(state.a)),
            input_event(EV_KEY, BTN_B, i32::from(state.b)),
            input_event(EV_KEY, BTN_X, i32::from(state.x)),
            input_event(EV_KEY, BTN_Y, i32::from(state.y)),
            input_event(EV_ABS, ABS_HAT0X as u16, state.hat_x),
            input_event(EV_ABS, ABS_HAT0Y as u16, state.hat_y),
            input_event(EV_ABS, ABS_Z as u16, state.lt),
            input_event(EV_ABS, ABS_RZ as u16, state.rt),
            input_event(EV_ABS, ABS_X as u16, state.lx),
            input_event(EV_ABS, ABS_Y as u16, state.ly),
            input_event(EV_ABS, ABS_RX as u16, state.rx),
            input_event(EV_ABS, ABS_RY as u16, state.ry),
            input_event(EV_SYN, SYN_REPORT, 0),
        ];
        self.file.write_all(as_bytes_slice(&events))
    }

    pub fn destroy(&self) -> io::Result<()> {
        debug("Destroying virtual XInput device");
        ioctl_none(self.file.as_raw_fd(), UI_DEV_DESTROY)
    }
}

fn input_event(event_type: u16, code: u16, value: i32) -> InputEvent {
    InputEvent {
        time: timeval { tv_sec: 0, tv_usec: 0 },
        type_: event_type,
        code,
        value,
    }
}

impl Drop for XInput360Device {
    fn drop(&mut self) {
        let _ = self.destroy();
    }
}

fn run_rumble_loop<F>(
    mut file: File,
    stop_flag: Arc<AtomicBool>,
    mut on_rumble: F,
) -> io::Result<()>
where
    F: FnMut(RumbleCommand) -> io::Result<()>,
{
    let fd = file.as_raw_fd();
    let mut effects = HashMap::<i16, FfRumbleEffect>::new();
    let mut last_command = RumbleCommand::default();

    loop {
        if stop_flag.load(Ordering::Relaxed) {
            break;
        }

        let mut poll_fd = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let poll_result = unsafe { libc::poll(&mut poll_fd, 1, 250) };
        if poll_result < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if poll_result == 0 {
            continue;
        }
        if (poll_fd.revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL)) != 0 {
            break;
        }
        if (poll_fd.revents & libc::POLLIN) == 0 {
            continue;
        }

        let event = read_struct::<InputEvent>(&mut file)?;
        match event.type_ {
            EV_UINPUT => match event.code {
                UI_FF_UPLOAD => {
                    let _ = handle_ff_upload(fd, event.value as u32, &mut effects)?;
                }
                UI_FF_ERASE => {
                    let erased_effect = handle_ff_erase(fd, event.value as u32, &mut effects)?;
                    if erased_effect.is_some() && last_command != RumbleCommand::default() {
                        debug("Erased rumble effect; sending stop command");
                        on_rumble(RumbleCommand::default())?;
                        last_command = RumbleCommand::default();
                    }
                }
                _ => {}
            },
            EV_FF => {
                if event.code == FF_GAIN || event.code == FF_AUTOCENTER {
                    continue;
                }

                let effect_id = event.code as i16;
                let command = if event.value == 0 {
                    RumbleCommand::default()
                } else {
                    effects
                        .get(&effect_id)
                        .copied()
                        .map(|effect| RumbleCommand {
                            strong_magnitude: effect.strong_magnitude,
                            weak_magnitude: effect.weak_magnitude,
                        })
                        .unwrap_or_default()
                };
                if command != last_command {
                    debug(&format!(
                        "Forwarding rumble effect {} value={} -> strong={} weak={}",
                        effect_id,
                        event.value,
                        command.strong_magnitude,
                        command.weak_magnitude
                    ));
                    on_rumble(command)?;
                    last_command = command;
                }
            }
            _ => {}
        }
    }

    if last_command != RumbleCommand::default() {
        let _ = on_rumble(RumbleCommand::default());
    }
    Ok(())
}

fn handle_ff_upload(
    fd: i32,
    request_id: u32,
    effects: &mut HashMap<i16, FfRumbleEffect>,
) -> io::Result<Option<i16>> {
    let mut upload: UinputFfUpload = unsafe { zeroed() };
    upload.request_id = request_id;
    ioctl_struct(fd, UI_BEGIN_FF_UPLOAD, &mut upload)?;

    let effect_id = upload.effect.id;
    if upload.effect.type_ != FF_RUMBLE || effect_id < 0 {
        upload.retval = -(libc::EINVAL as i32);
    } else {
        let rumble = unsafe { upload.effect.u.rumble };
        debug(&format!(
            "Registered rumble effect {} -> strong={} weak={}",
            effect_id, rumble.strong_magnitude, rumble.weak_magnitude
        ));
        effects.insert(effect_id, rumble);
        upload.retval = 0;
    }

    ioctl_struct(fd, UI_END_FF_UPLOAD, &mut upload)?;
    Ok((upload.retval == 0).then_some(effect_id))
}

fn handle_ff_erase(
    fd: i32,
    request_id: u32,
    effects: &mut HashMap<i16, FfRumbleEffect>,
) -> io::Result<Option<i16>> {
    let mut erase: UinputFfErase = unsafe { zeroed() };
    erase.request_id = request_id;
    ioctl_struct(fd, UI_BEGIN_FF_ERASE, &mut erase)?;
    let effect_id = erase.effect_id as i16;
    effects.remove(&effect_id);
    erase.retval = 0;
    ioctl_struct(fd, UI_END_FF_ERASE, &mut erase)?;
    debug(&format!("Erased rumble effect {}", effect_id));
    Ok(Some(effect_id))
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

struct ParsedXInput360Report {
    layout: &'static str,
    payload_offset: usize,
    state: XInput360State,
}

fn parse_xinput360_report(data: &[u8]) -> Option<ParsedXInput360Report> {
    if let Some(state) = parse_xinput360_payload(data, 0) {
        return Some(ParsedXInput360Report {
            layout: "wired",
            payload_offset: 0,
            state,
        });
    }

    // Upstream xpad treats Xbox 360 wireless class packets as having a 4-byte
    // wrapper, with the actual controller state starting at byte 4.
    if data.len() >= 18 && (data[1] & 0x01) != 0 {
        if let Some(state) = parse_xinput360_payload(data, 4) {
            return Some(ParsedXInput360Report {
                layout: "wireless-wrapped",
                payload_offset: 4,
                state,
            });
        }
    }

    None
}

fn parse_xinput360_payload(data: &[u8], offset: usize) -> Option<XInput360State> {
    let payload = data.get(offset..)?;
    if payload.len() < 14 || payload[0] != 0x00 {
        return None;
    }

    let buttons = payload[2];
    let buttons_hi = payload[3];

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
        lt: payload[4] as i32,
        rt: payload[5] as i32,
        lx: read_i16(payload, 6) as i32,
        ly: invert_axis(read_i16(payload, 8)),
        rx: read_i16(payload, 10) as i32,
        ry: invert_axis(read_i16(payload, 12)),
    })
}

fn open_uinput() -> io::Result<File> {
    match OpenOptions::new()
        .read(true)
        .write(true)
        .open("/dev/uinput")
    {
        Ok(file) => {
            debug("Using /dev/uinput");
            Ok(file)
        }
        Err(primary_error) if primary_error.kind() != io::ErrorKind::NotFound => Err(primary_error),
        Err(_) => {
            let file = OpenOptions::new()
                .read(true)
                .write(true)
                .open("/dev/input/uinput")?;
            debug("Using /dev/input/uinput");
            Ok(file)
        }
    }
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

fn invert_axis(value: i16) -> i32 {
    if value == i16::MIN {
        i16::MAX as i32
    } else {
        -(value as i32)
    }
}

fn should_log_report(report_count: usize) -> bool {
    report_count <= 12 || report_count % 100 == 0
}

fn hex_snippet(data: &[u8], limit: usize) -> String {
    let mut snippet = data
        .iter()
        .take(limit)
        .map(|byte| format!("{byte:02x}"))
        .collect::<Vec<_>>()
        .join(" ");
    if data.len() > limit {
        snippet.push_str(" ...");
    }
    snippet
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

fn ioctl_struct<T>(fd: i32, request: libc::c_ulong, value: &mut T) -> io::Result<()> {
    let result = unsafe { libc::ioctl(fd, request, value) };
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

fn as_bytes_slice<T>(values: &[T]) -> &[u8] {
    unsafe {
        std::slice::from_raw_parts(values.as_ptr().cast::<u8>(), size_of::<T>() * values.len())
    }
}

fn as_bytes_mut<T>(value: &mut T) -> &mut [u8] {
    unsafe { std::slice::from_raw_parts_mut((value as *mut T).cast::<u8>(), size_of::<T>()) }
}

fn read_struct<T>(file: &mut File) -> io::Result<T> {
    let mut value: T = unsafe { zeroed() };
    use std::io::Read;
    file.read_exact(as_bytes_mut(&mut value))?;
    Ok(value)
}
