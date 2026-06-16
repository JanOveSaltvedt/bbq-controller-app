# BBQ Rotisserie — BLE GATT Reference

This document describes the Bluetooth Low Energy interface exposed by the BBQ motor controller firmware. It is the primary reference for the Android client app.

All multi-byte fields are **little-endian**. All position and velocity values exposed over BLE are in **gearbox turns** (post-gearbox output). The hardware gearbox has a 1:25 reduction ratio — 1 gearbox turn = 25 motor shaft revolutions.

---

## Advertising

| Field | Value |
|---|---|
| Device name | `BBQ-Rotisserie` |
| BLE address | `02:01:BB:BB:AD:DE` (static random) |
| Address type | Random |
| AD flags | LE General Discoverable, BR/EDR Not Supported |
| Max simultaneous connections | 1 |

The device advertises connectable undirected packets continuously. It returns to advertising automatically after a client disconnects.

---

## Service

**Service UUID:** `59a4a1c0-8b8e-4a9c-bbe3-000000000001`

The service contains 10 characteristics:

| UUID suffix | Name | Properties |
|---|---|---|
| `...0002` | position | Read, Notify |
| `...0003` | velocity | Read, Notify |
| `...0004` | target_position | Read, Write, Notify |
| `...0005` | motor_status | Read, Notify |
| `...0006` | bus_voltage | Read, Notify |
| `...0007` | errors | Read, Notify |
| `...0008` | controller_event | Notify |
| `...0009` | command | Write |
| `...000a` | auto_turn | Read, Write, Notify |
| `...000b` | auto_turn_remaining | Read, Notify |

---

## Characteristics

### `position` — `59a4a1c0-8b8e-4a9c-bbe3-000000000002`

Current motor position.

| Bytes | Field | Type | Units |
|---|---|---|---|
| 0–3 | position | f32 LE | gearbox turns |

**Stale sentinel:** `[0xFF, 0xFF, 0xFF, 0xFF]` — data from the ODrive has not arrived within the last 30 ms. Treat as unknown; do not display or act on this value.

---

### `velocity` — `59a4a1c0-8b8e-4a9c-bbe3-000000000003`

Current motor velocity.

| Bytes | Field | Type | Units |
|---|---|---|---|
| 0–3 | velocity | f32 LE | gearbox rev/s |

**Stale sentinel:** `[0xFF, 0xFF, 0xFF, 0xFF]` — same 30 ms threshold as position.

---

### `target_position` — `59a4a1c0-8b8e-4a9c-bbe3-000000000004`

Current move target, or idle state. Also writable as an alternative to the `command` characteristic for direct position control.

**Read / Notify format (5 bytes):**

| Byte(s) | Field | Value |
|---|---|---|
| 0 | flag | `0x00` = no active target (idle) |
| | | `0x01` = move in progress |
| 1–4 | target | f32 LE gearbox turns (only meaningful when flag = `0x01`) |

**Write format (5 bytes):**

| Byte(s) | Value | Effect |
|---|---|---|
| 0 = `0x00`, 1–4 = any | Force idle — cancel any active move |
| 0 = `0x01`, 1–4 = f32 LE turns | Set absolute target position and begin move |

Minimum write length: 1 byte for force-idle, 5 bytes for set-target. Writing fewer than 5 bytes with flag `0x01` is silently ignored.

---

### `motor_status` — `59a4a1c0-8b8e-4a9c-bbe3-000000000005`

Composite motor snapshot (14 bytes).

| Bytes | Field | Type | Units | Notes |
|---|---|---|---|---|
| 0 | axis_state | u8 | — | `1` = Idle, `8` = Closed Loop Control |
| 1 | is_running | u8 | — | `1` when axis_state = 8 (actively moving), else `0` |
| 2–5 | bus_current | f32 LE | A | Current drawn from the power bus |
| 6–9 | torque_estimate | f32 LE | Nm | Estimated output torque |
| 10–13 | velocity_gearbox | f32 LE | gearbox rev/s | Same scale as the `velocity` characteristic |

**Stale sentinel:** `[0x00; 14]` — all zeros. Stale if heartbeat > 300 ms old or torque > 30 ms old.

---

### `bus_voltage` — `59a4a1c0-8b8e-4a9c-bbe3-000000000006`

Power bus voltage.

| Bytes | Field | Type | Units |
|---|---|---|---|
| 0–3 | voltage | f32 LE | V |

**Stale sentinel:** `[0xFF, 0xFF, 0xFF, 0xFF]` — stale after 3 000 ms without an ODrive update.

---

### `errors` — `59a4a1c0-8b8e-4a9c-bbe3-000000000007`

ODrive error state (8 bytes).

| Bytes | Field | Type | Notes |
|---|---|---|---|
| 0–3 | active_errors | u32 LE | Bitmask. `0` = no errors. |
| 4–7 | disarm_reason | u32 LE | Reason the motor was last disarmed. `0` = none. |

