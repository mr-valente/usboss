# USBoss Protocol

`USBoss` uses a small binary protocol over TCP.

## Discovery

- Client sends UDP broadcast payload `USBOSS_DISCOVER_V1` to port `35354`.
- Host replies with UTF-8 text:

```text
USBOSS|1|35355|USBoss on SHIELD Android TV
```

## Framing

Each TCP frame is:

- `u32` little-endian message type
- `u32` little-endian payload length
- payload bytes

Strings and byte arrays inside the payload are:

- `u16` little-endian length
- raw bytes

## Message types

- `1`: `HELLO`
- `2`: `HELLO_ACK`
- `10`: `LIST_DEVICES`
- `11`: `DEVICES`
- `12`: `OPEN_DEVICE`
- `13`: `OPEN_DEVICE_ACK`
- `14`: `ERROR`
- `20`: `INPUT_REPORT`
- `21`: `OUTPUT_REPORT`
- `22`: `GET_REPORT_REQUEST`
- `23`: `GET_REPORT_RESPONSE`
- `24`: `SET_REPORT_REQUEST`
- `25`: `SET_REPORT_RESPONSE`
- `26`: `PING`
- `27`: `PONG`

## Flow

1. Linux client connects to TCP `35355`.
2. Linux sends `HELLO`.
3. Android replies with `HELLO_ACK`.
4. Linux sends `LIST_DEVICES`.
5. Android replies with `DEVICES`.
6. Linux may repeat `LIST_DEVICES` while it waits for a controller to appear.
7. Linux sends `OPEN_DEVICE`.
8. Android replies with `OPEN_DEVICE_ACK` or `ERROR`.
9. Android streams interrupt-IN data as `INPUT_REPORT`.
10. Linux forwards UHID output/get/set-report traffic back to Android.

`ERROR` during `OPEN_DEVICE` is a normal runtime outcome when a controller disappears between enumeration and open. The Linux client is expected to keep waiting or reconnect.

## Report type values

- `1`: input
- `2`: output
- `3`: feature
