package tech.saltvedt.gcu.ble

import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import tech.saltvedt.gcu.model.ConnectionStatus

/** Map the Nordic library connection state onto our UI-facing [ConnectionStatus]. */
fun GattConnectionState?.toConnectionStatus(): ConnectionStatus = when (this) {
    GattConnectionState.STATE_CONNECTING -> ConnectionStatus.Connecting
    GattConnectionState.STATE_CONNECTED -> ConnectionStatus.Connected
    GattConnectionState.STATE_DISCONNECTING -> ConnectionStatus.Connecting
    GattConnectionState.STATE_DISCONNECTED -> ConnectionStatus.Disconnected
    null -> ConnectionStatus.Connecting
}
