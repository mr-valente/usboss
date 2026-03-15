mod protocol;
mod uhid;

use std::env;
use std::error::Error;
use std::io;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use protocol::{
    read_message, write_message, DeviceSummary, Message, OpenDeviceAck, DEFAULT_DISCOVERY_PORT,
    DEFAULT_TCP_PORT, DISCOVERY_REQUEST, DISCOVERY_RESPONSE_PREFIX,
};
use uhid::UhidDevice;

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
        Command::Attach { host, device_id } => {
            let target = resolve_target(host)?;
            let mut stream = connect_target(target)?;
            hello(&mut stream)?;
            let devices = list_devices(&mut stream)?;
            if devices.is_empty() {
                return Err("no compatible USB HID interfaces reported by the Android host".into());
            }
            print_devices(&devices);

            let selected = match device_id {
                Some(id) => devices
                    .iter()
                    .find(|device| device.device_id == id)
                    .cloned()
                    .ok_or_else(|| format!("device id {id} was not advertised by the host"))?,
                None => devices[0].clone(),
            };
            println!("Attaching to {selected}");

            let spec = open_device(&mut stream, selected.device_id)?;
            attach_loop(stream, spec)?;
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

fn attach_loop(stream: TcpStream, spec: OpenDeviceAck) -> Result<(), Box<dyn Error>> {
    let device = Arc::new(UhidDevice::create(&spec)?);
    let writer = Arc::new(Mutex::new(stream.try_clone()?));
    let writer_for_uhid = Arc::clone(&writer);
    let device_for_thread = Arc::clone(&device);

    thread::spawn(move || {
        if let Err(error) = device_for_thread.run_event_loop(|message| {
            let mut stream = lock_writer(&writer_for_uhid)?;
            write_message(&mut *stream, &message)
        }) {
            eprintln!("USBoss: UHID loop ended: {error}");
        }
    });

    let mut reader_stream = stream;
    reader_stream.set_nodelay(true)?;

    loop {
        match read_message(&mut reader_stream) {
            Ok(Message::InputReport { data }) => {
                device.send_input(&data)?;
            }
            Ok(Message::GetReportResponse {
                request_id,
                status,
                data,
            }) => {
                device.reply_get_report(request_id, status, &data)?;
            }
            Ok(Message::SetReportResponse { request_id, status }) => {
                device.reply_set_report(request_id, status)?;
            }
            Ok(Message::Ping) => {
                let mut stream = lock_writer(&writer)?;
                write_message(&mut *stream, &Message::Pong)?;
            }
            Ok(Message::Pong) => {}
            Ok(Message::Error { message }) => {
                return Err(message.into());
            }
            Ok(other) => {
                eprintln!("USBoss: ignoring unexpected message during attach: {other:?}");
            }
            Err(error) => {
                let _ = device.destroy();
                return Err(Box::new(error));
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

    let mut servers = Vec::new();
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

#[derive(Debug)]
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
    Attach { host: Option<String>, device_id: Option<u32> },
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

fn print_help() {
    println!(
        "\
USBoss Linux client

Commands:
  usboss-client discover [--timeout-ms 900]
  usboss-client list [--host 192.168.1.20]
  usboss-client attach [--host 192.168.1.20] [--device-id 1]

If --host is omitted, attach/list will try UDP broadcast discovery on the local subnet.
"
    );
}
