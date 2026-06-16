package tech.saltvedt.gcu.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner
import tech.saltvedt.gcu.ble.RotisserieClient
import tech.saltvedt.gcu.ble.RotisserieUuids
import tech.saltvedt.gcu.ble.TempClient
import tech.saltvedt.gcu.ble.TempUuids
import tech.saltvedt.gcu.ble.serviceUuids
import tech.saltvedt.gcu.model.CommsLogEntry
import tech.saltvedt.gcu.model.ConnectionStatus
import tech.saltvedt.gcu.model.ControllerEvent
import tech.saltvedt.gcu.model.RotisserieState
import tech.saltvedt.gcu.model.TempState
import tech.saltvedt.gcu.model.UiState

/**
 * Single source of truth for the BBQ control screen. Scans for the rotisserie and
 * temp probe, auto-connects to each, and merges their state into one [UiState].
 */
class BbqRepository(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val rotisserie = RotisserieClient(appContext, scope)
    private val temp = TempClient(appContext, scope)

    /** Reflects whether a scan is currently active (before either device connects). */
    private val scanningRotisserie = MutableStateFlow(false)
    private val scanningTemp = MutableStateFlow(false)

    /** Rolling BLE communication log (newest last), capped at [MAX_LOG_ENTRIES]. */
    private val commsLog = MutableStateFlow<List<CommsLogEntry>>(emptyList())

    init {
        merge(rotisserie.comms, temp.comms)
            .onEach { entry -> commsLog.update { (it + entry).takeLast(MAX_LOG_ENTRIES) } }
            .launchIn(scope)
    }

    val uiState: StateFlow<UiState> =
        combine(rotisserie.state, temp.state, commsLog) { r, t, log ->
            UiState(
                rotisserie = applyScanning(r, scanningRotisserie.value),
                temp = applyScanning(t, scanningTemp.value),
                commsLog = log,
            )
        }.stateIn(scope, SharingStarted.Eagerly, UiState())

    val events = rotisserie.events

    private var started = false
    private var scanJob: Job? = null
    private var rotisserieAddress: String? = null
    private var tempAddress: String? = null

    private fun applyScanning(state: RotisserieState, scanning: Boolean): RotisserieState =
        if (scanning && state.connection == ConnectionStatus.Idle)
            state.copy(connection = ConnectionStatus.Scanning) else state

    private fun applyScanning(state: TempState, scanning: Boolean): TempState =
        if (scanning && state.connection == ConnectionStatus.Idle)
            state.copy(connection = ConnectionStatus.Scanning) else state

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        scanningRotisserie.value = true
        scanningTemp.value = true
        scanJob = BleScanner(appContext).scan()
            .onEach { result -> onScanResult(result.device.address, result.device.name, result.serviceUuids()) }
            .launchIn(scope)
    }

    private fun onScanResult(address: String, name: String?, serviceUuids: List<java.util.UUID>) {
        if (rotisserieAddress == null &&
            (name == RotisserieUuids.DEVICE_NAME || serviceUuids.contains(RotisserieUuids.SERVICE))
        ) {
            rotisserieAddress = address
            scanningRotisserie.value = false
            Log.i(TAG, "Connecting to rotisserie at $address")
            scope.launch { rotisserie.connect(address) }
        }
        if (tempAddress == null && serviceUuids.contains(TempUuids.SCAN_SERVICE)) {
            tempAddress = address
            scanningTemp.value = false
            Log.i(TAG, "Connecting to temp sensor at $address")
            scope.launch { temp.connect(address) }
        }
        if (rotisserieAddress != null && tempAddress != null) {
            scanJob?.cancel()
        }
    }

    fun flipBy(turns: Float) {
        scope.launch { rotisserie.flipBy(turns) }
    }

    fun stop() {
        scope.launch { rotisserie.stop() }
    }

    fun clearErrors() {
        scope.launch { rotisserie.clearErrors() }
    }

    fun setMaxVelocity(velocity: Float) {
        scope.launch { rotisserie.setMaxVelocity(velocity) }
    }

    fun setAutoTurn(stepTurns: Float, periodSeconds: Float) {
        scope.launch { rotisserie.enableAutoTurn(stepTurns, periodSeconds) }
    }

    fun cancelAutoTurn() {
        scope.launch { rotisserie.disableAutoTurn() }
    }

    private companion object {
        const val TAG = "BbqRepository"
        const val MAX_LOG_ENTRIES = 50
    }
}