**Stale sentinel:** `[0x00; 8]` — all zeros. Stale after 300 ms without a heartbeat.

Error bitmask definitions are in the ODrive CANSimple protocol reference (`DOCS-CAN.md`). When `active_errors != 0` the controller will fire a `controller_event` with kind `OdriveError` and force the motor to idle.

---

### `controller_event` — `59a4a1c0-8b8e-4a9c-bbe3-000000000008`

Asynchronous event notifications from the controller (5 bytes, notify only).

| Bytes | Field | Type | Notes |
|---|---|---|---|
| 0 | kind | u8 | See table below |
| 1–4 | payload | u32 LE | Interpretation depends on kind |

| Kind | Name | Payload | Meaning |
|---|---|---|---|
| `0` | MoveComplete | `0x00000000` | Target reached; motor returned to idle |
| `1` | Stall | `0x00000000` | High current + near-zero velocity for ≥ 3 s; motor forced idle |
| `2` | OdriveError | error code u32 | ODrive reported an active error; motor forced idle |
| `3` | ConnectionLost | `0x00000000` | ODrive heartbeat absent for > 1 s; motor forced idle |

**Notify behaviour:** The cached GATT value is **not** updated on notify (`store=false`). Read the characteristic after reconnect will return the previous event, not the last one. Subscribe via CCCD and rely on notifications only.

After firing `controller_event`, the firmware immediately pushes a fresh telemetry batch (all eight batched characteristics), so the subsequent notifications will reflect the post-event state.

---

### `command` — `59a4a1c0-8b8e-4a9c-bbe3-000000000009`

Write-only control interface (5 bytes).

| Bytes | Field | Type |
|---|---|---|
| 0 | kind | u8 |
| 1–4 | argument | f32 LE |

| Kind | Name | Argument | Min length | Notes |
|---|---|---|---|---|
| `0x00` | flip_by | signed f32 displacement (gearbox turns) | 5 | Relative move from current position |
| `0x01` | set_target | f32 absolute position (gearbox turns) | 5 | Absolute move |
| `0x02` | force_idle | ignored | 1 | Stop immediately; clear pending move |
| `0x03` | clear_errors | ignored | 1 | Clear stall flag and ODrive errors |
| `0x04` | set_max_velocity | f32 max speed (gearbox rev/s) | 5 | Speed cap applied to the next and all subsequent moves |

Writes shorter than the minimum length for that kind are silently ignored.

**`set_max_velocity` (`0x04`):** sets the maximum move speed in **gearbox rev/s**. The default is `0.1` (1 gearbox revolution per 10 s). The value is clamped to the range `(0, 1.0]` gearbox rev/s — non-positive values are rejected, and values above `1.0` are capped. The cap is applied as the ODrive `vel_limit` at the start of every move (re-asserted per move), so it survives an ODrive reset but is **not** persisted across an ESP32 reboot — it returns to the `0.1` default on power-up. There is no read-back characteristic; track the last-written value client-side.

---

### `auto_turn` — `59a4a1c0-8b8e-4a9c-bbe3-00000000000a`

Recurring "turn X gearbox turns every Y seconds" schedule. When enabled, the controller automatically performs a relative move of `step` gearbox turns (a `flip_by`) once per `period`. This is intended for slow rotisserie roasting — the spit advances a little, then idles, repeatedly, hands-free.

**Read / Notify format (9 bytes):**

| Byte(s) | Field | Type | Notes |
|---|---|---|---|
| 0 | enabled | u8 | `0x00` = off, `0x01` = active |
| 1–4 | step | f32 LE | gearbox turns per cycle (signed; `0` when off) |
| 5–8 | period | f32 LE | seconds between cycles (`0` when off) |

**Write format (9 bytes):**

| Byte(s) | Value | Effect |
|---|---|---|
| 0 = `0x00`, 1–8 = any | Cancel the schedule (disable auto-turn) |
| 0 = `0x01`, 1–4 = f32 LE step, 5–8 = f32 LE period | Enable: turn `step` gearbox turns every `period` seconds |

Minimum write length: 1 byte to cancel, 9 bytes to enable. An enable write with fewer than 9 bytes is silently ignored.

**Behaviour notes:**

- **Direction:** each cycle is a *relative* move of `step` turns from the current position, so rotation accumulates in one direction (use a negative `step` to reverse). It does not oscillate back and forth.
- **First turn:** fires one full `period` after the enable write (the countdown starts at `period`, not at zero).
- **Validation:** non-finite `step`, zero `step`, or non-positive `period` are rejected and the write is ignored (the schedule is left unchanged).
- **Interaction with moves:** if a move (manual or a previous auto-turn) is still in progress when a turn is due, the turn is deferred until the controller is idle, then fires on the next 200 ms tick. The next cycle is timed from when a turn actually fires, so a slow move never builds up a backlog.
- **Cancellation:** a `force_idle` (via `command` kind `0x02` or a `0x00` write to `target_position`) **also cancels auto-turn** — Stop means stop everything. `clear_errors` leaves the schedule running.
- **Persistence:** the schedule is **not** persisted across an ESP32 reboot; it is disabled on power-up.

