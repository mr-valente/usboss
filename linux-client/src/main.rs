mod protocol;
mod uhid;
mod uinput;

use std::env;
use std::error::Error;
use std::fmt;
use std::io;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use protocol::{
    read_message, write_message, DeviceSummary, DeviceTransport, Message, OpenDeviceAck,
    DEFAULT_DISCOVERY_PORT, DEFAULT_TCP_PORT, DISCOVERY_REQUEST, DISCOVERY_RESPONSE_PREFIX,
};
use uhid::UhidDevice;
use uinput::XInput360Device;

fn main() {
    if let Err(error) = run() {
        eprintln!("USBoss error: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), Box<dyn Error>> {
    let args: Vec<String> = env::args().skip(1).collect();
    let command = Command::parse(&args)?;

    match command {
        Command::Discover { timeout_ms } => {
            let servers = discover_servers(Duration::from_millis(timeout_ms))?;
            if servers.is_empty() {
                println!("No USBoss hosts responded.");
            } else {
                for server in servers {
                    println!("{}:{} {}", server.host, server.port, server.name);
                }
            }
        }
        Command::List { host } => {
            let mut stream = connect_target(resolve_target(host)?)?;
            hello(&mut stream)?;
            let devices = list_devices(&mut stream)?;
            print_devices(&devices);
        }
        Command::Attach {
            host,
            device_id,
            retry_ms,
            rescan_ms,
            once,
        } => {
            run_attach(AttachConfig {
                host,
                requested_device_id: device_id,
                retry_delay: Duration::from_millis(retry_ms),
                rescan_delay: Duration::from_millis(rescan_ms),
                once,
            })?;
        }
    }

    Ok(())
}

fn hello(stream: &mut TcpStream) -> Result<(), Box<dyn Error>> {
    write_message(
        stream,
        &Message::Hello {
            client_name: "usboss-client".to_string(),
        },
    )?;
    match read_message(stream)? {
        Message::HelloAck { server_name } => {
            println!("Connected to {server_name}");
            Ok(())
        }
        Message::Error { message } => Err(message.into()),
        other => Err(format!("unexpected handshake response: {other:?}").into()),
    }
}

fn list_devices(stream: &mut TcpStream) -> Result<Vec<DeviceSummary>, Box<dyn Error>> {
    write_message(stream, &Message::ListDevices)?;
    match read_message(stream)? {
        Message::Devices { devices } => Ok(devices),
        Message::Error { message } => Err(message.into()),
        other => Err(format!("unexpected device list response: {other:?}").into()),
    }
}

fn open_device(stream: &mut TcpStream, device_id: u32) -> Result<OpenDeviceAck, Box<dyn Error>> {
    write_message(stream, &Message::OpenDevice { device_id })?;
    match read_message(stream)? {
        Message::OpenDeviceAck(spec) => Ok(spec),
        Message::Error { message } => Err(message.into()),
        other => Err(format!("unexpected open-device response: {other:?}").into()),
    }
}

fn run_attach(config: AttachConfig) -> Result<(), Box<dyn Error>> {
    let mut preferred_matcher = None;

    loop {
        let target = match resolve_target(config.host.clone()) {
            Ok(target) => target,
            Err(error) => {
                if config.once {
                    return Err(error);
                }
                announce_retry(&error.to_string(), config.retry_delay);
                continue;
            }
        };

        match attach_session(&target, &config, &mut preferred_matcher) {
            Ok(()) => return Ok(()),
            Err(AttachFailure::Retryable(message)) if config.once => return Err(message.into()),
            Err(AttachFailure::Retryable(message)) => {
                announce_retry(&message, config.retry_delay);
            }
            Err(AttachFailure::Fatal(message)) => return Err(message.into()),
        }
    }
}

fn attach_session(
    target: &Target,
    config: &AttachConfig,
    preferred_matcher: &mut Option<DeviceMatcher>,
) -> Result<(), AttachFailure> {
    let mut stream = connect_target(target.clone()).map_err(|error| {
        AttachFailure::Retryable(format!("unable to connect to {}:{}: {error}", target.host, target.port))
    })?;
    hello(&mut stream).map_err(|error| classify_control_error("handshake failed", &*error))?;

    let mut last_snapshot: Option<String> = None;
    let mut last_waiting_message = String::new();

    loop {
        let devices =
            list_devices(&mut stream).map_err(|error| classify_control_error("device listing failed", &*error))?;
        let snapshot = device_snapshot(&devices);
        if last_snapshot.as_deref() != Some(snapshot.as_str()) {
            print_devices(&devices);
            last_snapshot = Some(snapshot);
        }

        let preferred = select_device(&devices, config.requested_device_id, preferred_matcher.as_ref());
        let open_candidates: Vec<DeviceSummary> = match preferred {
            Some(device) => {
                last_waiting_message.clear();
                vec![device.clone()]
            }
            None if config.requested_device_id.is_none() && preferred_matcher.is_none() && !devices.is_empty() => {
                last_waiting_message.clear();
                devices.to_vec()
            }
            None => {
                let message = waiting_message(&devices, config.requested_device_id, preferred_matcher.as_ref());
                if config.once {
                    return Err(AttachFailure::Retryable(message));
                }
                if message != last_waiting_message {
                    eprintln!("USBoss: {message}");
                    last_waiting_message = message;
                }
                thread::sleep(config.rescan_delay);
                continue;
            }
        };

        let mut deferred_error: Option<String> = None;
        for selected in open_candidates {
            let matcher = DeviceMatcher::from_device(&selected);
            println!("Attaching to {selected}");

            match open_device(&mut stream, selected.device_id) {
                Ok(spec) => {
                    *preferred_matcher = Some(matcher);
                    return attach_loop(stream, spec);
                }
                Err(error) => {
                    let message = format!("failed to open {selected}: {error}");
                    if can_try_next_device(
                        &error.to_string(),
                        config.requested_device_id,
                        preferred_matcher.as_ref(),
                    ) {
                        eprintln!("USBoss: {message}");
                        deferred_error = Some(message);
                        continue;
                    }
                    if config.once {
                        return Err(AttachFailure::Retryable(message));
                    }
                    eprintln!("USBoss: {message}");
                    deferred_error = Some(message);
                    break;
                }
            }
        }

        if config.once {
            return Err(AttachFailure::Retryable(
                deferred_error.unwrap_or_else(|| "no controller could be opened".to_string()),
            ));
        }
        if let Some(message) = &deferred_error {
            eprintln!("USBoss: {message}");
        }
        thread::sleep(config.rescan_delay);
    }
}

fn attach_loop(stream: TcpStream, spec: OpenDeviceAck) -> Result<(), AttachFailure> {
    match spec.transport {
        DeviceTransport::Hid => attach_hid_loop(stream, spec),
        DeviceTransport::XInput360 => attach_xinput_loop(stream, spec),
    }
}

fn attach_hid_loop(stream: TcpStream, spec: OpenDeviceAck) -> Result<(), AttachFailure> {
    let device = Arc::new(
        UhidDevice::create(&spec)
            .map_err(|error| AttachFailure::Fatal(format!("failed to create UHID device: {error}")))?,
    );
    let writer = Arc::new(Mutex::new(
        stream
            .try_clone()
            .map_err(|error| AttachFailure::Retryable(format!("failed to clone TCP stream: {error}")))?,
    ));
    let writer_for_uhid = Arc::clone(&writer);
    let device_for_thread = Arc::clone(&device);

    let uhid_thread = thread::spawn(move || {
        if let Err(error) = device_for_thread.run_event_loop(|message| {
            let mut stream = lock_writer(&writer_for_uhid)?;
            write_message(&mut *stream, &message)
        }) {
            eprintln!("USBoss: UHID loop ended: {error}");
        }
    });

    let mut reader_stream = stream;
    reader_stream
        .set_nodelay(true)
        .map_err(|error| AttachFailure::Retryable(format!("failed to configure TCP stream: {error}")))?;

    let outcome = loop {
        match read_message(&mut reader_stream) {
            Ok(Message::InputReport { data }) => {
                if let Err(error) = device.send_input(&data) {
                    break AttachFailure::Fatal(format!("failed to inject HID input into UHID: {error}"));
                }
            }
            Ok(Message::GetReportResponse {
                request_id,
                status,
                data,
            }) => {
                if let Err(error) = device.reply_get_report(request_id, status, &data) {
                    break AttachFailure::Fatal(format!("failed to reply to UHID GET_REPORT: {error}"));
                }
            }
            Ok(Message::SetReportResponse { request_id, status }) => {
                if let Err(error) = device.reply_set_report(request_id, status) {
                    break AttachFailure::Fatal(format!("failed to reply to UHID SET_REPORT: {error}"));
                }
            }
            Ok(Message::Ping) => {
                let mut stream = match lock_writer(&writer) {
                    Ok(stream) => stream,
                    Err(error) => {
                        break AttachFailure::Retryable(format!(
                            "socket writer became unavailable: {error}"
                        ));
                    }
                };
                if let Err(error) = write_message(&mut *stream, &Message::Pong) {
                    break AttachFailure::Retryable(format!("failed to respond to host ping: {error}"));
                }
            }
            Ok(Message::Pong) => {}
            Ok(Message::Error { message }) => {
                break AttachFailure::Retryable(message);
            }
            Ok(other) => {
                eprintln!("USBoss: ignoring unexpected message during attach: {other:?}");
            }
            Err(error) => {
                break AttachFailure::Retryable(format!("session ended: {error}"));
            }
        }
    };

    let _ = device.destroy();
    let _ = uhid_thread.join();
    Err(outcome)
}

fn attach_xinput_loop(mut stream: TcpStream, spec: OpenDeviceAck) -> Result<(), AttachFailure> {
    let mut device = XInput360Device::create(&spec)
        .map_err(|error| AttachFailure::Fatal(format!("failed to create virtual XInput device: {error}")))?;
    stream
        .set_nodelay(true)
        .map_err(|error| AttachFailure::Retryable(format!("failed to configure TCP stream: {error}")))?;

    loop {
        match read_message(&mut stream) {
            Ok(Message::InputReport { data }) => {
                if let Err(error) = device.send_input_report(&data) {
                    return Err(AttachFailure::Fatal(format!(
                        "failed to inject XInput state into uinput: {error}"
                    )));
                }
            }
            Ok(Message::Ping) => {
                if let Err(error) = write_message(&mut stream, &Message::Pong) {
                    return Err(AttachFailure::Retryable(format!(
                        "failed to respond to host ping: {error}"
                    )));
                }
            }
            Ok(Message::Pong) => {}
            Ok(Message::Error { message }) => {
                return Err(AttachFailure::Retryable(message));
            }
            Ok(Message::GetReportResponse { .. } | Message::SetReportResponse { .. }) => {}
            Ok(other) => {
                eprintln!("USBoss: ignoring unexpected message during xinput attach: {other:?}");
            }
            Err(error) => {
                return Err(AttachFailure::Retryable(format!("session ended: {error}")));
            }
        }
    }
}

fn connect_target(target: Target) -> Result<TcpStream, Box<dyn Error>> {
    let address = format!("{}:{}", target.host, target.port);
    let mut addrs = address.to_socket_addrs()?;
    let socket_addr = addrs
        .next()
        .ok_or_else(|| format!("unable to resolve {address}"))?;
    let stream = TcpStream::connect_timeout(&socket_addr, Duration::from_secs(3))?;
    stream.set_nodelay(true)?;
    Ok(stream)
}

fn print_devices(devices: &[DeviceSummary]) {
    if devices.is_empty() {
        println!("Available devices: none");
        return;
    }

    println!("Available devices:");
    for device in devices {
        println!("  {device}");
    }
}

fn resolve_target(host: Option<String>) -> Result<Target, Box<dyn Error>> {
    match host {
        Some(host) => parse_target(&host),
        None => discover_servers(Duration::from_millis(900))?
            .into_iter()
            .next()
            .map(|server| Target {
                host: server.host,
                port: server.port,
            })
            .ok_or_else(|| "no USBoss hosts discovered; pass --host <ip-or-hostname>".into()),
    }
}

fn parse_target(raw: &str) -> Result<Target, Box<dyn Error>> {
    if let Some((host, port)) = raw.rsplit_once(':') {
        if port.chars().all(|c| c.is_ascii_digit()) {
            return Ok(Target {
                host: host.to_string(),
                port: port.parse::<u16>()?,
            });
        }
    }
    Ok(Target {
        host: raw.to_string(),
        port: DEFAULT_TCP_PORT,
    })
}

fn discover_servers(timeout: Duration) -> Result<Vec<DiscoveredServer>, Box<dyn Error>> {
    let socket = UdpSocket::bind(("0.0.0.0", 0))?;
    socket.set_broadcast(true)?;
    socket.set_read_timeout(Some(Duration::from_millis(200)))?;
    socket.send_to(
        DISCOVERY_REQUEST,
        SocketAddr::from(([255, 255, 255, 255], DEFAULT_DISCOVERY_PORT)),
    )?;

    let mut servers: Vec<DiscoveredServer> = Vec::new();
    let started = Instant::now();
    let mut buffer = [0u8; 512];
    while started.elapsed() < timeout {
        match socket.recv_from(&mut buffer) {
            Ok((count, source)) => {
                if let Some(server) = parse_discovery_response(&buffer[..count], source)? {
                    if !servers
                        .iter()
                        .any(|existing| existing.host == server.host && existing.port == server.port)
                    {
                        servers.push(server);
                    }
                }
            }
            Err(error)
                if error.kind() == io::ErrorKind::WouldBlock
                    || error.kind() == io::ErrorKind::TimedOut => {}
            Err(error) => return Err(Box::new(error)),
        }
    }
    Ok(servers)
}

fn parse_discovery_response(
    data: &[u8],
    source: SocketAddr,
) -> Result<Option<DiscoveredServer>, Box<dyn Error>> {
    let text = std::str::from_utf8(data)?.trim();
    if !text.starts_with(DISCOVERY_RESPONSE_PREFIX) {
        return Ok(None);
    }
    let mut parts = text.split('|');
    let _prefix = parts.next();
    let _version = parts.next();
    let port = parts
        .next()
        .ok_or("discovery response missing port")?
        .parse::<u16>()?;
    let name = parts.next().unwrap_or("USBoss Host").to_string();
    Ok(Some(DiscoveredServer {
        host: source.ip().to_string(),
        port,
        name,
    }))
}

fn lock_writer(
    writer: &Arc<Mutex<TcpStream>>,
) -> io::Result<std::sync::MutexGuard<'_, TcpStream>> {
    writer
        .lock()
        .map_err(|_| io::Error::new(io::ErrorKind::Other, "socket mutex poisoned"))
}

#[derive(Clone, Debug)]
struct Target {
    host: String,
    port: u16,
}

#[derive(Debug)]
struct DiscoveredServer {
    host: String,
    port: u16,
    name: String,
}

enum Command {
    Discover { timeout_ms: u64 },
    List { host: Option<String> },
    Attach {
        host: Option<String>,
        device_id: Option<u32>,
        retry_ms: u64,
        rescan_ms: u64,
        once: bool,
    },
}

impl Command {
    fn parse(args: &[String]) -> Result<Self, Box<dyn Error>> {
        let command = match args.first().map(String::as_str) {
            Some("discover" | "list" | "attach" | "-h" | "--help" | "help") => {
                args.first().map(String::as_str).unwrap()
            }
            _ => "attach",
        };
        match command {
            "discover" => Ok(Command::Discover {
                timeout_ms: parse_named_u64(args, "--timeout-ms").unwrap_or(900),
            }),
            "list" => Ok(Command::List {
                host: parse_named_string(args, "--host"),
            }),
            "attach" => Ok(Command::Attach {
                host: parse_named_string(args, "--host"),
                device_id: parse_named_u32(args, "--device-id"),
                retry_ms: parse_named_u64(args, "--retry-ms").unwrap_or(1_500),
                rescan_ms: parse_named_u64(args, "--rescan-ms").unwrap_or(1_000),
                once: parse_flag(args, "--once"),
            }),
            "-h" | "--help" | "help" => {
                print_help();
                std::process::exit(0);
            }
            other => Err(format!("unknown command {other}").into()),
        }
    }
}

fn parse_named_string(args: &[String], name: &str) -> Option<String> {
    args.windows(2).find_map(|window| {
        if window[0] == name {
            Some(window[1].clone())
        } else {
            None
        }
    })
}

fn parse_named_u32(args: &[String], name: &str) -> Option<u32> {
    parse_named_string(args, name)?.parse().ok()
}

fn parse_named_u64(args: &[String], name: &str) -> Option<u64> {
    parse_named_string(args, name)?.parse().ok()
}

fn parse_flag(args: &[String], name: &str) -> bool {
    args.iter().any(|arg| arg == name)
}

fn print_help() {
    println!(
        "\
USBoss Linux client

Commands:
  usboss-client discover [--timeout-ms 900]
  usboss-client list [--host 192.168.1.20]
  usboss-client attach [--host 192.168.1.20] [--device-id 1] [--retry-ms 1500] [--rescan-ms 1000] [--once]

If --host is omitted, attach/list will try UDP broadcast discovery on the local subnet.
Attach mode stays connected, reconnects automatically, and waits for controller devices to appear unless --once is passed.
For multiple controllers, run one attach process per device id shown by `usboss-client list`.
"
    );
}

#[derive(Clone)]
struct AttachConfig {
    host: Option<String>,
    requested_device_id: Option<u32>,
    retry_delay: Duration,
    rescan_delay: Duration,
    once: bool,
}

struct DeviceMatcher {
    transport: DeviceTransport,
    vendor_id: u16,
    product_id: u16,
    interface_number: u8,
    system_path: String,
    manufacturer: String,
    product: String,
    serial: Option<String>,
}

impl DeviceMatcher {
    fn from_device(device: &DeviceSummary) -> Self {
        Self {
            transport: device.transport,
            vendor_id: device.vendor_id,
            product_id: device.product_id,
            interface_number: device.interface_number,
            system_path: device.system_path.clone(),
            manufacturer: device.manufacturer.clone(),
            product: device.product.clone(),
            serial: if device.serial.is_empty() {
                None
            } else {
                Some(device.serial.clone())
            },
        }
    }

    fn matches_exact_path(&self, device: &DeviceSummary) -> bool {
        self.matches_base(device) && device.system_path == self.system_path
    }

    fn matches_identity(&self, device: &DeviceSummary) -> bool {
        if !self.matches_base(device) {
            return false;
        }
        if let Some(serial) = &self.serial {
            return device.serial == *serial;
        }
        device.manufacturer == self.manufacturer && device.product == self.product
    }

    fn matches_base(&self, device: &DeviceSummary) -> bool {
        device.transport == self.transport
            && device.vendor_id == self.vendor_id
            && device.product_id == self.product_id
            && device.interface_number == self.interface_number
    }
}

#[derive(Debug)]
enum AttachFailure {
    Retryable(String),
    Fatal(String),
}

impl fmt::Display for AttachFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AttachFailure::Retryable(message) | AttachFailure::Fatal(message) => write!(f, "{message}"),
        }
    }
}

