# MemoMind Cardio (Android, standalone)

A standalone Android companion app that reads live heart rate from a Bluetooth
LE chest strap (e.g. **Polar H10**, H9, Garmin HRM, Wahoo TICKR…) and mirrors it
onto the **MemoMind smart glasses** HUD over Bluetooth LE.

This app exists because the MemoMind **plugin** ecosystem (Web `.mmpkg` and
glasses `.gmp` plugins) has **no Bluetooth / health-sensor access**. Reading a
heart-rate sensor requires native Bluetooth, so the only route is a native
Android app running outside the plugin model — which is what this is.

> Status: **1.2.7 — source-only**. The project is a complete, buildable Android
> Studio project. It has not been compiled into an APK in the authoring
> environment (no Android SDK available there); build it locally with Android
> Studio or the Gradle wrapper.
>
> **1.2.7 fixes** (the journal proved the glasses are bonded over **Classic**,
> so no BLE GATT link can ever reach them: `Link failed (status 147)`):
> - Devices bonded as Classic or Dual now connect over the **Classic SPP
>   command service** (`00007033-…`, then standard `00001101-…`, secure then
>   insecure) exactly like the official App. BLE GATT is kept for LE-only
>   devices and as the automatic fallback when SPP fails.
> - SPP frames use the firmware ceiling (512 bytes) and the byte stream is
>   reassembled into complete GM frames before parsing.
> - The journal prints whether the phone already records an SPP service for
>   the target.
>
> **1.2.6 fixes** (picking "MemoMind One" ended in *No MemoMind glasses
> answered* straight away):
> - The disconnect callback of a **closed** GATT handle could cancel the link
>   just started, so a pick aborted itself. Callbacks from any handle that is
>   not the current one are now ignored.
> - The link failure now logs its status (`Link failed (status 133).`) and the
>   target's type/bond state (`1C:… (DUAL, bonded)`), so the journal says why.
> - One automatic retry with the system transport when the LE link is refused
>   before connecting.
> - Repeated Connect taps no longer stack identical pickers.
>
> **1.2.5 fixes** (the glasses never advertise the GM service, so no amount
> of scanning can identify them):
> - *Connect glasses* now opens the picker on the **devices already paired
>   with the phone** (`BluetoothAdapter.getBondedDevices()`), which is where
>   the glasses show up reliably. Scanning only runs when the phone has no
>   paired device at all.
> - After a scan timeout the picker lists paired devices first, then whatever
>   was seen, without duplicates.
> - When a pick turns out not to be the glasses (no `0x2021`), the paired list
>   is offered again instead of ending the attempt.
> - Dismissing the picker resets the link state, so Connect can be used again.
>
> **1.2.3 fixes**:
> - *Connect glasses* now ignores any BLE peripheral that does not advertise
>   the GM control service (or its legacy alias), so a stray device in range
>   can no longer be picked instead of the glasses.
> - The status log is selectable (long-press to select) and there is a
>   *Copy log* button, so the full journal can be shared.
>
> **1.2.0 fixes** (make the Fitbit link actually deliver data):
> - `onSensorReady()` is now fired from `onDescriptorWrite` (CCCD ACK), not from
>   `onServicesDiscovered` — the HUD no longer shows READING with no value.
> - `gatt` is taken from `onConnectionStateChange`'s handle and the status is
>   checked, so a stale handle can't be used.
> - `GlassesHudClient` now cycles through scanned candidates (12 s scan budget)
>   instead of giving up on the first unrelated BLE device.
> - Reconnecting no longer stacks a second GATT on a live link.

---

## What it does

1. **Heart-rate sensor (BLE central → sensor)**
   Scans for BLE devices, lets you pick the source, then validates and
   subscribes to the standard Bluetooth SIG **Heart Rate service** (`0x180D`) /
   **Heart Rate Measurement** (`0x2A37`) and parses beats-per-minute from the
   measurement flags (uint8/uint16 formats). Works with any compliant source —
   no vendor SDK required. This includes wearables that broadcast optical HR
   over the standard Bluetooth Heart Rate Profile:
   - **Fitbit Charge 6** — swipe down → *HR on Equipment*
   - **Google Fitbit Air** — Google Health app → Connections → *Share heart rate*
   - **Google Pixel Watch 2 or newer** — Connected Fitness → *Connect* (a PIN
     prompt may appear; *Extended pairing* helps with some apps)

   > These wearables only broadcast while their *share heart rate* mode is on and
   > accept a limited number of simultaneous connections. The Fitbit Web API and
   > Health Connect are **not** used: the former only returns synced/batched data
   > (and is being retired on 2026-10-30), so neither can drive a live HUD. The
   > live path is the standard Bluetooth Heart Rate Profile.

