# BBQ Rotisserie Controller — Project Summary

An Android app that controls a motorized BBQ rotisserie ("GCU") and reads
meat-probe temperatures, both over Bluetooth Low Energy (BLE). It was
derived from Nordic Semiconductor's **nRF Blinky** sample, so much of the
package naming (`no.nordicsemi.android.blinky`, "Blinky", "LBS") is leftover
boilerplate rather than meaningful. This document captures *what it actually
does* so the useful features can be reimplemented cleanly in a future rewrite.

> Status: an MVP / hobby project. There are commented-out code blocks,
> hard-coded device MAC addresses, and connection-state handling that was
> stubbed out. Treat the behavior below as "what works today," not "what is
> well-architected."

## Tech stack

- **Language:** Java (Java 8 source/target)
- **Min SDK 18, target/compile SDK 29**, `versionName` 2.5.1
- **Build:** Gradle (Android Gradle Plugin 3.6.3) — old, will need upgrading
- **UI:** Android Views + Material Components + ConstraintLayout, ButterKnife
  for view binding, AndroidX Lifecycle (`ViewModel` + `LiveData`)
- **BLE:** Nordic's [`ble-livedata`](https://github.com/NordicSemiconductor/Android-BLE-Library)
  (`ObservableBleManager`) 2.2.0, Nordic support scanner 1.4.3, Nordic logger 2.2.0
- **Permissions:** `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`
  (location was required for BLE scanning pre-Android 12)

## App flow / screens

1. **SplashScreenActivity** — launcher, brief splash, forwards to scanner.
2. **ScannerActivity** — scans for BLE devices, shows a "found" chip for each
   expected device (GCU, Temp, Lift-left, Lift-right), and an "Open Controller"
   button that launches the control screen, passing the discovered GCU and Temp
   devices via Intent extras. Handles the location-permission / Bluetooth-off
   empty states.
3. **BlinkyActivity** (the real control screen) — connects to the GCU and Temp
   devices and renders the control UI (see Features).

`ViewModel`s (`ScannerViewModel`, `GCUViewModel`, `TempViewModel`) wrap the BLE
managers and expose `LiveData`; the BLE managers (`GCUManager`, `TempManager`,
`LiftManager`) own the GATT connections and characteristic parsing.

## Device discovery

`DevicesLiveData.deviceDiscovered()` filters scan results by **advertised
service UUID**:

- **GCU** (rotisserie controller) — service `dda07fc0-fc10-4216-be2a-466cf34d31d5`
- **Lift** — service `0x1809` (`00001809-…`); two units are distinguished by
  **hard-coded MAC address**: left `7E:DF:A1:61:C5:0D`, right `7E:DF:A1:AA:BB:CC`
  (right looks like a placeholder).
- **Temp** — advertised service `00006301-0000-1000-8000-00805f9b34fb`
  (note: the *actual* GATT service used after connect is a different UUID, below)

Each device type is tracked as its own single-value `LiveData`. There is an RSSI
filter constant (`-50 dBm`) defined but not actively applied.

---

## Feature 1 — Rotisserie control (GCU)

`GCUManager` connects to a custom GATT service and exposes:

| Purpose | Characteristic UUID | Direction | Format |
|---|---|---|---|
| Service | `dda07fc0-fc10-4216-be2a-466cf34d31d5` | — | — |
| Current angle | `d53ff3f4-a6f4-4383-a87f-4c5394d6c678` | notify + read | SINT32 |
| Battery | `762e655b-0f14-4fac-95ff-34664185f4c6` | notify + read | SINT32 |
| Target angle | `f6e43e6d-6539-4dff-9a69-a9f3c8cdf121` | write (unused in UI) | SINT32 |
| Target angle **delta** | `08a3e679-f27b-48d4-a45b-a29712442b37` | write | SINT32 LE |

