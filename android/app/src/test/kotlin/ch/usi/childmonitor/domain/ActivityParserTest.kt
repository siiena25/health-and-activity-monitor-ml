package ch.usi.childmonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityParserTest {

    private fun parse(text: String) = ActivityParser.parse(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `parses label with confidence`() {
        assertEquals(ActivityParseResult.Reading("walking", 0.91f), parse("walking|0.91"))
    }

    @Test
    fun `parses label without confidence`() {
        assertEquals(ActivityParseResult.Reading("running", null), parse("running"))
    }

    @Test
    fun `confidence boundaries are accepted`() {
        assertEquals(ActivityParseResult.Reading("sitting", 0f), parse("sitting|0"))
        assertEquals(ActivityParseResult.Reading("sitting", 1f), parse("sitting|1.00"))
    }

    @Test
    fun `label is normalised to lower case and trimmed`() {
        assertEquals(ActivityParseResult.Reading("standing", 0.5f), parse(" Standing | 0.50 \n"))
    }

    @Test
    fun `trailing nul bytes are ignored`() {
        val bytes = "walking|0.80".toByteArray() + byteArrayOf(0, 0)
        assertEquals(ActivityParseResult.Reading("walking", 0.8f), ActivityParser.parse(bytes))
    }

    @Test
    fun `unknown labels are passed through for forward compatibility`() {
        assertEquals(ActivityParseResult.Reading("climbing_stairs", 0.7f), parse("climbing_stairs|0.7"))
    }

    @Test
    fun `double dash and blank mean unavailable`() {
        assertEquals(ActivityParseResult.Unavailable, parse("--"))
        assertEquals(ActivityParseResult.Unavailable, parse(""))
        assertEquals(ActivityParseResult.Unavailable, ActivityParser.parse(ByteArray(0)))
    }

    @Test
    fun `confidence outside zero to one is rejected`() {
        assertTrue(parse("walking|1.7") is ActivityParseResult.Rejected)
        assertTrue(parse("walking|-0.2") is ActivityParseResult.Rejected)
    }

    @Test
    fun `non numeric confidence is rejected`() {
        assertTrue(parse("walking|high") is ActivityParseResult.Rejected)
        assertTrue(parse("walking|NaN") is ActivityParseResult.Rejected)
        assertTrue(parse("walking|1e0") is ActivityParseResult.Rejected)
        assertTrue(parse("walking|") is ActivityParseResult.Rejected)
    }

    @Test
    fun `bad labels are rejected`() {
        assertTrue(parse("|0.9") is ActivityParseResult.Rejected)
        assertTrue(parse("wal king|0.9") is ActivityParseResult.Rejected)
        assertTrue(parse("9lives|0.9") is ActivityParseResult.Rejected)
        assertTrue(parse("a".repeat(25) + "|0.9") is ActivityParseResult.Rejected)
    }

    @Test
    fun `extra fields are rejected`() {
        assertTrue(parse("walking|0.9|x") is ActivityParseResult.Rejected)
    }

    @Test
    fun `a numeric vital payload is not an activity`() {
        assertTrue(parse("112932") is ActivityParseResult.Rejected)
    }

    @Test
    fun `invalid utf8 is rejected`() {
        val result = ActivityParser.parse(byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue(result is ActivityParseResult.Rejected)
    }
}
