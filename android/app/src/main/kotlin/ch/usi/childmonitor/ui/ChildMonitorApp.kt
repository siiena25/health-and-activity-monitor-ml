package ch.usi.childmonitor.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.usi.childmonitor.ble.ConnectionState

private enum class AppTab(val label: String, val icon: ImageVector) {
    Monitor("Monitor", Icons.Filled.Favorite),
    Device("Device", Icons.Filled.Settings),
    Activity("Activity", Icons.Filled.Person),
    Alerts("Alerts", Icons.Filled.Warning),
    Diagnostics("Diagnostics", Icons.Filled.Info),
}

@Composable
fun ChildMonitorApp(viewModel: MonitorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AppTab.Monitor) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            AppTab.Monitor -> MonitorScreen(
                state = state,
                onOpenDevice = { tab = AppTab.Device },
                modifier = modifier,
            )
            AppTab.Device -> DeviceScreen(
                state = state,
                onMockModeChange = viewModel::setMockMode,
                onStartScan = viewModel::startScan,
                onStopScan = viewModel::stopScan,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
                modifier = modifier,
            )
            AppTab.Activity -> ActivityScreen(state = state, modifier = modifier)
            AppTab.Alerts -> AlertsScreen(
                state = state,
                onSettingsChange = viewModel::saveAlertSettings,
                modifier = modifier,
            )
            AppTab.Diagnostics -> DiagnosticsScreen(state = state, modifier = modifier)
        }
    }
}

fun ConnectionState.label(): String = when (this) {
    ConnectionState.Disconnected -> "Not connected"
    ConnectionState.Scanning -> "Scanning for ChildMonitor…"
    ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Reconnecting -> "Connection lost, reconnecting (attempt $attempt)…"
    ConnectionState.Connected -> "Connected"
    is ConnectionState.Error -> message
}
