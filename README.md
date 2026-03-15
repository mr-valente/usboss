# USBoss

`USBoss` is a clean-room USB host bridge for one narrow job:

- Android device acts as the USB host.
- A Linux machine recreates the controller through:
  - `/dev/uhid` for HID devices
  - `/dev/uinput` for Xbox 360 style XInput devices
- The transport is local-LAN only and optimized for low-latency controller traffic.

This repo is intentionally not a generic VirtualHere clone. It targets USB HID game controllers and Xbox 360 style XInput controllers that Android can open through the public USB host APIs.

## Important caveat for your 8BitDo setup

Many 8BitDo dongles can expose different USB modes. `USBoss` now prefers Xbox 360 style XInput interfaces when it sees them and still supports HID interfaces.

- If the dongle shows up as `XInput 360` in `USBoss`, that is the intended mode for your Shield use case.
- If it shows up as `HID`, USBoss can still forward it, but that is less valuable on a Shield that already handles D-input well.
- If it does not appear, the dongle is likely using a vendor-specific mode that is not Xbox 360 style XInput.

Current XInput scope:

- best-effort Xbox 360 style XInput support
- not a high-confidence implementation for Xbox One / GIP devices
- no rumble on the Linux virtual XInput path yet

## Repository layout

- [android-host](/Users/nick.valente/.dev/usboss/android-host): Android APK project for the Shield host.
- [linux-client](/Users/nick.valente/.dev/usboss/linux-client): Rust client that creates a virtual HID or XInput-style device on Linux.
- [docs/PROTOCOL.md](/Users/nick.valente/.dev/usboss/docs/PROTOCOL.md): Wire format notes.
- [docs/BUILD_WITH_DOCKER.md](/Users/nick.valente/.dev/usboss/docs/BUILD_WITH_DOCKER.md): Containerized build instructions.
- [docs/99-usboss-uhid.rules](/Users/nick.valente/.dev/usboss/docs/99-usboss-uhid.rules): Optional udev rule for `/dev/uhid` and `/dev/uinput`.
- [docs/usboss-client.service](/Users/nick.valente/.dev/usboss/docs/usboss-client.service): Optional systemd service template.

## Prerequisites

### Linux client

- Linux kernel with `uhid` and `uinput` enabled.
- Rust stable.
- Permission to open `/dev/uhid` and `/dev/uinput`.

Quick install example on Debian/Ubuntu:

```bash
sudo apt update
sudo apt install -y build-essential pkg-config curl
curl https://sh.rustup.rs -sSf | sh
source "$HOME/.cargo/env"
```

### Android build host

You said you will build in Linux, so use either Android Studio or a system Gradle install plus the Android SDK.

- JDK 17
- Gradle 8.7+ or Android Studio Iguana/Koala or newer
- Android SDK Platform 35
- Android Build-Tools 35.x
- Platform tools (`adb`)

