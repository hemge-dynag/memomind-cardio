# MemoMind Cardio (Android, standalone)

A standalone Android companion app that reads live heart rate from a Bluetooth
LE chest strap (e.g. **Polar H10**, H9, Garmin HRM, Wahoo TICKR…) and mirrors it
onto the **MemoMind smart glasses** HUD over Bluetooth LE.

This app exists because the MemoMind **plugin** ecosystem (Web `.mmpkg` and
glasses `.gmp` plugins) has **no Bluetooth / health-sensor access**. Reading a
heart-rate sensor requires native Bluetooth, so the only route is a native
Android app running outside the plugin model — which is what this is.

> Status: **source-only**. The project is a complete, buildable Android Studio
> project. It has not been compiled into an APK in the authoring environment
> (no Android SDK available there); build it locally with Android Studio or the
> Gradle wrapper.

---

## What it does

1. **Heart-rate sensor (BLE central → sensor)**
   Scans for the standard Bluetooth SIG **Heart Rate service** (`0x180D`),
   subscribes to **Heart Rate Measurement** (`0x2A37`) notifications, and
   parses beats-per-minute from the measurement flags (uint8/uint16 formats).
   Works with any compliant strap — no vendor SDK required.

2. **MemoMind glasses (BLE client → glasses)**
   Connects to the glasses GM command service, subscribes to the response
   characteristic (`0x2022`), and writes GM frames to the command
   characteristic (`0x2021`). It uses the public **Web Bridge** display channel
   (service `0x0F`, command `0x28`, text channel `2`) to draw the current BPM.

3. **Live push**
   Once the glasses are ready and a BPM is available, every new measurement is
   pushed to the HUD automatically. A button also pushes the latest value on
   demand.

---

## Requirements

- Android 8.0+ (API 26+). Runtime Bluetooth permissions are used on Android 12+.
- A BLE heart-rate sensor.
- MemoMind glasses, with the **Web Bridge glasses plugin running** so the text
  channel renders. (Firmware requirement: arbitrary text display is handled by
  the running Web Bridge plugin, not by a global firmware command.)

## Build

**Android Studio (recommended)**

1. Open the project folder in Android Studio (Koala / 2024.1+).
2. Let it sync (it downloads Gradle 8.7 and the Android Gradle Plugin).
3. Build → Make Project, then Run on a physical device.

**Command line**

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires a JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). Point
`local.properties` at your SDK, e.g. `sdk.dir=/path/to/Android/Sdk`.

**Minimum BLE test** (no glasses): pair a strap, tap *Connect heart sensor*, and
confirm the BPM updates.

---

## Permissions

Declared in `AndroidManifest.xml`:

- `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT` — Android 12+.
- `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` — legacy (API ≤ 30).

The app requests the correct set at runtime for the device's API level.

---

## Protocol notes

The glasses side follows the public MemoMind protocols (no vendor SDK):

- **GM packet framing** — `GlassSDK/docs/PROTOCOL.md`. A logical packet starts
  with `0xFA`, a 24-bit big-endian logical length, an event ID, a service ID, a
  command ID, the TLV body, and a 2-byte additive checksum (mod 65536).
- **Plugin application service `0x0F`** — downlink command `0x28` carries
  `INT16 channel` + `BYTES data`; uplink events use command `0x29`.
- **Web Bridge text channel `2`** — `GlassSDK/docs/HUD_PROTOCOL.md`. Payload:
  `id:u8 x:u16 y:u16 w:u16 h:u16 border:u8 radius:u8 utf8_text`.

The encoder in `GmFrame.kt` is verified byte-for-byte against the SDK wire
examples (`Web Bridge clear` and `Web Bridge text Hi`).

### BLE pacing

Each characteristic write carries **one complete physical GM frame**, sized
within the negotiated ATT MTU (`MTU − 3`). Logical payloads larger than that are
fragmented into ordered physical frames (`0xFA`, `0x01`, `0x02`, …), each with
its own checksum, paced by write-completion callbacks.

---

## Layout

```
app/src/main/
  AndroidManifest.xml
  java/com/dynag/cardio/
    GmFrame.kt            # GM framing + Web Bridge payload encoding
    HeartRateManager.kt   # BLE scan/connect/subscribe + 0x2A37 parsing
    GlassesHudClient.kt   # BLE to glasses; GM frame writes + MTU pacing
    MainActivity.kt       # UI, permissions, orchestration
  res/
    layout/activity_main.xml
    values/{strings,themes}.xml
```

## License

Provided as-is for the MemoMind ecosystem. The MemoMind protocols are documented
in the public MemoMind Plugin Open Platform SDK.
