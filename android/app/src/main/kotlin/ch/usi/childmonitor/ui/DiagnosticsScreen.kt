package ch.usi.childmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.usi.childmonitor.ble.BleContract
import ch.usi.childmonitor.domain.Channel
import ch.usi.childmonitor.domain.PayloadLogEntry
import ch.usi.childmonitor.domain.PayloadOutcome
import ch.usi.childmonitor.domain.ageLabel

@Composable
fun DiagnosticsScreen(state: MonitorUiState, modifier: Modifier = Modifier) {
    val diagnostics = state.diagnostics
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Section(title = "Connection", modifier = Modifier.padding(top = 16.dp)) {
                Line("State", state.connection.label())
                Line("Source", if (state.useMock) "Simulated" else "Bluetooth LE")
                Line("Bluetooth", if (state.bluetoothEnabled) "on" else "off")
            }
        }
        item {
            Section(title = "Expected GATT contract") {
                Line("Device name", BleContract.DEVICE_NAME)
                Line("Service", BleContract.SERVICE_UUID.toString(), diagnostics.serviceFound.presence(required = true))
                Channel.entries.forEach { channel ->
                    val present = diagnostics.serviceFound?.let { found -> found && channel !in diagnostics.missing }
                    val label = if (channel.required) channel.title else "${channel.title} (optional)"
                    Line(label, BleContract.uuidFor(channel).toString(), present.presence(channel.required))
                }
            }
        }
        item {
            Section(title = "Discovered services") {
                if (diagnostics.services.isEmpty()) {
                    Text("Nothing discovered yet", style = MaterialTheme.typography.bodySmall)
                }
                diagnostics.services.forEach { service ->
                    Text(service.uuid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    service.characteristics.forEach {
                        Text(
                            text = "  $it",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        item { Header("Latest notifications") }
        if (state.vitals.log.isEmpty()) {
            item { Text("No payloads received", style = MaterialTheme.typography.bodySmall) }
        }
        items(state.vitals.log) { entry -> PayloadRow(entry = entry, nowMillis = state.nowMillis) }
        item { Header("Link events") }
        if (diagnostics.events.isEmpty()) {
            item { Text("No events", style = MaterialTheme.typography.bodySmall) }
        }
        items(diagnostics.events) { event ->
            Text(
                text = "${ageLabel(state.nowMillis - event.atMillis)} · ${event.message}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        item { Header("") }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Header(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Line(label: String, value: String, suffix: String? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = if (suffix == null) value else "$value · $suffix",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PayloadRow(entry: PayloadLogEntry, nowMillis: Long) {
    Column {
        Text(
            text = "${entry.channel.title} · \"${entry.text}\" · ${entry.hex}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "${entry.outcome.describe()} · ${ageLabel(nowMillis - entry.receivedAtMillis)}",
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.outcome is PayloadOutcome.Rejected) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun Boolean?.presence(required: Boolean): String = when (this) {
    true -> "found"
    false -> if (required) "MISSING" else "not present"
    null -> "not checked"
}

private fun PayloadOutcome.describe(): String = when (this) {
    is PayloadOutcome.Accepted -> "accepted: $summary"
    PayloadOutcome.Unavailable -> "unavailable (--)"
    is PayloadOutcome.Rejected -> "rejected: $reason"
}
