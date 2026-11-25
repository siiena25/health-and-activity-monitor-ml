package ch.usi.childmonitor.domain

sealed interface ParseResult {
    data class Reading(val value: Int) : ParseResult
    data object Unavailable : ParseResult
    data class Rejected(val reason: RejectReason) : ParseResult
}

enum class RejectReason(val description: String) {
    NotUtf8("payload is not valid UTF-8"),
    NotANumber("payload is not a decimal integer"),
    OutOfRange("value is outside the plausible range"),
}

object VitalParser {

    fun parse(kind: VitalKind, bytes: ByteArray): ParseResult {
        val text = decodePayload(bytes) ?: return ParseResult.Rejected(RejectReason.NotUtf8)
        if (text.isEmpty() || text == UNAVAILABLE_MARKER) return ParseResult.Unavailable
        val digits = text.removePrefix("-")
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) {
            return ParseResult.Rejected(RejectReason.NotANumber)
        }
        val value = text.toIntOrNull() ?: return ParseResult.Rejected(RejectReason.OutOfRange)
        return if (value in kind.validRange) {
            ParseResult.Reading(value)
        } else {
            ParseResult.Rejected(RejectReason.OutOfRange)
        }
    }
}