Example setup with the command-line SDK tools:

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
yes | sdkmanager --licenses
sdkmanager \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0"
```

Note:

- This repo does not include a Gradle wrapper because I could not generate it in the current environment.
- If you want one, run `gradle wrapper` inside [android-host](/Users/nick.valente/.dev/usboss/android-host) after installing Gradle.

## Build

### 1. Build the Linux client

```bash
cd linux-client
cargo build --release
```

Binary output:

- `linux-client/target/release/usboss-client`

### 2. Build the Android APK

```bash
cd android-host
gradle :app:assembleDebug
```

APK output:

- `android-host/app/build/outputs/apk/debug/app-debug.apk`

## Sideload onto Nvidia Shield Pro

Because the Shield’s USB port may already be occupied by the 8BitDo dongle, network ADB is usually the easiest path.

1. On the Shield, enable Developer Options and ADB/network debugging.
2. Connect your Linux workstation to the Shield over ADB:

```bash
adb connect SHIELD_IP:5555
```

3. Install the APK:

```bash
adb install -r android-host/app/build/outputs/apk/debug/app-debug.apk
```

4. Launch `USBoss` on the Shield.
5. Plug in the 8BitDo USB dongle if it is not already connected.
6. Use the `Grant USB` button once and accept the Android USB permission dialog.

## Run

### Start the Android host

Open the app on the Shield and leave it running. The foreground service will:

- listen on TCP `35355`
- answer UDP discovery on `35354`
- show the exact `usboss-client` command to run from Linux
- stay available even if no supported controller is currently plugged in

### Start the Linux client

You can either auto-discover or point it directly at the Shield.

Auto-discover:

```bash
sudo linux-client/target/release/usboss-client discover
sudo linux-client/target/release/usboss-client attach
```

Direct host:

```bash
sudo linux-client/target/release/usboss-client attach --host SHIELD_IP:35355
```

If multiple controller interfaces are advertised, list them first:

```bash
sudo linux-client/target/release/usboss-client list --host SHIELD_IP:35355
sudo linux-client/target/release/usboss-client attach --host SHIELD_IP:35355 --device-id 1
```

`attach` is now a persistent runtime command:

- if the host is offline, it keeps retrying
- if the host is online but no controller is present yet, it keeps waiting
- if the controller is unplugged and replugged, it reconnects and reattaches automatically
- pass `--once` if you want the old single-shot behavior for debugging

You can tune the retry loop if needed:

```bash
sudo linux-client/target/release/usboss-client attach --host SHIELD_IP:35355 --retry-ms 1500 --rescan-ms 1000
```

## Everyday runtime behavior

These states are now treated as normal:

- start the Shield host first, then plug the controller in later
- start the Linux client first, then turn on the Shield host later
- unplug and replug the controller while the host is already running
- restart either side and let `attach` settle back into a healthy connection

For your use case, the intended steady state is simply to leave the Android host service running and leave `usboss-client attach` running on Linux.

## Avoid duplicate input with Moonlight/Sunshine

For your exact setup, the Linux box will now see a local virtual gamepad through `USBoss`.

That means Moonlight may also try to forward the same controller through its normal gamepad path. If you notice double inputs:

- disable Moonlight gamepad forwarding for that Shield session, or
- disable controller forwarding on the Sunshine side for that client session

The right toggle depends on your current Moonlight/Sunshine config, but the symptom is straightforward: every button press appears twice.

## Test checklist

### Basic device visibility

1. Start `USBoss` on the Shield.
2. Confirm the app shows your dongle as `XInput 360` or `HID`.
3. Run:

```bash
sudo linux-client/target/release/usboss-client attach --host SHIELD_IP:35355
```

4. In another Linux shell, verify that a new input device exists:

```bash
ls -l /dev/uhid /dev/uinput
grep -H . /sys/class/input/event*/device/name 2>/dev/null | grep USBoss
```

For XInput mode, the virtual controller is created through `/dev/uinput`.
For HID mode, the virtual controller is created through `/dev/uhid`.

### Input test

Use one of these tools:

```bash
sudo evtest
```

or

```bash
sudo libinput debug-events
```

Press buttons on the 8BitDo controller and confirm events show up on Linux.

### Hotplug test

1. Leave `usboss-client attach` running.
2. Unplug the controller or dongle from the Android host.
3. Confirm the Linux client drops back to a waiting/retrying state instead of exiting.
4. Plug the controller or dongle back in.
5. Confirm the client reconnects and the virtual controller becomes active again.

### In-game test with Sunshine

1. Keep `usboss-client` attached on the Linux machine.
2. Start Sunshine as usual.
3. Launch a game locally on Linux or through Sunshine.
4. Confirm the game sees the virtual controller.
5. If input is doubled, adjust Moonlight/Sunshine gamepad forwarding as noted above.

## Permissions for `/dev/uhid` and `/dev/uinput`

Quickest path:

```bash
sudo linux-client/target/release/usboss-client attach --host SHIELD_IP:35355
```

If you want to run without `sudo`, install the udev rule from [docs/99-usboss-uhid.rules](/Users/nick.valente/.dev/usboss/docs/99-usboss-uhid.rules).

## Known limitations

- Generic USB devices are out of scope.
- XInput support is currently aimed at Xbox 360 style packet layouts.
- Xbox One / GIP style devices are not currently implemented.
- Output reports are best-effort. Basic input is the main focus of this first cut.
- The Linux virtual XInput backend currently focuses on input. Rumble is not implemented there yet.
- I could not compile this locally because the current environment did not have Android or Rust toolchains installed.

## Suggested next improvements

- add a small pairing token instead of open LAN access
- add a persistent systemd install script for the Linux client
- add optional mDNS in addition to UDP broadcast discovery
- add rumble / force-feedback support for the virtual XInput device
- add explicit Xbox One / GIP transport support where Android USB behavior allows it