- **Reads/notifications** for *current angle* and *battery* drive the UI.
- 4-byte little-endian signed int payloads, parsed by `SInt32DataCallback`
  (rejects payloads that aren't exactly 4 bytes).
- **Battery** is shown as volts: `value / 100` → e.g. `8.40 V`.
- **Current angle** is displayed as a signed angle plus number of full turns:
  `angle % 360` and `value / 360` → e.g. `+ 120° (+3)`. Total rotation is
  tracked as a cumulative degree count, not wrapped.
- **Control:** a SeekBar maps 0–100 to an angle of `-180…+180°`; the "Push
  Angle" button writes that value to the **target-angle-delta** characteristic
  (`addTargetDelta`), i.e. it commands a *relative* rotation. The controller
  firmware is responsible for moving by that delta.

## Feature 2 — Meat temperature probes (Temp)

`TempManager` talks to a third-party BLE thermometer (the setup byte sequences
strongly suggest a cloned/clone-protocol wireless meat thermometer). Two probes
are supported.

| Purpose | UUID |
|---|---|
| Advertised service (scan filter) | `00006301-0000-1000-8000-00805f9b34fb` |
| GATT service (after connect) | `00006301-0000-0041-4c50-574953450000` |
| Notify characteristic | `00006302-0000-0041-4c50-574953450000` |
| Write characteristic | `00006303-0000-0041-4c50-574953450000` |

**Protocol (reverse-engineered, magic numbers):**

- On connect, `initialize()` enables notifications, then writes a fixed sequence
  of ~11 **setup/handshake commands** (`0xa0…`, `0xa1…`, `0xac…`, etc.). The
  notify callback ignores the first 11 notifications (assumed to be setup echoes)
  before treating data as real temperature frames.
- **Polling:** `triggerTempUpdate()` writes two request frames (one per probe,
  `0xa4 05 00 …` / `0xa4 05 01 …`). `BlinkyActivity` calls this every **5 s**
  via a `postDelayed` loop.
- **Temperature decode** from a notify frame `data`:
  - raw = `((data[9] & 0xFF) << 2) | (data[8] & 0x03)`
  - Fahrenheit-ish = `raw - 100`
  - Celsius = `(f - 32) * 5/9`
  - battery = `data[2] & 0x7F`
  - probe id = `(data[3] & 0xF0) >> 4` (probe 0 → temp1, else → temp2)
- UI shows two large temperature readouts (`text_temp1`, `text_temp2`) in °C.

## Feature 3 — Lift (partial / unused)

`LiftManager` targets service `0x1809` with a "current height" characteristic
(`C9151596-5456-64B3-3845-265DF1626AA8`, SINT32). It reads/notifies a height
value, but **there is no UI wired up** for the lift in `BlinkyActivity`, and the
right-hand unit's MAC is a placeholder. This appears to be in-progress
scaffolding for raising/lowering something (the spit?) that was never finished.

---

## What's worth keeping in the rewrite

- The **per-device-type BLE scan + connect** pattern (filter by service UUID,
  track each device independently).
- The **GCU rotisserie protocol**: angle/battery notifications as SINT32,
  relative rotation commands via the target-angle-delta characteristic. The
  "angle + full-turn count" display is a nice touch.
- The **temperature-probe protocol** — the handshake byte sequences, the
  per-probe poll frames, and the `data[8/9]` / `data[2]` / `data[3]` decode are
  the genuinely hard-won, reverse-engineered knowledge here. Preserve these
  exact bytes.

## What to fix / rethink

- **Remove the nRF Blinky lineage**: package names, "Blinky"/"LBS" naming, the
  "DEVICE NOT SUPPORTED / flash the firmware" boilerplate strings.
- **Hard-coded MAC addresses** for lift left/right → make configurable.
- **Connection-state UI is stubbed out** (the whole observer block in
  `BlinkyActivity` is commented; the screen just force-shows content as if
  always connected). Real connect/disconnect/retry handling is needed.
- **No persistence, no charting/history** of temperature or angle.
- The temp "skip first 11 notifications" and "5 s polling" are fragile timing
  assumptions; consider a more robust state machine.
- Modernize: AGP/Gradle upgrade, Kotlin + Coroutines/Flow or Compose, runtime
  permission model for Android 12+ (`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`,
  `neverForLocation`), drop ButterKnife.

## File map (key sources)

```
app/src/main/java/no/nordicsemi/android/blinky/
├── SplashScreenActivity.java        # launcher splash
├── ScannerActivity.java             # device scan UI + start control
├── BlinkyActivity.java              # main control screen (GCU + Temp)
├── BlinkyApplication.java           # app + nRF logger init
├── viewmodels/
│   ├── ScannerViewModel.java        # scanning
│   ├── DevicesLiveData.java         # per-type UUID/MAC device filtering
│   ├── GCUViewModel.java            # wraps GCUManager
│   └── TempViewModel.java           # wraps TempManager
├── profile/
│   ├── GCUManager.java              # rotisserie GATT (angle/battery/target)
│   ├── TempManager.java             # thermometer GATT + decode protocol
│   ├── LiftManager.java             # lift GATT (unfinished, no UI)
│   └── callback/SInt32*.java        # SINT32 payload parsing
└── adapter/DiscoveredBluetoothDevice.java
```
