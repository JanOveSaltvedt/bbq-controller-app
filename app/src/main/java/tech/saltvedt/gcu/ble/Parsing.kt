package tech.saltvedt.gcu.ble

import tech.saltvedt.gcu.model.ControllerEvent
import tech.saltvedt.gcu.model.ControllerEventKind
import tech.saltvedt.gcu.model.MotorStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure decoding helpers for the BBQ BLE payloads. Kept side-effect-free so they
 * can be unit-tested on the JVM without any Android/BLE dependencies.
 */
object Parsing {

    fun f32(bytes: ByteArray, offset: Int = 0): Float =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float

    fun u32(bytes: ByteArray, offset: Int = 0): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun ByteArray.allEqual(value: Byte): Boolean = isNotEmpty() && all { it == value }

    /** `position`, `velocity`, `bus_voltage`: f32; stale sentinel = four 0xFF bytes. */
    fun parseF32OrStale(bytes: ByteArray): Float? =
        if (bytes.size < 4 || bytes.copyOf(4).allEqual(0xFF.toByte())) null else f32(bytes)

    /** `target_position`: byte0 flag (0 idle / 1 moving) + f32 target. */
    fun parseTarget(bytes: ByteArray): Pair<Boolean, Float?> {
        if (bytes.size < 5) return false to null
        val active = bytes[0].toInt() == 0x01
        return active to if (active) f32(bytes, 1) else null
    }

    /** `motor_status`: 14 bytes; stale sentinel = all zeros. */
    fun parseMotorStatus(bytes: ByteArray): MotorStatus? {
        if (bytes.size < 14 || bytes.allEqual(0x00)) return null
        return MotorStatus(
            axisState = bytes[0].toInt() and 0xFF,
            isRunning = bytes[1].toInt() != 0,
            busCurrent = f32(bytes, 2),
            torque = f32(bytes, 6),
            velocityGearbox = f32(bytes, 10),
        )
    }

    /** `errors`: u32 active_errors + u32 disarm_reason. (all-zero == no errors) */
    fun parseErrors(bytes: ByteArray): Pair<Long, Long> {
        if (bytes.size < 8) return 0L to 0L
        return u32(bytes, 0) to u32(bytes, 4)
    }

    /** `controller_event`: byte0 kind + u32 payload. */
    fun parseEvent(bytes: ByteArray): ControllerEvent? {
        if (bytes.size < 5) return null
        val kind = when (bytes[0].toInt() and 0xFF) {
            0 -> ControllerEventKind.MoveComplete
            1 -> ControllerEventKind.Stall
            2 -> ControllerEventKind.OdriveError
            3 -> ControllerEventKind.ConnectionLost
            else -> ControllerEventKind.Unknown
        }
        return ControllerEvent(kind, u32(bytes, 1))
    }

    /** Encode a `command` write. flip_by is 5 bytes; force_idle/clear_errors 1 byte. */
    fun flipByCommand(turns: Float): ByteArray =
        ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(RotisserieUuids.CMD_FLIP_BY).putFloat(turns).array()

    val forceIdleCommand: ByteArray = byteArrayOf(RotisserieUuids.CMD_FORCE_IDLE)
    val clearErrorsCommand: ByteArray = byteArrayOf(RotisserieUuids.CMD_CLEAR_ERRORS)

    /** `set_max_velocity`: kind byte + f32 LE cap in gearbox rev/s (5 bytes). */
    fun setMaxVelocityCommand(velocity: Float): ByteArray =
        ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(RotisserieUuids.CMD_SET_MAX_VELOCITY).putFloat(velocity).array()

    /**
     * `auto_turn` notify/read: byte0 enabled (0/1) + f32 step turns + f32 period seconds
     * (9 bytes). Returns (enabled, step, period); null when the frame is too short.
     */
    fun parseAutoTurn(bytes: ByteArray): Triple<Boolean, Float, Float>? {
        if (bytes.size < 9) return null
        return Triple(bytes[0].toInt() == 0x01, f32(bytes, 1), f32(bytes, 5))
    }

    /**
     * `auto_turn_remaining`: f32 LE seconds until the next turn. The "disabled"
     * sentinel is the same four-0xFF pattern as the stale f32 chars, so reuse
     * [parseF32OrStale] — null means no schedule is active.
     */
    fun parseAutoTurnRemaining(bytes: ByteArray): Float? = parseF32OrStale(bytes)

