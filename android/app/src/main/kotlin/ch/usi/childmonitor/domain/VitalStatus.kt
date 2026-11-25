package ch.usi.childmonitor.domain

enum class VitalStatus { WaitingForData, Unavailable, Live, Stale }

const val STALE_AFTER_MILLIS = 10_000L

fun VitalSample?.statusAt(nowMillis: Long, connected: Boolean): VitalStatus = when {
    this == null -> VitalStatus.WaitingForData
    value == null -> VitalStatus.Unavailable
    !connected || nowMillis - receivedAtMillis > STALE_AFTER_MILLIS -> VitalStatus.Stale
    else -> VitalStatus.Live
}

fun ageLabel(ageMillis: Long): String {
    val seconds = (ageMillis / 1_000).coerceAtLeast(0)
    return when {
        seconds < 1 -> "just now"
        seconds < 60 -> "$seconds s ago"
        seconds < 3_600 -> "${seconds / 60} min ago"
        else -> "${seconds / 3_600} h ago"
    }
}
