package ch.usi.childmonitor.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ch.usi.childmonitor.domain.AlertRecord
import ch.usi.childmonitor.domain.AlertSettings
import ch.usi.childmonitor.domain.AlertText
import ch.usi.childmonitor.domain.ageLabel

private const val BPM_STEP = 5

@Composable
fun AlertsScreen(
    state: MonitorUiState,
    onSettingsChange: (AlertSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.alertSettings
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Heart rate warnings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = settings.enabled, onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) })
            }
        }
        NotificationPermissionCard()
        LimitsCard(settings = settings, onSettingsChange = onSettingsChange)
        HistoryCard(records = state.alerts.history, nowMillis = state.nowMillis)
        Text(
            text = "These limits are not medical advice: normal heart rate depends on the child's age and health, " +
                "so ask a doctor. A warning needs about 10 seconds of consistent readings. Monitoring works while " +
                "the app is running; Android may stop it in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotificationPermissionCard() {
    val context = LocalContext.current
    fun enabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    var notificationsEnabled by remember { mutableStateOf(enabled()) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = enabled()
        denied = !notificationsEnabled
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsEnabled = enabled() }
    if (notificationsEnabled) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notifications are off", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Without them warnings are only visible inside the app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !denied) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                },
            ) {
                Text("Allow notifications")
            }
        }
    }
}

@Composable
private fun LimitsCard(settings: AlertSettings, onSettingsChange: (AlertSettings) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Limits", style = MaterialTheme.typography.titleMedium)
            Stepper("Low pulse: below", "BPM", settings.lowBpm, AlertSettings.LOW_RANGE, BPM_STEP) {
                onSettingsChange(settings.copy(lowBpm = it))
            }
            Stepper("High at rest: above", "BPM", settings.restingHighBpm, AlertSettings.RESTING_HIGH_RANGE, BPM_STEP) {
                onSettingsChange(settings.copy(restingHighBpm = it))
            }
            Stepper("High with activity: above", "BPM", settings.activeHighBpm, AlertSettings.ACTIVE_HIGH_RANGE, BPM_STEP) {
                onSettingsChange(settings.copy(activeHighBpm = it))
            }
            Stepper("Long high pulse: above", "BPM", settings.prolongedBpm, AlertSettings.PROLONGED_RANGE, BPM_STEP) {
                onSettingsChange(settings.copy(prolongedBpm = it))
            }
            Stepper("Long means at least", "min", settings.prolongedMinutes, AlertSettings.PROLONGED_MINUTES_RANGE, 1) {
                onSettingsChange(settings.copy(prolongedMinutes = it))
            }
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    unit: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) {
            Text("−")
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp),
        )
        OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) {
            Text("+")
        }
    }
}

@Composable
private fun HistoryCard(records: List<AlertRecord>, nowMillis: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("History", style = MaterialTheme.typography.titleMedium)
            if (records.isEmpty()) {
                Text("No warnings since the app was started", style = MaterialTheme.typography.bodyMedium)
            }
            records.forEach { record ->
                Column {
                    Text(
                        text = "${AlertText.title(record.type)} · ${record.bpm} BPM (limit ${record.limit})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val ended = record.endedAtMillis
                    Text(
                        text = if (ended == null) {
                            "Started ${ageLabel(nowMillis - record.raisedAtMillis)}, ongoing"
                        } else {
                            "Ended ${ageLabel(nowMillis - ended)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