    /** `auto_turn` enable write: flag 0x01 + f32 step + f32 period (9 bytes). */
    fun enableAutoTurnCommand(step: Float, period: Float): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x01).putFloat(step).putFloat(period).array()

    /** `auto_turn` cancel write: a single 0x00 flag byte. */
    val disableAutoTurnCommand: ByteArray = byteArrayOf(0x00)

    // --- Temperature probe ---

    data class TempReading(val probeId: Int, val celsius: Float, val battery: Int)

    /**
     * Decode a real temperature notification frame (reverse-engineered magic numbers,
     * preserved verbatim from the legacy TempManager).
     */
    fun decodeTemp(data: ByteArray): TempReading? {
        if (data.size < 10) return null
        val raw = ((data[9].toInt() and 0xFF) shl 2) or (data[8].toInt() and 0x03)
        val fahr = raw - 100
        val celsius = ((fahr - 32) * 5.0 / 9.0).toFloat()
        val battery = data[2].toInt() and 0x7F
        val probeId = (data[3].toInt() and 0xF0) shr 4
        return TempReading(probeId, celsius, battery)
    }

    // --- WPprobe (beacon-only dual-sensor thermometer) ---

    data class WpProbeReading(val meatC: Float?, val ambientC: Float?, val battery: Int)

    /** Big-endian unsigned 16-bit read at [offset]. */
    private fun be16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /**
     * The BLE stack splits manufacturer data into a 2-byte company id (here really the
     * first two MAC bytes) plus the value bytes. Rebuild the full payload by prepending
     * the company id as two little-endian bytes — mirror of the reference python
     * `reconstruct_payload`.
     */
    fun reconstructWpPayload(companyId: Int, value: ByteArray): ByteArray =
        byteArrayOf((companyId and 0xFF).toByte(), ((companyId shr 8) and 0xFF).toByte()) + value

    /**
     * Decode a reconstructed WPprobe manufacturer payload (tip + ambient temps). Each
     * sensor reports a sentinel when inactive (meat 0xFFFF, ambient 0x8000) and those —
     * along with out-of-range values — decode to null. Returns null for a short frame.
     */
    fun decodeWpProbe(payload: ByteArray): WpProbeReading? {
        if (payload.size < WpProbeProtocol.PAYLOAD_LEN) return null
        return WpProbeReading(
            meatC = wpTemp(be16(payload, 8), WpProbeProtocol.MEAT_SENTINEL),
            ambientC = wpTemp(be16(payload, 12), WpProbeProtocol.AMBIENT_SENTINEL),
            battery = payload[10].toInt() and 0xFF,
        )
    }

    /** A big-endian int16 tenths-of-a-degree reading; sentinel or out-of-range -> null. */
    private fun wpTemp(word: Int, sentinel: Int): Float? {
        if (word == sentinel) return null
        val signed = if (word and 0x8000 != 0) word - 0x10000 else word
        val c = signed / 10.0f
        return if (c in WpProbeProtocol.TEMP_LO..WpProbeProtocol.TEMP_HI) c else null
    }
}

/**
 * Temp probe protocol constants (handshake + poll frames), preserved verbatim.
 * The first 11 notifications after connect are setup echoes and must be skipped.
 */
object TempProtocol {
    const val SETUP_ECHO_COUNT = 11

    val INIT: ByteArray = byteArrayOf(0x01, 0x00)

    val SETUP_COMMANDS: Array<ByteArray> = arrayOf(
        byteArrayOf(0xa0.toByte(), 0x04, 0x0f, 0xab.toByte()),
        byteArrayOf(0xa1.toByte(), 0x04, 0xf0.toByte(), 0x55),
        byteArrayOf(0xac.toByte(), 0x0a, 0x8f.toByte(), 0x0c, 0xed.toByte(), 0x69, 0xf7.toByte(), 0xe5.toByte(), 0xf0.toByte(), 0x43),
        byteArrayOf(0xaa.toByte(), 0x08, 0xdc.toByte(), 0x9b.toByte(), 0xa0.toByte(), 0x56, 0xf0.toByte(), 0xe3.toByte()),
        byteArrayOf(0xa5.toByte(), 0x04, 0xf0.toByte(), 0x51),
        byteArrayOf(0xa2.toByte(), 0x05, 0x00, 0xf0.toByte(), 0x57),
        byteArrayOf(0xa3.toByte(), 0x05, 0x00, 0xf0.toByte(), 0x56),
        byteArrayOf(0xa2.toByte(), 0x05, 0x01, 0xf0.toByte(), 0x56),
        byteArrayOf(0xa3.toByte(), 0x05, 0x01, 0xf0.toByte(), 0x57),
        byteArrayOf(0xad.toByte(), 0x05, 0x00, 0xf0.toByte(), 0x58),
        byteArrayOf(0xad.toByte(), 0x05, 0x10, 0xf0.toByte(), 0x48),
    )

    val POLL_PROBE_0: ByteArray = byteArrayOf(0xa4.toByte(), 0x05, 0x00, 0xf0.toByte(), 0x51)
    val POLL_PROBE_1: ByteArray = byteArrayOf(0xa4.toByte(), 0x05, 0x01, 0xf0.toByte(), 0x50)
}

/**
 * WPprobe (third-party beacon thermometer) advertisement constants. This probe never
 * connects — it broadcasts a 15-byte manufacturer-data payload decoded by
 * [Parsing.decodeWpProbe]. Matched by advertised name [TARGET_NAME].
 */
object WpProbeProtocol {
    const val TARGET_NAME = "WPprobe"
    const val PAYLOAD_LEN = 15
    const val MEAT_SENTINEL = 0xFFFF        // tip "no reading"
    const val AMBIENT_SENTINEL = 0x8000     // ambient "no reading" -> LO
    const val TEMP_LO = -55.0f              // plausible range guard (Celsius)
    const val TEMP_HI = 350.0f
}
