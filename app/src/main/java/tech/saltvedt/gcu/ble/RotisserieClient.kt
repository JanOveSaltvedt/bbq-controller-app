package tech.saltvedt.gcu.ble

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
import no.nordicsemi.android.kotlin.ble.core.data.BleGattProperty
import no.nordicsemi.android.kotlin.ble.core.data.BleWriteType
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

    /** Write type for [commandChar], chosen from its advertised properties. */
    @Volatile
    private var commandWriteType: BleWriteType = BleWriteType.DEFAULT

    /** Per-connection job owning discovery + all notification collectors. */
    private var sessionJob: Job? = null

    /** Observes the GATT connection state for the *current* [client]. */
    private var connectionJob: Job? = null

    /** Last address we were asked to connect to, for [reopen]. */
    @Volatile
    private var address: String? = null

    /** Guards against overlapping [reopen] attempts. */
    @Volatile
    private var reopening = false

    suspend fun connect(address: String) {
        this.address = address
        open()
    }

    /**
     * Open a *fresh* GATT connection. Each call creates a new [ClientBleGatt] with its
     * own shared operation mutex — the only way to recover from a write that hung and
     * left the previous connection's mutex permanently locked (see [write]).
     */
    @SuppressLint("MissingPermission")
    private suspend fun open() {
        val target = address ?: return
        _state.update { it.copy(connection = ConnectionStatus.Connecting) }
        log("Connecting to $target")
        val gatt = ClientBleGatt.connect(
            context = context,
            macAddress = target,
            scope = scope,
            options = BleGattConnectOptions(autoConnect = true),
        )
        client = gatt

        // One observer per connection: drives the chip and re-arms the session each
        // time the link (re)connects. Cancel any prior observer first.
        connectionJob?.cancel()
        connectionJob = gatt.connectionStateWithStatus
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

    /**
     * Fully tear the current GATT down and reconnect from scratch. Used when a write
     * wedges the shared mutex: a plain link-level reconnect reuses the same locked
     * mutex, so [discoverServices] would deadlock and never rebind [commandChar].
     */
    private fun reopen(reason: String) {
        if (reopening) return
        reopening = true
        scope.launch {
            try {
                log("Reconnecting from scratch — $reason", error = true)
                sessionJob?.cancel()
                connectionJob?.cancel()
                commandChar = null
                runCatching { client?.close() }
                client = null
                open()
            } finally {
                reopening = false
            }
        }
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

            val command = service.findCharacteristic(RotisserieUuids.COMMAND)
            commandChar = command
            if (command == null) {
                log("Command characteristic missing", error = true)
            } else {
                // Prefer write-without-response when the controller advertises it: it
                // resolves on the local stack callback instead of waiting for a device
                // ack that may never arrive (which is what wedges the shared mutex).
                commandWriteType =
                    if (command.properties.contains(BleGattProperty.PROPERTY_WRITE_NO_RESPONSE))
                        BleWriteType.NO_RESPONSE else BleWriteType.DEFAULT
                log(
                    "Command char ready — props: ${command.properties.joinToString { it.name }}" +
                        " · writeType=$commandWriteType",
                )
            }

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

    /** Serializes command writes so taps can't race each other into the GATT queue. */
    private val writeMutex = Mutex()

    /**
     * Write a command, surfacing every outcome to the comms log. The Nordic `write()`
     * suspends on the GATT write-callback and hangs indefinitely if the controller
     * never acks — and because that callback also releases the connection's *shared*
     * mutex, a single hung write wedges every later operation (including reconnect's
     * service discovery). So: log the attempt up front, serialize writes, bound each
     * with a timeout, and on timeout tear the connection down and reconnect fresh to
     * clear the wedged mutex instead of silently swallowing all future commands.
     */
    @SuppressLint("MissingPermission")
    private suspend fun write(bytes: ByteArray, label: String) {
        val characteristic = commandChar
        if (characteristic == null) {
            log("$label dropped — not ready (no command characteristic)", error = true)
            return
        }
        log("$label → writing ${bytes.size} B")
        try {
            writeMutex.withLock {
                withTimeout(WRITE_TIMEOUT_MS) {
                    characteristic.write(DataByteArray(bytes), commandWriteType)
                }
            }
            log("$label acknowledged")
        } catch (e: TimeoutCancellationException) {
            log("$label timed out after $WRITE_TIMEOUT_MS ms — connection wedged, reconnecting", error = true)
            reopen("write '$label' timed out")
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
        const val WRITE_TIMEOUT_MS = 3000L
    }
}