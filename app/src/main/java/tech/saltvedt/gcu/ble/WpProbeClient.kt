package tech.saltvedt.gcu.ble

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResult
import tech.saltvedt.gcu.model.CommsLogEntry
import tech.saltvedt.gcu.model.ConnectionStatus
import tech.saltvedt.gcu.model.WpProbeState

/**
 * Passive receiver for the WPprobe beacon thermometer. Unlike [TempClient] this device
 * never connects: it broadcasts its dual-sensor readings in BLE advertisement
 * manufacturer data, so we simply decode every matching advertisement seen by the shared
 * scan in [tech.saltvedt.gcu.data.BbqRepository].
 *
 * Because there is no GATT link to signal a drop, readings are aged out: if no beacon
 * arrives within [STALE_MS] the state reverts to [ConnectionStatus.Disconnected] with
 * blank readings, so the UI stops showing a frozen temperature.
 */
class WpProbeClient(scope: CoroutineScope) {
    private val _state = MutableStateFlow(WpProbeState())
    val state = _state.asStateFlow()

    private val _comms = MutableSharedFlow<CommsLogEntry>(extraBufferCapacity = 64)
    val comms = _comms.asSharedFlow()

    @Volatile
    private var lastSeen = 0L

    init {
        scope.launch {
            while (isActive) {
                delay(STALE_CHECK_MS)
                val seen = lastSeen
                if (seen != 0L &&
                    SystemClock.elapsedRealtime() - seen > STALE_MS &&
                    _state.value.connection == ConnectionStatus.Connected
                ) {
                    _state.update {
                        it.copy(
                            connection = ConnectionStatus.Disconnected,
                            rssi = null,
                            meatC = null,
                            ambientC = null,
                        )
                    }
                    log("No beacon for ${STALE_MS / 1000}s — reading stale", error = true)
                }
            }
        }
    }

    /** Feed every scan result here; non-WPprobe advertisements are ignored. */
    fun onAdvertisement(result: BleScanResult) {
        if (result.device.name != WpProbeProtocol.TARGET_NAME) return
        val reading = result.manufacturerData()
            .asSequence()
            .map { (companyId, value) -> Parsing.reconstructWpPayload(companyId, value) }
            .mapNotNull { Parsing.decodeWpProbe(it) }
            .firstOrNull() ?: return

        lastSeen = SystemClock.elapsedRealtime()
        val reacquired = _state.value.connection != ConnectionStatus.Connected
        _state.update {
            it.copy(
                connection = ConnectionStatus.Connected,
                rssi = result.rssi(),
                meatC = reading.meatC,
                ambientC = reading.ambientC,
                battery = reading.battery,
            )
        }
        // Log only on (re)acquisition so the ~1 Hz beacon stream doesn't flood the log.
        if (reacquired) {
            log("Beacon acquired — meat=${fmt(reading.meatC)} ambient=${fmt(reading.ambientC)} batt=${reading.battery}%")
        }
    }

    private fun fmt(c: Float?): String = c?.let { "%.1f°C".format(it) } ?: "LO"

    private fun log(message: String, error: Boolean = false) {
        if (error) Log.w(TAG, message) else Log.i(TAG, message)
        _comms.tryEmit(CommsLogEntry(System.currentTimeMillis(), "WPprobe", message, error))
    }

    private companion object {
        const val TAG = "WpProbeClient"
        const val STALE_MS = 30_000L
        const val STALE_CHECK_MS = 5_000L
    }
}