impl Error for AttachFailure {}

fn classify_control_error(context: &str, error: &(dyn Error + 'static)) -> AttachFailure {
    let message = format!("{context}: {error}");
    if let Some(io_error) = error.downcast_ref::<io::Error>() {
        match io_error.kind() {
            io::ErrorKind::ConnectionAborted
            | io::ErrorKind::ConnectionRefused
            | io::ErrorKind::ConnectionReset
            | io::ErrorKind::BrokenPipe
            | io::ErrorKind::NotConnected
            | io::ErrorKind::UnexpectedEof
            | io::ErrorKind::TimedOut
            | io::ErrorKind::WouldBlock => return AttachFailure::Retryable(message),
            _ => {}
        }
    }

    if message.contains("unexpected") || message.contains("protocol version mismatch") {
        AttachFailure::Fatal(message)
    } else {
        AttachFailure::Retryable(message)
    }
}

fn select_device<'a>(
    devices: &'a [DeviceSummary],
    requested_device_id: Option<u32>,
    preferred_matcher: Option<&DeviceMatcher>,
) -> Option<&'a DeviceSummary> {
    if let Some(device_id) = requested_device_id {
        if let Some(device) = devices.iter().find(|device| device.device_id == device_id) {
            return Some(device);
        }
    }

    if let Some(matcher) = preferred_matcher {
        if let Some(device) = devices.iter().find(|device| matcher.matches_exact_path(device)) {
            return Some(device);
        }
        if let Some(device) = devices.iter().find(|device| matcher.matches_identity(device)) {
            return Some(device);
        }
    }

    if requested_device_id.is_some() {
        None
    } else {
        devices.first()
    }
}

