# CLAUDE.md

Guidance for working in this repository.

## What this app is

An Android app (`tech.saltvedt.gcu`) that controls a motorized **BBQ rotisserie**
and reads **meat-probe temperatures**, both over Bluetooth Low Energy. It is a
clean Compose/Coroutines reimplementation of an older nRF-Blinky-derived Java app
(documented in `OLD-APP.md`).

Two physical BLE devices, each its own GATT connection:
- **Rotisserie motor controller** — current firmware protocol; spec is the
  authoritative `DOCS-BLE.md` (service `59a4a1c0-…`, advertised name `BBQ-Rotisserie`).
- **Meat-probe thermometer** — third-party device; protocol is unchanged from the
  legacy app and was reverse-engineered (handshake/poll/decode magic bytes).

A **Lift** feature exists in the old app but is intentionally **out of scope** here
(the hardware does not exist yet).

## Build & test

```bash
./gradlew :app:assembleDebug        # build APK
./gradlew :app:testDebugUnitTest    # JVM unit tests (parsers)
./gradlew installDebug              # install on a connected device (BLE needs real hardware)
```

There is no git repo and no CI. The BLE features require physical hardware; an
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
│   ├── Parsing.kt         # PURE decoders (f32/u32 LE, stale sentinels, temp decode,
│   │                      #   command encoders) + TempProtocol handshake/poll bytes
│   ├── ConnectionMapping.kt # GattConnectionState -> ConnectionStatus
│   ├── ScanResultExt.kt   # BleScanResult.serviceUuids()
│   ├── RotisserieClient.kt# GATT for BBQ-Rotisserie: subscribes telemetry, sends commands
│   └── TempClient.kt      # GATT for thermometer: handshake, 5s poll loop, decode
├── data/
│   └── BbqRepository.kt   # owns both clients + app CoroutineScope; scans, auto-connects,
│                          #   merges into one UiState; exposes command methods
├── model/
│   └── Models.kt          # ConnectionStatus, RotisserieState, MotorStatus,
│                          #   ControllerEvent(+Kind), TempState, UiState
├── ui/
│   ├── MainViewModel.kt   # AndroidViewModel wrapping the repository
│   └── MainScreen.kt      # single screen: connection chips, telemetry cards, controls, snackbars
└── MainActivity.kt        # version-aware BLE permission gate, then MainScreen
```

### Connection flow
`MainActivity` requests BLE permissions, then calls `viewModel.start()` →
`BbqRepository.start()` begins a `BleScanner` scan. Results are matched by service
UUID / device name; on first match each device is auto-connected via its own
`ClientBleGatt.connect(..., autoConnect = true)`. Scanning stops once both are found.
There is **no device-picker screen** by design — it auto-connects to the fixed appliance.

## BLE protocol notes

- **All multi-byte fields are little-endian.** Rotisserie values are in *gearbox turns*.
- **Stale sentinels** (see `DOCS-BLE.md`): four `0xFF` bytes for f32 chars
  (position/velocity/bus_voltage), all-zero for `motor_status`/`errors`. Decoders in
  `Parsing.kt` return `null` for stale; the UI renders `—`.
- **Commands** (`command` char): `flip_by` = `[0x00] + f32 turns`, `force_idle` = `[0x02]`,
  `clear_errors` = `[0x03]`. Implemented controls: relative move (flip_by), Stop, Clear errors.
- **controller_event** is notify-only and not cached — subscribe and treat as one-shot
  (surfaced as snackbars: MoveComplete / Stall / OdriveError / ConnectionLost).
- **Temp probe protocol is reverse-engineered**: the handshake frames, the two 5-byte
  per-probe poll frames, the "skip the first 11 notifications" rule, and the
  `data[8/9]`/`data[2]`/`data[3]` decode are hard-won. **Preserve these bytes verbatim**
  (mirrored from the legacy `TempManager`). The decode keeps the legacy formula
  (`raw - 100` treated as °F, then → °C) on purpose, even though it reads physically high.

## Conventions

- Keep all decoding logic pure and in `Parsing.kt` so it stays unit-testable; clients
  only do BLE I/O and state updates.
- UUID/byte constants live in `ble/Uuids.kt` and `ble/Parsing.kt` (`TempProtocol`) —
  don't inline them elsewhere.
- Permissions: API 31+ uses `BLUETOOTH_SCAN` (`neverForLocation`) + `BLUETOOTH_CONNECT`;
  API 30 uses legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION`
  (capped with `maxSdkVersion="30"`). The runtime request set is chosen by version in
  `MainActivity`.

## Reference docs

- `DOCS-BLE.md` — authoritative rotisserie GATT spec (the source of truth for that device).
- `OLD-APP.md` — summary of the legacy app and the temp-probe protocol being preserved.