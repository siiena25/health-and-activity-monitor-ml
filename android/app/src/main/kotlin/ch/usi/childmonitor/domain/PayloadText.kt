package ch.usi.childmonitor.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val NUL = '\u0000'

internal const val UNAVAILABLE_MARKER = "--"

internal fun decodePayload(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .trim { it.isWhitespace() || it == NUL }
} catch (_: CharacterCodingException) {
    null
}