2. **Distance, speed & time (phone GPS)**
   A heart-rate source carries no motion data, so distance and speed come from
   the phone's GNSS receiver (`LocationTracker`): distance is accumulated from
   successive fixes, current speed prefers the receiver's Doppler value, and
   average speed follows from distance ÷ elapsed time.

3. **MemoMind glasses (BLE client → glasses)**
   Connects to the glasses GM command service, subscribes to the response
   characteristic (`0x2022`), and writes GM frames to the command
   characteristic (`0x2021`). Two display paths are driven at once:

   - **Paired cardio plugin** (preferred). When the `cardio.gmp` glasses plugin
     is running it answers the connection with `EVENT_READY` on channel
     `0x4355`; the app then streams the `gm.cardio` protocol v1 on channel
     `0x4344` through the public plugin service (service `0x0F`, command
     `0x28`, `INT16 channel` + `BYTES payload`):
     - `CONFIG` `[1, 3, resting, maximal]` — zone bands (Karvonen)
     - `STATUS` `[1, 2, state]` — idle / connecting / reading / lost / error
     - `HEART_RATE` `[1, 1, bpm_lo, bpm_hi, zone, contactBad, trend]`, throttled
       to one payload every 250 ms

     The glasses render the big BPM, the zone label and their own 30-second
     trend line, so nothing else has to be drawn.
   - **Web Bridge text channel** (service `0x0F`, command `0x28`, channel `2`)
     as a fallback for when no cardio plugin is running: three text objects
     (heart rate, distance + speed, time + average speed) pushed by the
     *Show on glasses* button.

4. **Live push**
   Once the glasses are ready and a reading is available, every new heart-rate
   update is streamed to the cardio plugin automatically (throttled), and every
   GPS update is mirrored through the Web Bridge text channel. A button also
   pushes the latest Web Bridge values on demand.

---

## Requirements

- Android 8.0+ (API 26+). Runtime Bluetooth and location permissions are used on Android 12+.
- A BLE heart-rate source.
- Location/GPS enabled on the phone for distance and speed.
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

**Minimum BLE test** (no glasses): tap *Scan for heart sensor*, pick your device, and
confirm the BPM updates.

---

## Permissions

Declared in `AndroidManifest.xml`:

- `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT` — Android 12+.
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — GPS distance/speed, and legacy BLE scanning (API ≤ 30).
- `BLUETOOTH`, `BLUETOOTH_ADMIN` — legacy (API ≤ 30).

The app requests the correct set at runtime for the device's API level.

---

## Protocol notes

The glasses side follows the public MemoMind protocols (no vendor SDK):

- **GM packet framing** — `GlassSDK/docs/PROTOCOL.md`. A logical packet starts
  with `0xFA`, a 24-bit big-endian logical length, an event ID, a service ID, a
  command ID, the TLV body, and a 2-byte additive checksum (mod 65536).
- **Plugin application service `0x0F`** — downlink command `0x28` carries
  `INT16 channel` + `BYTES data`; uplink events use command `0x29`.
- **Cardio plugin protocol v1** — `GlassSDK/examples/cardio/cardio.c` and
  `PhoneSDK/examples/cardio/protocol.js`. Downlink channel `0x4344` (`'CD'`),
  upstream channel `0x4355` (`'CU'`). Commands: `HEART_RATE (1)`,
  `STATUS (2)`, `CONFIG (3)`; event `READY (1)`; states `IDLE 0`,
  `CONNECTING 1`, `READING 2`, `LOST 3`, `ERROR 4`; zones
  `NONE 0`, `REST 1`, `MODERATE 2`, `EFFORT 3`, `MAXIMUM 4`.
- **Web Bridge text channel `2`** — `GlassSDK/docs/HUD_PROTOCOL.md`. Payload:
  `id:u8 x:u16 y:u16 w:u16 h:u16 border:u8 radius:u8 utf8_text`.

The encoder in `GmFrame.kt` is verified byte-for-byte against the SDK wire
examples (`Web Bridge clear` and `Web Bridge text Hi`); the cardio payloads
mirror `protocol.js` byte for byte.

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
    GmFrame.kt            # GM framing + Web Bridge + cardio payload encoding
    HeartRateManager.kt   # BLE scan/connect/subscribe + 0x2A37 parsing
    LocationTracker.kt    # GPS distance, speed, average speed, elapsed time
    GlassesHudClient.kt   # BLE to glasses; GM frame writes, uplink parsing, MTU pacing
    MainActivity.kt       # UI, permissions, orchestration
  res/
    layout/activity_main.xml
    values/{strings,themes}.xml
```

## License

Provided as-is for the MemoMind ecosystem. The MemoMind protocols are documented
in the public MemoMind Plugin Open Platform SDK.
