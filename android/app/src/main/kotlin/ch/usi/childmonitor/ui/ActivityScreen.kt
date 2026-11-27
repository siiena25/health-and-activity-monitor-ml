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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.usi.childmonitor.domain.ActivityLabels
import ch.usi.childmonitor.domain.Channel
import ch.usi.childmonitor.domain.VitalStatus
import ch.usi.childmonitor.domain.activityDisplay
import ch.usi.childmonitor.domain.ageLabel

private const val TIMELINE_SHOWN = 10
private const val PERCENT = 100

@Composable
fun ActivityScreen(state: MonitorUiState, modifier: Modifier = Modifier) {
    val sample = state.vitals.activity
    val display = activityDisplay(sample, state.nowMillis, state.connected)
    val unsupported = state.diagnostics.serviceFound == true && Channel.Activity in state.diagnostics.missing
    val live = display.status == VitalStatus.Live

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Activity", style = MaterialTheme.typography.titleMedium)
                if (unsupported) {
                    Text("Not sent by this firmware", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "The device does not expose the activity characteristic yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = display.headline,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (live) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = display.caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (live) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (display.status == VitalStatus.Unavailable) {
                        Text(
                            text = "Normal for the first few seconds after the wearable starts. " +
                                "If it stays, check the motion sensor connection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    sample?.confidence?.let { confidence ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinearProgressIndicator(progress = { confidence }, modifier = Modifier.weight(1f))
                            Text("${(confidence * PERCENT).toInt()}%", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (display.lowConfidence) {
                        Text(
                            text = "Low confidence: the result may be wrong.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        if (state.vitals.activityTimeline.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Recent changes", style = MaterialTheme.typography.titleMedium)
                    state.vitals.activityTimeline.take(TIMELINE_SHOWN).forEach { change ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = ActivityLabels.display(change.label),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = ageLabel(state.nowMillis - change.sinceMillis),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = "Recognised on the wearable from wrist motion. The model was trained on recordings of three adults " +
                "and not tested on children, so treat the result as an indication, not a certainty.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
