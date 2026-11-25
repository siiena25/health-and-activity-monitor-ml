package ch.usi.childmonitor.domain

sealed interface ActivityParseResult {
    data class Reading(val label: String, val confidence: Float?) : ActivityParseResult
    data object Unavailable : ActivityParseResult
    data class Rejected(val reason: String) : ActivityParseResult
}

object ActivityParser {

    private const val FIELD_SEPARATOR = '|'
    private val LABEL = Regex("^[a-z][a-z0-9_]{0,23}$")
    private val CONFIDENCE = Regex("^\\d+(\\.\\d+)?$")

    fun parse(bytes: ByteArray): ActivityParseResult {
        val text = decodePayload(bytes) ?: return ActivityParseResult.Rejected("payload is not valid UTF-8")
        if (text.isEmpty() || text == UNAVAILABLE_MARKER) return ActivityParseResult.Unavailable
        val fields = text.split(FIELD_SEPARATOR)
        if (fields.size > 2) return ActivityParseResult.Rejected("more than two fields")
        val label = fields[0].trim().lowercase()
        if (!LABEL.matches(label)) return ActivityParseResult.Rejected("label is not a lowercase identifier")
        if (fields.size == 1) return ActivityParseResult.Reading(label, confidence = null)
        val confidence = parseConfidence(fields[1].trim())
            ?: return ActivityParseResult.Rejected("confidence is not a number from 0 to 1")
        return ActivityParseResult.Reading(label, confidence)
    }

    private fun parseConfidence(text: String): Float? {
        if (!CONFIDENCE.matches(text)) return null
        return text.toFloatOrNull()?.takeIf { it in 0f..1f }
    }
}
