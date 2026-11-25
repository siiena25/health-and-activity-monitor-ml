package ch.usi.childmonitor.domain

class RawPayload(
    val channel: Channel,
    val bytes: ByteArray,
    val receivedAtMillis: Long,
)

data class VitalSample(val value: Int?, val receivedAtMillis: Long)

data class VitalPoint(val value: Int, val receivedAtMillis: Long)

data class ActivitySample(val label: String?, val confidence: Float?, val receivedAtMillis: Long)

data class ActivityChange(val label: String, val sinceMillis: Long)

sealed interface PayloadOutcome {
    data class Accepted(val summary: String) : PayloadOutcome
    data object Unavailable : PayloadOutcome
    data class Rejected(val reason: String) : PayloadOutcome
}

data class PayloadLogEntry(
    val channel: Channel,
    val text: String,
    val hex: String,
    val outcome: PayloadOutcome,
    val receivedAtMillis: Long,
)

data class VitalsState(
    val bpm: VitalSample? = null,
    val spo2: VitalSample? = null,
    val bpmHistory: List<VitalPoint> = emptyList(),
    val spo2History: List<VitalPoint> = emptyList(),
    val activity: ActivitySample? = null,
    val activityTimeline: List<ActivityChange> = emptyList(),
    val log: List<PayloadLogEntry> = emptyList(),
) {

    fun sample(kind: VitalKind): VitalSample? = when (kind) {
        VitalKind.Bpm -> bpm
        VitalKind.Spo2 -> spo2
    }

    fun history(kind: VitalKind): List<VitalPoint> = when (kind) {
        VitalKind.Bpm -> bpmHistory
        VitalKind.Spo2 -> spo2History
    }

    fun withPayload(payload: RawPayload): VitalsState {
        val kind = payload.channel.vitalKind ?: return withActivity(payload)
        return withVital(kind, payload)
    }

    private fun withVital(kind: VitalKind, payload: RawPayload): VitalsState {
        val result = VitalParser.parse(kind, payload.bytes)
        val value = (result as? ParseResult.Reading)?.value
        val sample = VitalSample(value, payload.receivedAtMillis)
        val updatedHistory = if (value == null) {
            history(kind)
        } else {
            (history(kind) + VitalPoint(value, payload.receivedAtMillis)).takeLast(MAX_HISTORY)
        }
        val outcome = when (result) {
            is ParseResult.Reading -> PayloadOutcome.Accepted(result.value.toString())
            ParseResult.Unavailable -> PayloadOutcome.Unavailable
            is ParseResult.Rejected -> PayloadOutcome.Rejected(result.reason.description)
        }
        val updatedLog = logged(payload, outcome)
        return when (kind) {
            VitalKind.Bpm -> copy(bpm = sample, bpmHistory = updatedHistory, log = updatedLog)
            VitalKind.Spo2 -> copy(spo2 = sample, spo2History = updatedHistory, log = updatedLog)
        }
    }

    private fun withActivity(payload: RawPayload): VitalsState {
        val result = ActivityParser.parse(payload.bytes)
        val reading = result as? ActivityParseResult.Reading
        val sample = ActivitySample(reading?.label, reading?.confidence, payload.receivedAtMillis)
        val changed = reading != null && activityTimeline.firstOrNull()?.label != reading.label
        val timeline = if (changed) {
            (listOf(ActivityChange(reading.label, payload.receivedAtMillis)) + activityTimeline).take(MAX_TIMELINE)
        } else {
            activityTimeline
        }
        val outcome = when (result) {
            is ActivityParseResult.Reading -> PayloadOutcome.Accepted(
                result.confidence?.let { "${result.label} (${"%.0f".format(it * PERCENT)}%)" } ?: result.label,
            )
            ActivityParseResult.Unavailable -> PayloadOutcome.Unavailable
            is ActivityParseResult.Rejected -> PayloadOutcome.Rejected(result.reason)
        }
        return copy(activity = sample, activityTimeline = timeline, log = logged(payload, outcome))
    }

    private fun logged(payload: RawPayload, outcome: PayloadOutcome): List<PayloadLogEntry> {
        val entry = PayloadLogEntry(
            channel = payload.channel,
            text = payload.bytes.toPrintableText(),
            hex = payload.bytes.toHex(),
            outcome = outcome,
            receivedAtMillis = payload.receivedAtMillis,
        )
        return (listOf(entry) + log).take(MAX_LOG)
    }

    companion object {
        const val MAX_HISTORY = 120
        const val MAX_LOG = 100
        const val MAX_TIMELINE = 30
        private const val PERCENT = 100
    }
}

private fun ByteArray.toHex(): String = joinToString("-") { "%02X".format(it) }

private fun ByteArray.toPrintableText(): String =
    String(this, Charsets.UTF_8).map { if (it.isISOControl()) '·' else it }.joinToString("")