---

### `auto_turn_remaining` — `59a4a1c0-8b8e-4a9c-bbe3-00000000000b`

Time remaining until the next auto-turn fires.

| Bytes | Field | Type | Units |
|---|---|---|---|
| 0–3 | remaining | f32 LE | seconds |

Counts down from `period` toward `0` and resets after each turn. While a due turn is deferred (a move is still running) it reads `0`.

**Disabled sentinel:** `[0xFF, 0xFF, 0xFF, 0xFF]` — auto-turn is currently off. This is the same sentinel used by the stale f32 characteristics; here it specifically means "no schedule active" rather than "stale data".

---

## Notification Timing

**Telemetry batch (every 200 ms):**  
`position`, `velocity`, `target_position`, `motor_status`, `bus_voltage`, `errors`, `auto_turn`, and `auto_turn_remaining` are notified together as a batch while a client is connected. Each notification also updates the cached GATT value (`store=true`), so a plain Read on any of these characteristics returns the most recently notified value. (The `auto_turn_remaining` countdown therefore updates at ~5 Hz.)

**Event-driven:**  
`controller_event` is sent immediately when the move controller fires an event (not on the 200 ms cadence). A full telemetry batch follows it synchronously, so the app receives a consistent state snapshot after each event.

---

## Move Control Flow

### Starting a move

Write to `command` with kind `0x00` (flip_by) or `0x01` (set_target), or write to `target_position` with flag `0x01`. The controller sends the ODrive into Closed Loop Control mode and begins tracking the target.

### Configuring move speed

Write `command` kind `0x04` (set_max_velocity) with an f32 max speed in gearbox rev/s. This caps how fast moves are performed (applied as the ODrive `vel_limit`). The default is `0.1` gearbox rev/s (one revolution per 10 s); accepted range is `(0, 1.0]`. It takes effect on the next move. See the `set_max_velocity` note under the `command` characteristic for persistence details.

### Running a recurring turn (auto-turn)

To make the spit advance automatically, enable a schedule via the `auto_turn` characteristic:

1. Write `[0x01, <f32 step>, <f32 period>]` (9 bytes) to `auto_turn` — e.g. step = `0.25` turns, period = `30.0` s.
2. Subscribe to `auto_turn_remaining` (notified every 200 ms) to drive a countdown UI; it resets to `period` after each turn.
3. Each fired turn runs as a normal move, so you still get `controller_event` MoveComplete / Stall / etc. and live `position` updates.
4. Cancel with a `[0x00]` write to `auto_turn`, or stop everything with `force_idle` (which also cancels the schedule).

The first turn happens one full period after enabling. See the `auto_turn` characteristic for full semantics (direction, deferral, validation, persistence).

### Detecting completion

**Preferred:** subscribe to `controller_event` and wait for kind `0` (MoveComplete). The motor is already idle when this fires.

**Polling fallback:** The firmware considers a move complete when, after a 500 ms settle window, position is within 0.05 gearbox turns of the target and velocity is below 0.05 gearbox rev/s. You can monitor `position`, `velocity`, and `target_position` at 200 ms intervals to replicate this logic, but the event is simpler and more reliable.

### Detecting stall

Subscribe to `controller_event` kind `1` (Stall). The stall condition is: velocity < 0.25 motor rev/s (≈ 0.01 gearbox rev/s) AND bus current > 20 A, sustained for 3 consecutive seconds.

### Detecting ODrive connection loss

Subscribe to `controller_event` kind `3` (ConnectionLost). The ODrive normally sends a heartbeat every 100 ms; the firmware fires ConnectionLost if no heartbeat arrives within 1 000 ms.

### Cancelling a move

Write `command` kind `0x02` (force_idle), or write `[0x00, 0x00, 0x00, 0x00, 0x00]` to `target_position`. Both also cancel any active auto-turn schedule.

### Recovering from errors

1. Read `errors` to inspect `active_errors` and `disarm_reason`.
2. Fix the underlying condition (e.g. clear an overcurrent fault).
3. Write `command` kind `0x03` (clear_errors).
4. The controller is then ready to accept a new move.

---

## Units Reference

| Parameter | Wire unit | Notes |
|---|---|---|
| position | gearbox turns | 1 turn = 360° of gearbox output shaft |
| velocity | gearbox rev/s | |
| flip_by / set_target argument | gearbox turns | |
| auto_turn step | gearbox turns | per cycle; signed |
| auto_turn period / remaining | seconds | |
| bus_current | Amperes | Power bus, not motor phase current |
| bus_voltage | Volts | Typically ~24 V |
| torque_estimate | Newton-metres | Estimated at motor output |
| axis_state | ODrive enum | 1 = Idle, 8 = Closed Loop Control |

Motor turns = gearbox turns × 25 (1:25 reduction ratio). All BLE values use gearbox turns exclusively; the ×25 conversion is handled inside the firmware.
