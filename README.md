# USBoss

`USBoss` is a USB controller bridge between Android and Linux.

It lets an Android device act as the USB host, forwards supported controller traffic over the local network, and recreates the controller on Linux as a local input device.

- Android host app
- Linux client
- HID support through `/dev/uhid`
- Xbox 360 style XInput support through `/dev/uinput`
- Local-network operation with automatic reconnect support

`USBoss` is well suited to setups like an NVIDIA Shield or Android phone/tablet hosting a USB controller dongle and forwarding it to a Linux gaming or streaming machine.

Tested working with:

- NVIDIA Shield / Android TV as host
- Linux as client
- 8BitDo Ultimate 2C Wireless Controller via 2.4G dongle in XInput mode

## How It Works

1. The Android app enumerates supported USB controller interfaces and opens them through the Android USB host APIs.
2. The Linux client connects over TCP and attaches to one or more advertised controllers.
3. Linux sees a local virtual controller created through `uinput` or `uhid`.

## Current Status

- Supports Android host to Linux client controller forwarding
- Prefers Xbox 360 style XInput when available
- Also supports USB HID controllers
- Supports long-running attach mode and `attach-all` for multi-controller setups
- Rumble is not implemented yet on the Linux XInput path

## Build

Docker is the recommended build path.

```bash
chmod +x docker/run-build.sh
./docker/run-build.sh
```

Build outputs:

- `build-artifacts/android/app-debug.apk`
- `build-artifacts/linux/usboss-client`

Native builds are also supported:

```bash
cd linux-client
cargo build --release

cd ../android-host
gradle :app:assembleDebug
```

More detail: `docs/BUILD_WITH_DOCKER.md`

## Quick Start

### 1. Install the Android app

```bash
adb connect SHIELD_IP:5555
adb install -r build-artifacts/android/app-debug.apk
```

Open `USBoss` on the Android device, grant USB permission, and start the host.

### 2. Start the Linux client

```bash
sudo build-artifacts/linux/usboss-client attach-all --host SHIELD_IP:35355
```

Useful commands:

```bash
sudo build-artifacts/linux/usboss-client discover
sudo build-artifacts/linux/usboss-client list --host SHIELD_IP:35355
sudo build-artifacts/linux/usboss-client attach --host SHIELD_IP:35355 --device-id 1
sudo build-artifacts/linux/usboss-client attach-all --host SHIELD_IP:35355 --verbose
```

## Runtime Notes

- `attach-all` is the recommended mode for normal use
- `attach` is useful when you want to pin a specific controller manually
- XInput devices use `/dev/uinput`
- HID devices use `/dev/uhid`
- If needed, install the udev rule from `docs/99-usboss-uhid.rules` to avoid running the Linux client as `root`

If you are using Moonlight and Sunshine, avoid double input by disabling duplicate gamepad forwarding on one side of the session.

## Debugging

Enable `Verbose` in the Android app and run the Linux client with `--verbose`.

Linux:

```bash
sudo build-artifacts/linux/usboss-client attach-all --host SHIELD_IP:35355 --verbose
```

Android:

```bash
adb logcat -s USBoss
```

## Limitations

- Generic USB forwarding is out of scope
- Xbox One / GIP devices are not implemented
- The Linux XInput backend is input-only today
- Rumble is not implemented yet

## License

`USBoss` is licensed under the MIT License.

## Acknowledgments

`USBoss` was inspired by remote USB and device-forwarding tools such as VirtualHere. It is an independent project and is not affiliated with, endorsed by, or distributed by VirtualHere or its authors.
