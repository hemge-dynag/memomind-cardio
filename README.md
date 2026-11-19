# MemoMind Cardio (Android, standalone)

A standalone Android companion app that reads live heart rate from a Bluetooth
LE chest strap (e.g. **Polar H10**, H9, Garmin HRM, Wahoo TICKR…) and mirrors it
onto the **MemoMind smart glasses** HUD over Bluetooth LE.

This app exists because the MemoMind **plugin** ecosystem (Web `.mmpkg` and
glasses `.gmp` plugins) has **no Bluetooth / health-sensor access**. Reading a
heart-rate sensor requires native Bluetooth, so the only route is a native
Android app running outside the plugin model — which is what this is.

> Status: **1.2.17 — source-only**. The project is a complete, buildable Android
> Studio project. It has not been compiled into an APK in the authoring
> environment (no Android SDK available there); build it locally with Android
> Studio or the Gradle wrapper.
>
> **1.2.17** (sport selector):
> - New "Sport" block with three choices: *Course* (run), *Marche* (walk) and
>   *Autre sport* (generic).
> - Run and walk keep the full metrics UI (speed, distance, average) and start
>   the location foreground service.
> - *Autre sport* hides speed/distance/average, does not ask for the location
>   permission, and sends `TRACKING` with the `TRACKING_NO_SPEED` flag so the
>   glasses HUD shows only BPM and elapsed time.
> - Requires glasses plugin **cardio v3**.
>
> **1.2.15** (collapsible log):
> - The status log is now hidden by default; tap the "Journal" header (or its
>   "Afficher / Masquer" label) to expand or collapse it. The copy-log button
>   lives inside the panel.
>
> **1.2.14** (UI redesign + French):
> - New dark cardio design: hero heart-rate card with a live zone chip, a
>   distance / speed / time / average metrics card, pill-style action buttons
>   and a framed log panel.
> - The whole interface is now in French (labels, dialogs, status log).
>
> **1.2.13** (glasses HUD rework):
> - The Android app now streams `TRACKING` (command 4) so the glasses show the
>   workout time, speed and distance under the BPM, and hide that line when
>   tracking is off.
> - Requires glasses plugin `cardio.gmp` v2 or newer.
>
> **1.2.12** (icon restyle):
> - Icon now shows a red heart wearing eyeglasses (white frames) instead of the
>   ECG pulse line.
>
> **1.2.11** (app icon):
> - Added a proper launcher icon: a red heart crossed by a white ECG pulse on a
>   dark gradient, with adaptive-icon (foreground/background) layers and a round
>   variant for every density (mdpi → xxxhdpi).
> - Previously the app had no `android:icon`, so Android showed the default
>   placeholder.
>
> **1.2.10 fixes** (remember the heart sensor for faster reconnection):
> - The chosen heart-rate sensor (address + name) is saved in
>   `SharedPreferences` under `cardio_hr`.
> - The **Sensor** button now reconnects straight to the saved sensor without a
>   fresh scan. If it cannot be reached (moved, powered off, paired elsewhere),
>   the app forgets it and falls back to a normal scan once.
> - **Long-press Sensor** forgets the saved device and scans again.
> - The glasses address was already remembered; this adds the same convenience
>   for the heart-rate strap.
>
> **1.2.9 fixes** (1.2.8 journal: link good, four uplinks — system only — then
> *No cardio plugin announced itself*, and nothing drawn):
> - Readings are now pushed *continuously* in text fallback, throttled to four
>   per second, instead of one push per beat that silently dropped the rest.
> - Right after the six-second plugin timeout the app draws a **"HUD check"**
>   line, so the journal and the display together say whether the Web Bridge
>   renderer is running at all.
> - Any `0x29` reply on a non-cardio channel is reported as *Web Bridge
>   answered*, which distinguishes "no renderer" from "wrong drawing".
> - Pending text/plugin callbacks are cancelled on disconnect.
>
> **1.2.8 fixes** (the link came up but the HUD stayed blank, while the phone
> showed a live BPM — so the *cardio plugin* on the glasses never greeted us):
> - After six seconds on a live link with no `EVENT_READY`, the app reports
>   *No cardio plugin announced itself* and draws the reading through the
>   **Web Bridge text** channel (`"142 BPM"`), which needs no plugin at all.
> - Every outbound frame and every uplink is counted, and the first uplinks
>   are logged with their head/service/command, so the journal shows whether
>   the glasses answer at all.
> - Unknown plugin-channel uplinks are logged with channel and payload hex.
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
     - `TRACKING` `[1, 4, flags, time_s:u32le, speed_0.1kmh:u16le, distance_m:u32le]`
       — `flags` bit 0 = tracking active; sent with zeros when tracking stops

     The glasses render the big BPM, the unit, the zone label and (while
     tracking) a time / speed / distance line, so nothing else has to be drawn.
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
  `STATUS (2)`, `CONFIG (3)`, `TRACKING (4)`; event `READY (1)`; states `IDLE 0`,
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

## Glasses plugin (`glass/`)

The glasses side lives in this repository too:

- `glass/cardio.c` — LVGL plugin source (`com.dynag.cardio.glass`), version 3.
- `glass/manifest.json` — plugin manifest (`version: 3`).
- `glass/cardio.gmp` — compiled plugin, install once with Plugin Studio.
- Protocol `gm.cardio` 1.0, command `TRACKING (4)`:
  `[ver=1, 4, flags, time_s:u32le, speed_0.1kmh:u16le, distance_m:u32le]`.
  `flags` bit 0 = tracking active, bit 1 (`0x02`) = speed-less sport
  (HUD shows BPM + elapsed time only).

Rebuild from a Plugin Open Platform workspace:

```bash
python3 GlassSDK/build.py build --example cardio
```

## APK

Signed release APK: `apk/memomind-cardio-1.2.17.apk`
(sha256 `eb31f5954068169c94dc426f5fd577b67b2fc71f250eced319a606faabe6cf78`).

## F-Droid repository

The F-Droid index for this APK is published from the separate repository
[`hemge-dynag/fdroid`](https://github.com/hemge-dynag/fdroid) (fdroidserver
configuration + signing key, metadata under `metadata/`).
