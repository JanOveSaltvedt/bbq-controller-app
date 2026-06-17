# CLAUDE.md

Guidance for working in this repository.

## What this app is

An Android app (`tech.saltvedt.gcu`) that controls a motorized **BBQ rotisserie**
and reads **meat-probe temperatures**, both over Bluetooth Low Energy. It is a
clean Compose/Coroutines reimplementation of an older nRF-Blinky-derived Java app
(documented in `OLD-APP.md`).

Three physical BLE devices are supported:

- **Rotisserie motor controller** — own GATT connection; current firmware protocol;
  spec is the authoritative `DOCS-BLE.md` (service `59a4a1c0-…`, advertised name
  `BBQ-Rotisserie`).
- **Wired meat-probe thermometer** — own GATT connection; third-party device whose
  protocol is unchanged from the legacy app and was reverse-engineered
  (handshake/poll/decode magic bytes). Two probes multiplexed over one connection.
- **WPprobe wireless thermometer** — **beacon-only, no GATT connection**. A
  dual-sensor (meat tip + ambient) probe that broadcasts its readings in BLE
  advertisement *manufacturer-specific data*; the app decodes them passively from
  the running scan. Protocol reverse-engineered (see `temp-probe.py`, a reference
  Python decoder kept in the repo root).

A **Lift** feature exists in the old app but is intentionally **out of scope** here
(the hardware does not exist yet).

## Build & test

```bash
./gradlew :app:assembleDebug        # build APK
./gradlew :app:testDebugUnitTest    # JVM unit tests (parsers)
./gradlew installDebug              # install on a connected device (BLE needs real hardware)
```

This is a git repo with no CI. The BLE features require physical hardware; an
emulator only exercises the scanning/disconnected UI states. The pure decoders in
`ble/Parsing.kt` are covered by JVM unit tests (`app/src/test/.../ble/ParsingTest.kt`)
and are the main thing verifiable without hardware.

## Tech stack

- Kotlin 2.2.10, AGP 9.2.1, Jetpack Compose (Material 3), Coroutines/Flow.
- `minSdk 30`, `targetSdk 36`, `compileSdk 36`. Java 11.
- BLE: **Nordic Kotlin BLE Library 1.3.x** (groupId `no.nordicsemi.android.kotlin.ble`,
  modules `scanner` / `client` / `core`). Note: this is the **1.x** line — the 2.x
  line uses a different groupId (`no.nordicsemi.kotlin.ble`) and a changed API; do
  not mix them.
- Versions live in `gradle/libs.versions.toml`; dependencies are referenced via the
  version catalog (`libs.*`).

## Architecture

Unidirectional flow: BLE clients expose `StateFlow`s → repository combines them →
`AndroidViewModel` → Compose collects with `collectAsStateWithLifecycle()`.

```
tech.saltvedt.gcu/
├── ble/
│   ├── Uuids.kt           # RotisserieUuids + TempUuids (service/char UUIDs, command kinds)
│   ├── Parsing.kt         # PURE decoders (f32/u32 LE, stale sentinels, wired-temp decode,
│   │                      #   WPprobe decode) + command encoders + TempProtocol /
│   │                      #   WpProbeProtocol byte constants
│   ├── ConnectionMapping.kt # GattConnectionState -> ConnectionStatus
│   ├── ScanResultExt.kt   # BleScanResult.serviceUuids() / rssi() / manufacturerData()
│   ├── RotisserieClient.kt# GATT for BBQ-Rotisserie: subscribes telemetry, sends commands
│   ├── TempClient.kt      # GATT for wired thermometer: handshake, 5s poll loop, decode
│   └── WpProbeClient.kt   # PASSIVE beacon receiver for WPprobe: decodes advertisements,
│                          #   ages readings out after 30s of silence (no GATT)
├── data/
│   └── BbqRepository.kt   # owns all three clients + app CoroutineScope; runs one scan,
│                          #   auto-connects the GATT devices, feeds beacons to WpProbeClient,
│                          #   merges into one UiState; exposes command methods
├── model/
│   └── Models.kt          # ConnectionStatus, RotisserieState, MotorStatus,
│                          #   ControllerEvent(+Kind), TempState, WpProbeState, UiState
├── ui/
│   ├── MainViewModel.kt   # AndroidViewModel wrapping the repository
│   └── MainScreen.kt      # single screen: connection chips, temperature chips,
│                          #   telemetry cards, controls, snackbars
└── MainActivity.kt        # version-aware BLE permission gate, then MainScreen
```

