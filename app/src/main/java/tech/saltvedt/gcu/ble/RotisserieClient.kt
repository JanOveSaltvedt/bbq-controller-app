package tech.saltvedt.gcu.ble

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattCharacteristic
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattService
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectOptions
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray
import tech.saltvedt.gcu.model.CommsLogEntry
import tech.saltvedt.gcu.model.ConnectionStatus
import tech.saltvedt.gcu.model.ControllerEvent
import tech.saltvedt.gcu.model.RotisserieState
import java.util.UUID

/**
 * Owns the GATT connection to the BBQ-Rotisserie motor controller, decodes its
 * telemetry into a [RotisserieState] flow, and exposes the move commands.
 *
 * Discovery, characteristic binding and notification subscriptions are re-run on
 * *every* transition into [GattConnectionState.STATE_CONNECTED], not just the first
 * one. With `autoConnect = true` the Nordic library re-establishes the link after a
 * drop, and that reconnect is a fresh GATT session: the old session's characteristics
 * and notification flows are dead, so they must be rebuilt or telemetry silently
 * freezes.
 */
class RotisserieClient(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RotisserieState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<ControllerEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private val _comms = MutableSharedFlow<CommsLogEntry>(extraBufferCapacity = 64)
    val comms = _comms.asSharedFlow()

    private var client: ClientBleGatt? = null

    /** Command characteristic for the *current* GATT session; null when not ready. */
    @Volatile
    private var commandChar: ClientBleGattCharacteristic? = null

    /** Per-connection job owning discovery + all notification collectors. */
    private var sessionJob: Job? = null

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String) {
        _state.update { it.copy(connection = ConnectionStatus.Connecting) }
        log("Connecting to $address")
        val gatt = ClientBleGatt.connect(
            context = context,
            macAddress = address,
            scope = scope,
            options = BleGattConnectOptions(autoConnect = true),
        )
        client = gatt

        // Single long-lived observer: drives the connection chip and re-arms the
        // session each time the link (re)connects.
        gatt.connectionStateWithStatus
            .onEach { s ->
                _state.update { it.copy(connection = s?.state.toConnectionStatus()) }
                when (s?.state) {
                    GattConnectionState.STATE_CONNECTED -> onConnected(gatt)
                    GattConnectionState.STATE_DISCONNECTED -> onDisconnected()
                    else -> {}
                }
            }
            .launchIn(scope)
    }

    /** Tear down the previous session: its characteristics are now invalid. */
    private fun onDisconnected() {
        commandChar = null
        sessionJob?.cancel()
        log("Disconnected — link dropped", error = true)
    }

    /** (Re)discover services and (re)subscribe telemetry for a fresh GATT session. */
    private fun onConnected(gatt: ClientBleGatt) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            log("Link up — discovering services")
            val services = gatt.discoverServices()
            val service = services.findService(RotisserieUuids.SERVICE)
            if (service == null) {
                log("Rotisserie service not found", error = true)
                return@launch
            }

            commandChar = service.findCharacteristic(RotisserieUuids.COMMAND)
            if (commandChar == null) log("Command characteristic missing", error = true)

            subscribe(service, RotisserieUuids.POSITION, "position") { data ->
                _state.update { it.copy(position = Parsing.parseF32OrStale(data)) }
            }
            subscribe(service, RotisserieUuids.VELOCITY, "velocity") { data ->
                _state.update { it.copy(velocity = Parsing.parseF32OrStale(data)) }
            }
            subscribe(service, RotisserieUuids.TARGET_POSITION, "target") { data ->
                val (active, target) = Parsing.parseTarget(data)
                _state.update { it.copy(targetActive = active, target = target) }
            }
            subscribe(service, RotisserieUuids.MOTOR_STATUS, "motor_status") { data ->
                _state.update { it.copy(motorStatus = Parsing.parseMotorStatus(data)) }
            }
            subscribe(service, RotisserieUuids.BUS_VOLTAGE, "bus_voltage") { data ->
                _state.update { it.copy(busVoltage = Parsing.parseF32OrStale(data)) }
            }
            subscribe(service, RotisserieUuids.ERRORS, "errors") { data ->
                val (active, disarm) = Parsing.parseErrors(data)
                _state.update { it.copy(activeErrors = active, disarmReason = disarm) }
            }
            subscribe(service, RotisserieUuids.CONTROLLER_EVENT, "controller_event") { data ->
                Parsing.parseEvent(data)?.let { _events.tryEmit(it) }
            }

            log("Telemetry subscribed — ready")
        }
    }

    /**
     * Subscribe to a characteristic's notifications within the current [sessionJob],
     * so the collector is cancelled (not stacked) when the session ends. Runs on the
     * receiver [CoroutineScope] — i.e. a child of [sessionJob].
     */
    private fun CoroutineScope.subscribe(
        service: ClientBleGattService,
        uuid: UUID,
        label: String,
        onValue: (ByteArray) -> Unit,
    ) {
        val characteristic = service.findCharacteristic(uuid)
        if (characteristic == null) {
            log("Characteristic '$label' missing", error = true)
            return
        }
        launch {
            characteristic.getNotifications()
                .onEach { onValue(it.value) }
                .launchIn(this)
        }
    }

    suspend fun flipBy(turns: Float) = write(Parsing.flipByCommand(turns), "flip_by $turns")

    suspend fun stop() = write(Parsing.forceIdleCommand, "force_idle")

    suspend fun clearErrors() = write(Parsing.clearErrorsCommand, "clear_errors")

    /**
     * Write a command, surfacing the outcome to the comms log instead of silently
     * dropping it. A null [commandChar] (not yet discovered, or stale after a
     * reconnect) is reported rather than swallowed by `?.`.
     */
    @SuppressLint("MissingPermission")
    private suspend fun write(bytes: ByteArray, label: String) {
        val characteristic = commandChar
        if (characteristic == null) {
            log("$label dropped — not ready (no command characteristic)", error = true)
            return
        }
        try {
            characteristic.write(DataByteArray(bytes))
            log("$label sent (${bytes.size} B)")
        } catch (e: Exception) {
            log("$label failed: ${e.message ?: e.javaClass.simpleName}", error = true)
        }
    }

    private fun log(message: String, error: Boolean = false) {
        if (error) Log.w(TAG, message) else Log.i(TAG, message)
        _comms.tryEmit(CommsLogEntry(System.currentTimeMillis(), "Rotisserie", message, error))
    }

    private companion object {
        const val TAG = "RotisserieClient"
    }
}