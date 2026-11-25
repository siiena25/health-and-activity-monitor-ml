package ch.usi.childmonitor.domain

object AlertText {

    const val ADVICE = "Check on your child. The sensor can be wrong."

    fun title(type: AlertType): String = when (type) {
        AlertType.LowHeartRate -> "Low heart rate"
        AlertType.HighHeartRate -> "High heart rate"
        AlertType.ProlongedHighHeartRate -> "Heart rate high for a long time"
    }

    fun message(alert: ActiveAlert, nowMillis: Long): String {
        val duration = duration(nowMillis - alert.sinceMillis)
        val context = if (alert.duringActivity) "during or after activity" else "at rest"
        return when (alert.type) {
            AlertType.LowHeartRate -> "${alert.bpm} BPM, below ${alert.limit} for $duration"
            AlertType.HighHeartRate -> "${alert.bpm} BPM, above ${alert.limit} for $duration, $context"
            AlertType.ProlongedHighHeartRate ->
                "${alert.bpm} BPM, above ${alert.limit} for $duration while moving. Time for a break?"
        }
    }

    fun duration(millis: Long): String {
        val seconds = (millis / 1_000).coerceAtLeast(0)
        return if (seconds < 60) "$seconds s" else "${seconds / 60} min"
    }
}
