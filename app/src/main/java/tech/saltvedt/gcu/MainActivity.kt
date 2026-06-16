
package tech.saltvedt.gcu

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.saltvedt.gcu.ui.MainScreen
import tech.saltvedt.gcu.ui.MainViewModel
import tech.saltvedt.gcu.ui.theme.GCUTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GCUTheme {
                val viewModel: MainViewModel = viewModel()
                PermissionGate(onGranted = { viewModel.start() }) {
                    MainScreen(
                        uiStateFlow = viewModel.uiState,
                        events = viewModel.events,
                        onFlipBy = viewModel::flipBy,
                        onStop = viewModel::stop,
                        onClearErrors = viewModel::clearErrors,
                        onSetMaxVelocity = viewModel::setMaxVelocity,
                        onSetAutoTurn = viewModel::setAutoTurn,
                        onCancelAutoTurn = viewModel::cancelAutoTurn,
                    )
                }
            }
        }
    }
}

/** The BLE permissions required, depending on the Android version. */
private val requiredBlePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * Requests the BLE permissions and only renders [content] once they are granted,
 * invoking [onGranted] exactly once on the transition to granted.
 */
@Composable
private fun PermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    fun allGranted() = requiredBlePermissions.all {
        ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(allGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it } || allGranted()
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(requiredBlePermissions)
    }

    if (granted) {
        LaunchedEffect(Unit) { onGranted() }
        content()
    } else {
        PermissionRequest(onRequest = { launcher.launch(requiredBlePermissions) })
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Bluetooth permission is required to connect to the rotisserie and probes.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onRequest) { Text("Grant permission") }
        }
    }
}
