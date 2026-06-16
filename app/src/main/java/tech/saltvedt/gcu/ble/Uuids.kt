package tech.saltvedt.gcu.ble

import java.util.UUID

/**
 * BBQ rotisserie motor controller GATT interface.
 * See DOCS-BLE.md. All multi-byte fields are little-endian; values are gearbox turns.
 */
object RotisserieUuids {
    const val DEVICE_NAME = "BBQ-Rotisserie"

    val SERVICE: UUID = UUID.fromString("59a4a1c0-8b8e-4a9c-bbe3-000000000001")
    val POSITION: UUID = UUID.fromString("59a4a1c0-8b8e-4a9c-bbe3-000000000002")
    val VELOCITY: UUID = UUID.fromString("59a4a1c0-8b8e-4a9c-bbe3-000000000003")
    val TARGET_POSITION: UUID = UUID.fromString("59a4a1c0-8b8e-4a9c-bbe3-000000000004")
    val MOTOR_STATUS: UUID = UUID.fromString("59a4a1c0-8b8e-4a9c-bbe3-000000000005")
    val BUS_VOLTAGE: UUID = UUID.fromString("59a4a1c0-8b8e-4a9c-bbe3-000000000006")
    val ERRORS: UUID = UUID.fromString("59a4a1c0-8b8e-4a9c-bbe3-000000000007")
    val CONTROLLER_EVENT: UUID = UUID.fromString("59a4a1c0-8b8e-4a9c-bbe3-000000000008")
    val COMMAND: UUID = UUID.fromString("59a4a1c0-8b8e-4a9c-bbe3-000000000009")

    // command kinds (byte 0 of the COMMAND characteristic)
    const val CMD_FLIP_BY: Byte = 0x00
    const val CMD_FORCE_IDLE: Byte = 0x02
    const val CMD_CLEAR_ERRORS: Byte = 0x03
}

/**
 * Third-party meat-probe thermometer. Protocol unchanged from the legacy app;
 * the handshake/poll/decode bytes are reverse-engineered and preserved verbatim.
 */
object TempUuids {
    /** Advertised service used only as a scan filter. */
    val SCAN_SERVICE: UUID = UUID.fromString("00006301-0000-1000-8000-00805f9b34fb")
    /** Actual GATT service exposed after connecting. */
    val SERVICE: UUID = UUID.fromString("00006301-0000-0041-4c50-574953450000")
    val NOTIFY: UUID = UUID.fromString("00006302-0000-0041-4c50-574953450000")
    val WRITE: UUID = UUID.fromString("00006303-0000-0041-4c50-574953450000")
}