### Connection / scan flow
`MainActivity` requests BLE permissions, then calls `viewModel.start()` →
`BbqRepository.start()` begins a single `BleScanner` scan. Every scan result is
matched by service UUID / device name; on first match the rotisserie and wired temp
sensor are each auto-connected via their own `ClientBleGatt.connect(..., autoConnect = true)`
(address guards prevent duplicate connects). Every result is also handed to
`WpProbeClient.onAdvertisement()`. There is **no device-picker screen** by design.

**The scan never stops** — the WPprobe is beacon-only and needs continuous
advertisements. (The older "stop once both GATT devices connect" behavior was
removed for this reason.) Because a beacon device can't signal a disconnect,
`WpProbeClient` ages its reading out: if no beacon arrives within 30 s it reverts to
`Disconnected` with blank readings so the UI stops showing a frozen temperature.

## BLE protocol notes

- **All multi-byte fields are little-endian** *except the WPprobe temps* (see below).
  Rotisserie values are in *gearbox turns*.
- **Stale sentinels** (see `DOCS-BLE.md`): four `0xFF` bytes for f32 chars
  (position/velocity/bus_voltage), all-zero for `motor_status`/`errors`. Decoders in
  `Parsing.kt` return `null` for stale; the UI renders `—`.
- **Commands** (`command` char): `flip_by` = `[0x00] + f32 turns`, `force_idle` = `[0x02]`,
  `clear_errors` = `[0x03]`. Implemented controls: relative move (flip_by), Stop, Clear errors.
- **controller_event** is notify-only and not cached — subscribe and treat as one-shot
  (surfaced as snackbars: MoveComplete / Stall / OdriveError / ConnectionLost).
- **Wired temp probe protocol is reverse-engineered**: the handshake frames, the two
  5-byte per-probe poll frames, the "skip the first 11 notifications" rule, and the
  `data[8/9]`/`data[2]`/`data[3]` decode are hard-won. **Preserve these bytes verbatim**
  (mirrored from the legacy `TempManager`). The decode keeps the legacy formula
  (`raw - 100` treated as °F, then → °C) on purpose, even though it reads physically high.
- **WPprobe (beacon) protocol** (`WpProbeProtocol` + `Parsing.decodeWpProbe`): matched by
  advertised name `WPprobe`. The BLE stack splits manufacturer data into a 2-byte
  company-id (really the first two MAC bytes) + value bytes; `reconstructWpPayload`
  prepends the company-id as two LE bytes to rebuild the 15-byte payload. In that payload
  the meat tip `[8:10]` and ambient `[12:14]` temps are **big-endian** int16, `÷10 = °C`;
  battery is `[10]`. Inactive sensors report sentinels (meat `0xFFFF`, ambient `0x8000`) →
  `null`; out-of-range values are also dropped. **Preserve these bytes/offsets verbatim.**

## Conventions

- Keep all decoding logic pure and in `Parsing.kt` so it stays unit-testable; clients
  only do BLE I/O and state updates. Android collection types (e.g. the scan record's
  `SparseArray`) are flattened in `ScanResultExt.kt` so they don't leak into the decoders.
- UUID/byte constants live in `ble/Uuids.kt` and `ble/Parsing.kt` (`TempProtocol`,
  `WpProbeProtocol`) — don't inline them elsewhere.
- Each device has its own client class exposing a `StateFlow<…State>` and a
  `SharedFlow<CommsLogEntry>`; the repository merges them. The WPprobe client follows the
  same shape even though it never connects.
- Permissions: API 31+ uses `BLUETOOTH_SCAN` (`neverForLocation`) + `BLUETOOTH_CONNECT`;
  API 30 uses legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION`
  (capped with `maxSdkVersion="30"`). The runtime request set is chosen by version in
  `MainActivity`.

## Reference docs

- `DOCS-BLE.md` — authoritative rotisserie GATT spec (the source of truth for that device).
- `OLD-APP.md` — summary of the legacy app and the wired temp-probe protocol being preserved.
- `temp-probe.py` — reference Python decoder for the WPprobe beacon protocol.
