package ch.usi.childmonitor.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ch.usi.childmonitor.ble.BlePermissions
import ch.usi.childmonitor.ble.ConnectionState
import ch.usi.childmonitor.ble.ScannedDevice

@Composable
fun DeviceScreen(
    state: MonitorUiState,
    onMockModeChange: (Boolean) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(BlePermissions.allGranted(context)) }
    var permissionsDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsGranted = BlePermissions.allGranted(context)
        permissionsDenied = !permissionsGranted
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionsGranted = BlePermissions.allGranted(context)
    }

    val ready = state.useMock || (permissionsGranted && state.bluetoothEnabled)
    val showDevices = state.connection is ConnectionState.Disconnected ||
        state.connection is ConnectionState.Scanning ||
        state.connection is ConnectionState.Error

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Simulated data", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Demo mode without the Arduino",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.useMock, onCheckedChange = onMockModeChange)
            }
        }
        if (!ready) {
            item {
                SetupCard(
                    permissionsGranted = permissionsGranted,
                    permissionsDenied = permissionsDenied,
                    onRequestPermissions = { permissionLauncher.launch(BlePermissions.required()) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                    onEnableBluetooth = {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                )
            }
        } else {
            item {
                ConnectionControls(
                    state = state,
                    onStartScan = onStartScan,
                    onStopScan = onStopScan,
                    onDisconnect = onDisconnect,
                )
            }
            if (showDevices) {
                if (state.devices.isEmpty()) {
                    item {
                        Text(
                            text = if (state.connection is ConnectionState.Scanning) {
                                "Looking for a device named ChildMonitor…"
                            } else {
                                "No devices yet. Power the Portenta and press Scan."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.devices, key = ScannedDevice::address) { device ->
                    DeviceRow(device = device, onClick = { onConnect(device.address) })
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    permissionsGranted: Boolean,
    permissionsDenied: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!permissionsGranted) {
                Text("Bluetooth permission needed", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "The app uses Bluetooth only to find and read the ChildMonitor wearable.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onRequestPermissions) { Text("Grant permission") }
                if (permissionsDenied) {
                    Text(
                        text = "Permission was denied. If the system no longer asks, enable it in the app settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
                }
            } else {
                Text("Bluetooth is off", style = MaterialTheme.typography.titleMedium)
                Text("Turn Bluetooth on to scan for the wearable.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onEnableBluetooth) { Text("Turn on Bluetooth") }
            }
        }
    }
}

@Composable
private fun ConnectionControls(
    state: MonitorUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.connection is ConnectionState.Scanning || state.connection is ConnectionState.Connecting ||
                    state.connection is ConnectionState.Reconnecting
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = state.connection.label(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.connection is ConnectionState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            when (state.connection) {
                ConnectionState.Disconnected, is ConnectionState.Error ->
                    Button(onClick = onStartScan) { Text("Scan") }
                ConnectionState.Scanning ->
                    OutlinedButton(onClick = onStopScan) { Text("Stop scanning") }
                ConnectionState.Connecting, is ConnectionState.Reconnecting, ConnectionState.Connected ->
                    Button(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name ?: "Unnamed device", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${device.address} · ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Connect", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
