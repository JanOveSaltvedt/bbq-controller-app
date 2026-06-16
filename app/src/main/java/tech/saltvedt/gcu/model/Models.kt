package tech.saltvedt.gcu.model

/** Connection lifecycle of a single BLE device, surfaced to the UI. */
enum class ConnectionStatus { Idle, Scanning, Connecting, Connected, Disconnected }

/** Snapshot decoded from the rotisserie `motor_status` characteristic. */
data class MotorStatus(
    val axisState: Int,           // 1 = Idle, 8 = Closed Loop Control
    val isRunning: Boolean,
    val busCurrent: Float,        // Amperes
    val torque: Float,            // Newton-metres
    val velocityGearbox: Float,   // gearbox rev/s
) {
    val axisStateLabel: String
        get() = when (axisState) {
            1 -> "Idle"
            8 -> "Closed Loop"
            else -> "State $axisState"
        }
}

/** Full rotisserie state. Null fields mean "unknown / stale / not yet received". */
data class RotisserieState(
    val connection: ConnectionStatus = ConnectionStatus.Idle,
    val rssi: Int? = null,              // latest signal strength in dBm; null when unknown
    val position: Float? = null,        // gearbox turns
    val velocity: Float? = null,        // gearbox rev/s
    val targetActive: Boolean = false,
    val target: Float? = null,          // gearbox turns
    val motorStatus: MotorStatus? = null,
    val busVoltage: Float? = null,      // Volts
    val activeErrors: Long = 0L,
    val disarmReason: Long = 0L,
    val maxVelocity: Float = 0.1f,      // last-written move-speed cap, gearbox rev/s (no read-back)
    val autoTurnEnabled: Boolean = false,   // recurring turn schedule currently active
    val autoTurnStep: Float? = null,        // gearbox turns per cycle (from notify)
    val autoTurnPeriod: Float? = null,      // seconds between cycles (from notify)
    val autoTurnRemaining: Float? = null,   // seconds until the next turn; null when disabled
) {
    val hasErrors: Boolean get() = activeErrors != 0L || disarmReason != 0L
}

enum class ControllerEventKind { MoveComplete, Stall, OdriveError, ConnectionLost, Unknown }

/** One-shot asynchronous event from the rotisserie controller. */
data class ControllerEvent(val kind: ControllerEventKind, val payload: Long)

/** Two meat-probe temperatures plus probe battery. */
data class TempState(
    val connection: ConnectionStatus = ConnectionStatus.Idle,
    val rssi: Int? = null,              // latest signal strength in dBm; null when unknown
    val temp1C: Float? = null,
    val temp2C: Float? = null,
    val battery: Int? = null,           // 0..100
)

/** One line in the BLE communication log, surfaced for on-device diagnostics. */
data class CommsLogEntry(
    val timestampMs: Long,
    val source: String,        // "Rotisserie" / "Temp"
    val message: String,
    val isError: Boolean = false,
)

/** Combined UI state for the single main screen. */
data class UiState(
    val rotisserie: RotisserieState = RotisserieState(),
    val temp: TempState = TempState(),
    val commsLog: List<CommsLogEntry> = emptyList(),
)
