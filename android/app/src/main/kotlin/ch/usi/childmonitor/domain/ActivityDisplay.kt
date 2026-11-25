package ch.usi.childmonitor.domain

const val LOW_CONFIDENCE = 0.6f

object ActivityLabels {
    fun display(label: String): String =
        label.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

data class ActivityDisplay(
    val headline: String,
    val caption: String,
    val status: VitalStatus,
    val lowConfidence: Boolean,
)

fun ActivitySample?.statusAt(nowMillis: Long, connected: Boolean): VitalStatus = when {
    this == null -> VitalStatus.WaitingForData
    label == null -> VitalStatus.Unavailable
    !connected || nowMillis - receivedAtMillis > STALE_AFTER_MILLIS -> VitalStatus.Stale
    else -> VitalStatus.Live
}

fun activityDisplay(sample: ActivitySample?, nowMillis: Long, connected: Boolean): ActivityDisplay {
    val status = sample.statusAt(nowMillis, connected)
    val age = sample?.let { ageLabel(nowMillis - it.receivedAtMillis) }.orEmpty()
    val caption = when (status) {
        VitalStatus.WaitingForData -> if (connected) "Waiting for the first result" else "Not connected"
        VitalStatus.Unavailable -> "No result yet · $age"
        VitalStatus.Live -> "Live · $age"
        VitalStatus.Stale -> if (connected) "Stale · last result $age" else "Disconnected · last result $age"
    }
    return ActivityDisplay(
        headline = when {
            sample == null -> "--"
            else -> sample.label?.let(ActivityLabels::display) ?: "Unknown"
        },
        caption = caption,
        status = status,
        lowConfidence = sample?.confidence?.let { it < LOW_CONFIDENCE } == true,
    )
}