fn waiting_message(
    devices: &[DeviceSummary],
    requested_device_id: Option<u32>,
    preferred_matcher: Option<&DeviceMatcher>,
) -> String {
    match (devices.is_empty(), requested_device_id, preferred_matcher.is_some()) {
        (true, _, _) => "host is online but no compatible USB controller interfaces are currently available".to_string(),
        (false, Some(device_id), true) => format!(
            "device id {device_id} is not currently advertised; waiting for the previously selected controller to return"
        ),
        (false, Some(device_id), false) => {
            format!("device id {device_id} is not currently advertised by the host")
        }
        (false, None, true) => "waiting for the previously selected controller to return".to_string(),
        (false, None, false) => "waiting for a compatible USB controller interface".to_string(),
    }
}

fn device_snapshot(devices: &[DeviceSummary]) -> String {
    devices
        .iter()
        .map(|device| {
            format!(
                "{}:{}:{}:{}:{}:{}:{}:{}",
                device.device_id,
                device.transport.label(),
                device.vendor_id,
                device.product_id,
                device.interface_number,
                device.manufacturer,
                device.product,
                device.system_path
            )
        })
        .collect::<Vec<_>>()
        .join("|")
}

fn announce_retry(message: &str, delay: Duration) {
    eprintln!(
        "USBoss: {message}. Retrying in {} ms.",
        delay.as_millis()
    );
    thread::sleep(delay);
}

fn can_try_next_device(
    error_message: &str,
    requested_device_id: Option<u32>,
    preferred_matcher: Option<&DeviceMatcher>,
) -> bool {
    requested_device_id.is_none()
        && preferred_matcher.is_none()
        && error_message.contains("already being forwarded by another client")
}
