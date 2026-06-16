package tech.saltvedt.gcu.ble

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattCharacteristic
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectOptions
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray
import tech.saltvedt.gcu.model.CommsLogEntry
import tech.saltvedt.gcu.model.ConnectionStatus
import tech.saltvedt.gcu.model.TempState

/**
 * Owns the GATT connection to the meat-probe thermometer. Runs the reverse-engineered
 * handshake on connect, polls both probes every 5 s, and decodes temperature frames.
 *
 * Like [RotisserieClient], the handshake/subscribe/poll setup is re-run on every
 * transition into [GattConnectionState.STATE_CONNECTED] so a reconnect (under
 * `autoConnect = true`) re-arms a fresh GATT session instead of silently going quiet.
 * The protocol bytes themselves are unchanged and must stay verbatim.
 */
class TempClient(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(TempState())
    val state = _state.asStateFlow()

    private val _comms = MutableSharedFlow<CommsLogEntry>(extraBufferCapacity = 64)
    val comms = _comms.asSharedFlow()

    private var client: ClientBleGatt? = null

    @Volatile
    private var writeChar: ClientBleGattCharacteristic? = null
    private var setupCounter = 0

    /** Per-connection job owning the notify collector and the poll loop. */
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

    private fun onDisconnected() {
        writeChar = null
        sessionJob?.cancel()
        _state.update { it.copy(rssi = null) }
        log("Disconnected — link dropped", error = true)
    }

    private fun onConnected(gatt: ClientBleGatt) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            log("Link up — discovering services")
            val services = gatt.discoverServices()
            val service = services.findService(TempUuids.SERVICE)
            if (service == null) {
                log("Temp service not found", error = true)
                return@launch
            }
            writeChar = service.findCharacteristic(TempUuids.WRITE)
            if (writeChar == null) log("Temp write characteristic missing", error = true)

            // Fresh session: the skip-first-11-notifications counter restarts because
            // the handshake echoes are replayed below.
            setupCounter = 0
            val notify = service.findCharacteristic(TempUuids.NOTIFY)
            if (notify == null) {
                log("Temp notify characteristic missing", error = true)
                return@launch
            }
            notify.getNotifications()
                .onEach { onNotify(it.value) }
                .launchIn(this)

            // Handshake: init frame followed by the fixed setup sequence.
            write(TempProtocol.INIT, "init")
            for (cmd in TempProtocol.SETUP_COMMANDS) write(cmd, "setup")

            log("Handshake sent — polling")
            startPolling()
            pollRssi(gatt)
        }
    }

    /**
     * Poll the remote RSSI on a fixed cadence for as long as this session is alive.
     * Runs as a child of [sessionJob], so it stops on disconnect. A failed read is
     * swallowed (rssi cleared) — it must never take down the session or poll loop.
     */
    @SuppressLint("MissingPermission")
    private fun CoroutineScope.pollRssi(gatt: ClientBleGatt) {
        launch {
            while (isActive) {
                try {
                    val rssi = gatt.readRssi()
                    _state.update { it.copy(rssi = rssi) }
                } catch (e: Exception) {
                    _state.update { it.copy(rssi = null) }
                }
                delay(RSSI_INTERVAL_MS)
            }
        }
    }

    private fun onNotify(data: ByteArray) {
        // The first 11 notifications are setup echoes; ignore them.
        if (setupCounter++ < TempProtocol.SETUP_ECHO_COUNT) return
        val reading = Parsing.decodeTemp(data) ?: return
        _state.update {
            if (reading.probeId == 0) {
                it.copy(temp1C = reading.celsius, battery = reading.battery)
            } else {
                it.copy(temp2C = reading.celsius, battery = reading.battery)
            }
        }
    }

    private fun CoroutineScope.startPolling() {
        launch {
            while (isActive) {
                write(TempProtocol.POLL_PROBE_0, "poll0")
                write(TempProtocol.POLL_PROBE_1, "poll1")
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(bytes: ByteArray, label: String) {
        val characteristic = writeChar
        if (characteristic == null) {
            log("$label dropped — not ready (no write characteristic)", error = true)
            return
        }
        try {
            // Bounded so a hung write can't permanently stall the poll loop.
            withTimeout(WRITE_TIMEOUT_MS) {
                characteristic.write(DataByteArray(bytes))
            }
        } catch (e: TimeoutCancellationException) {
            log("$label timed out after $WRITE_TIMEOUT_MS ms — no GATT write ack", error = true)
        } catch (e: Exception) {
            log("$label failed: ${e.message ?: e.javaClass.simpleName}", error = true)
        }
    }

    private fun log(message: String, error: Boolean = false) {
        if (error) Log.w(TAG, message) else Log.i(TAG, message)
        _comms.tryEmit(CommsLogEntry(System.currentTimeMillis(), "Temp", message, error))
    }

    private companion object {
        const val TAG = "TempClient"
        const val POLL_INTERVAL_MS = 5000L
        const val WRITE_TIMEOUT_MS = 3000L
        const val RSSI_INTERVAL_MS = 3000L
    }
}