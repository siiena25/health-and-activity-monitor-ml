package ch.usi.childmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.usi.childmonitor.ble.ConnectionState
import ch.usi.childmonitor.domain.ActiveAlert
import ch.usi.childmonitor.domain.AlertText
import ch.usi.childmonitor.domain.Channel
import ch.usi.childmonitor.domain.VitalKind
import ch.usi.childmonitor.domain.VitalStatus
import ch.usi.childmonitor.domain.activityDisplay
import ch.usi.childmonitor.domain.ageLabel
import ch.usi.childmonitor.domain.statusAt

private const val SPARKLINE_POINTS = 60

@Composable
fun MonitorScreen(
    state: MonitorUiState,
    onOpenDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionBanner(state = state, onOpenDevice = onOpenDevice)
        state.alerts.active.values.forEach { alert -> AlertBanner(alert = alert, nowMillis = state.nowMillis) }
        VitalCard(kind = VitalKind.Bpm, state = state, note = null)
        VitalCard(
            kind = VitalKind.Spo2,
            state = state,
            note = "Experimental sensor reading, not medically validated.",
        )
        ActivityRow(state = state)
        Text(
            text = "Prototype wearable. These values are not a medical measurement. Heart rate warnings are only a " +
                "hint to check on your child and may be wrong or missed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionBanner(state: MonitorUiState, onOpenDevice: () -> Unit) {
    val connection = state.connection
    val containerColor = when {
        state.useMock || connection is ConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
        connection is ConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (state.useMock) {
                    Text("DEMO MODE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "All values are made up by the app, not measured by the sensor.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(connection.label(), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (state.useMock || !state.connected) {
                TextButton(onClick = onOpenDevice) { Text(if (state.useMock) "Turn off" else "Devices") }
            }
        }
    }
}

@Composable
private fun VitalCard(kind: VitalKind, state: MonitorUiState, note: String?) {
    val vitals = state.vitals
    val sample = vitals.sample(kind)
    val status = sample.statusAt(state.nowMillis, state.connected)
    val emphasised = status == VitalStatus.Live
    val valueColor = if (emphasised) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(kind.title, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = sample?.value?.toString() ?: "--",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                )
                Text(
                    text = kind.unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Text(
                text = statusText(status, sample?.receivedAtMillis?.let { state.nowMillis - it }, state.connected),
                style = MaterialTheme.typography.bodyMedium,
                color = if (emphasised) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Sparkline(
                points = vitals.history(kind).takeLast(SPARKLINE_POINTS),
                color = if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AlertBanner(alert: ActiveAlert, nowMillis: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = AlertText.title(alert.type),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(AlertText.message(alert, nowMillis), style = MaterialTheme.typography.bodyLarge)
            Text(AlertText.ADVICE, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActivityRow(state: MonitorUiState) {
    val display = activityDisplay(state.vitals.activity, state.nowMillis, state.connected)
    val unsupported = state.diagnostics.serviceFound == true && Channel.Activity in state.diagnostics.missing
    val live = display.status == VitalStatus.Live
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Activity", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (unsupported) "Not sent by this firmware" else display.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!unsupported) {
                Text(
                    text = display.headline,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (live) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusText(status: VitalStatus, ageMillis: Long?, connected: Boolean): String {
    val age = ageMillis?.let(::ageLabel).orEmpty()
    return when (status) {
        VitalStatus.WaitingForData -> "Waiting for data"
        VitalStatus.Unavailable -> "No valid reading · $age"
        VitalStatus.Live -> "Live · $age"
        VitalStatus.Stale -> if (connected) "Stale · last value $age" else "Disconnected · last value $age"
    }
}
