package tech.saltvedt.gcu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import tech.saltvedt.gcu.ble.RotisserieUuids
import tech.saltvedt.gcu.model.CommsLogEntry
import tech.saltvedt.gcu.model.ConnectionStatus
import tech.saltvedt.gcu.model.ControllerEvent
import tech.saltvedt.gcu.model.ControllerEventKind
import tech.saltvedt.gcu.model.RotisserieState
import tech.saltvedt.gcu.model.TempState
import tech.saltvedt.gcu.model.UiState
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiStateFlow: StateFlow<UiState>,
    events: SharedFlow<ControllerEvent>,
    onFlipBy: (Float) -> Unit,
    onStop: () -> Unit,
    onClearErrors: () -> Unit,
    onSetMaxVelocity: (Float) -> Unit,
) {
    val uiState by uiStateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        events.collect { event ->
            snackbarHostState.showMessage(event)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("BBQ Rotisserie") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionHeader(uiState.rotisserie, uiState.temp)
            RotisserieCard(uiState.rotisserie, onFlipBy, onStop, onClearErrors, onSetMaxVelocity)
            TempCard(uiState.temp)
            CommsLogCard(uiState.commsLog)
        }
    }
}

private suspend fun SnackbarHostState.showMessage(event: ControllerEvent) {
    val message = when (event.kind) {
        ControllerEventKind.MoveComplete -> "Move complete"
        ControllerEventKind.Stall -> "Stall detected — motor idled"
        ControllerEventKind.OdriveError -> "ODrive error 0x${event.payload.toString(16)} — motor idled"
        ControllerEventKind.ConnectionLost -> "ODrive connection lost — motor idled"
        ControllerEventKind.Unknown -> "Controller event"
    }
    showSnackbar(message)
}

@Composable
private fun ConnectionHeader(rotisserie: RotisserieState, temp: TempState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionChip("Rotisserie", rotisserie.connection, rotisserie.rssi, Modifier.weight(1f))
        ConnectionChip("Temp sensor", temp.connection, temp.rssi, Modifier.weight(1f))
    }
}

@Composable
private fun ConnectionChip(
    label: String,
    status: ConnectionStatus,
    rssi: Int?,
    modifier: Modifier = Modifier,
) {
    val color = when (status) {
        ConnectionStatus.Connected -> Color(0xFF2E7D32)
        ConnectionStatus.Connecting, ConnectionStatus.Scanning -> Color(0xFFF9A825)
        ConnectionStatus.Disconnected -> Color(0xFFC62828)
        ConnectionStatus.Idle -> Color(0xFF757575)
    }
    Surface(
        color = color,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
            Text(
                if (status == ConnectionStatus.Connected && rssi != null)
                    "${status.name} · $rssi dBm" else status.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RotisserieCard(
    state: RotisserieState,
    onFlipBy: (Float) -> Unit,
    onStop: () -> Unit,
    onClearErrors: () -> Unit,
    onSetMaxVelocity: (Float) -> Unit,
) {
    val connected = state.connection == ConnectionStatus.Connected
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Rotisserie", style = MaterialTheme.typography.titleMedium)

            TelemetryRow("Position", state.position?.let { "%.3f turns".format(it) })
            TelemetryRow("Velocity", state.velocity?.let { "%.3f rev/s".format(it) })
            TelemetryRow(
                "Target",
                if (state.targetActive) state.target?.let { "%.3f turns".format(it) } else "idle",
            )
            TelemetryRow("Motor", state.motorStatus?.let {
                "${it.axisStateLabel}${if (it.isRunning) " · running" else ""}"
            })
            TelemetryRow("Bus voltage", state.busVoltage?.let { "%.2f V".format(it) })
            TelemetryRow("Bus current", state.motorStatus?.let { "%.2f A".format(it.busCurrent) })
            TelemetryRow("Torque", state.motorStatus?.let { "%.3f Nm".format(it.torque) })
            TelemetryRow(
                "Errors",
                if (state.hasErrors)
                    "active=0x${state.activeErrors.toString(16)} disarm=0x${state.disarmReason.toString(16)}"
                else "none",
            )

            FlipControl(enabled = connected, onFlipBy = onFlipBy)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onStop, enabled = connected, modifier = Modifier.weight(1f)) {
                    Text("Stop")
                }
                OutlinedButton(onClick = onClearErrors, enabled = connected, modifier = Modifier.weight(1f)) {
                    Text("Clear errors")
                }
            }

            MaxVelocityControl(
                enabled = connected,
                current = state.maxVelocity,
                onSetMaxVelocity = onSetMaxVelocity,
            )
        }
    }
}

@Composable
private fun MaxVelocityControl(
    enabled: Boolean,
    current: Float,
    onSetMaxVelocity: (Float) -> Unit,
) {
    // Rarely changed: a compact text field + Set button. Accepts (0, 1.0] rev/s,
    // defaulting to the firmware's 0.1. Seeded from the last-written value.
    var text by remember(current) { mutableStateOf("%.2f".format(current)) }
    val typed = text.toFloatOrNull()
    val valid = typed != null && typed > 0f && typed <= RotisserieUuids.MAX_VELOCITY_MAX
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Max speed", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            isError = text.isNotEmpty() && !valid,
            suffix = { Text("rev/s") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.width(140.dp),
        )
        Button(
            onClick = { typed?.let(onSetMaxVelocity) },
            enabled = enabled && valid,
        ) {
            Text("Set")
        }
    }
}

@Composable
private fun FlipControl(enabled: Boolean, onFlipBy: (Float) -> Unit) {
    // -1 to 1 turns. The slider snaps to 0.25 increments (8 intervals -> 7
    // intermediate steps), but the text field can override with any precise value
    // in range. The two stay in sync: dragging fills the field, typing moves the thumb.
    var turns by remember { mutableFloatStateOf(1.0f) }
    var text by remember { mutableStateOf("1.00") }
    val typed = text.toFloatOrNull()
    val valid = typed != null && typed in -1f..1f
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Turns", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    it.toFloatOrNull()?.let { v -> if (v in -1f..1f) turns = v }
                },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                modifier = Modifier.width(112.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Slider(
                value = turns,
                onValueChange = {
                    turns = it
                    text = "%.2f".format(it)
                },
                valueRange = -1f..1f,
                steps = 7,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { typed?.let(onFlipBy) },
                enabled = enabled && valid,
            ) {
                Text("Flip")
            }
        }
    }
}

@Composable
private fun TempCard(state: TempState) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Meat probes", style = MaterialTheme.typography.titleMedium)
            TelemetryRow("Probe 1", state.temp1C?.let { "%.1f °C".format(it) })
            TelemetryRow("Probe 2", state.temp2C?.let { "%.1f °C".format(it) })
            TelemetryRow("Battery", state.battery?.let { "$it%" })
        }
    }
}

@Composable
private fun CommsLogCard(entries: List<CommsLogEntry>) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val errorCount = entries.count { it.isError }
            Text(
                if (errorCount > 0) "Comms log · $errorCount error(s)" else "Comms log",
                style = MaterialTheme.typography.titleMedium,
            )
            if (entries.isEmpty()) {
                Text("No activity yet", style = MaterialTheme.typography.bodySmall, color = Color(0xFF757575))
            } else {
                // Newest first, limited so the card stays readable.
                for (entry in entries.asReversed().take(15)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            formatTime(entry.timestampMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF757575),
                        )
                        Text(
                            "${entry.source}: ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.isError) Color(0xFFC62828) else Color.Unspecified,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

private fun formatTime(millis: Long): String = timeFormat.format(java.util.Date(millis))

@Composable
private fun TelemetryRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}
