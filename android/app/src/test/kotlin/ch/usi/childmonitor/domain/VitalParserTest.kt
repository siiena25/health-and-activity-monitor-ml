package ch.usi.childmonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class VitalParserTest {

    private fun parse(kind: VitalKind, text: String) = VitalParser.parse(kind, text.toByteArray(Charsets.UTF_8))

    @Test
    fun `parses plain bpm`() {
        assertEquals(ParseResult.Reading(78), parse(VitalKind.Bpm, "78"))
    }

    @Test
    fun `parses plain spo2`() {
        assertEquals(ParseResult.Reading(97), parse(VitalKind.Spo2, "97"))
    }

    @Test
    fun `ascii bytes from the nRF capture decode to the number`() {
        val bytes = byteArrayOf(0x38, 0x32)
        assertEquals(ParseResult.Reading(82), VitalParser.parse(VitalKind.Bpm, bytes))
    }

    @Test
    fun `double dash means unavailable`() {
        assertEquals(ParseResult.Unavailable, parse(VitalKind.Bpm, "--"))
    }

    @Test
    fun `blank payload means unavailable`() {
        assertEquals(ParseResult.Unavailable, parse(VitalKind.Spo2, "  "))
        assertEquals(ParseResult.Unavailable, VitalParser.parse(VitalKind.Spo2, ByteArray(0)))
    }

    @Test
    fun `trailing nul and whitespace are ignored`() {
        assertEquals(ParseResult.Reading(75), VitalParser.parse(VitalKind.Bpm, byteArrayOf(0x37, 0x35, 0x00, 0x00)))
        assertEquals(ParseResult.Reading(75), parse(VitalKind.Bpm, " 75\n"))
    }

    @Test
    fun `value from the first BLE capture is out of range for bpm`() {
        assertEquals(ParseResult.Rejected(RejectReason.OutOfRange), parse(VitalKind.Bpm, "112932"))
    }

    @Test
    fun `range boundaries are inclusive`() {
        assertEquals(ParseResult.Reading(30), parse(VitalKind.Bpm, "30"))
        assertEquals(ParseResult.Reading(220), parse(VitalKind.Bpm, "220"))
        assertEquals(ParseResult.Rejected(RejectReason.OutOfRange), parse(VitalKind.Bpm, "29"))
        assertEquals(ParseResult.Rejected(RejectReason.OutOfRange), parse(VitalKind.Bpm, "221"))
        assertEquals(ParseResult.Reading(70), parse(VitalKind.Spo2, "70"))
        assertEquals(ParseResult.Reading(100), parse(VitalKind.Spo2, "100"))
        assertEquals(ParseResult.Rejected(RejectReason.OutOfRange), parse(VitalKind.Spo2, "101"))
    }

    @Test
    fun `algorithm garbage spo2 values are rejected not shown as zero`() {
        assertEquals(ParseResult.Rejected(RejectReason.OutOfRange), parse(VitalKind.Spo2, "0"))
        assertEquals(ParseResult.Rejected(RejectReason.OutOfRange), parse(VitalKind.Spo2, "10"))
        assertEquals(ParseResult.Rejected(RejectReason.OutOfRange), parse(VitalKind.Spo2, "-999"))
    }

    @Test
    fun `non numeric text is rejected`() {
        assertEquals(ParseResult.Rejected(RejectReason.NotANumber), parse(VitalKind.Bpm, "abc"))
        assertEquals(ParseResult.Rejected(RejectReason.NotANumber), parse(VitalKind.Bpm, "78.5"))
        assertEquals(ParseResult.Rejected(RejectReason.NotANumber), parse(VitalKind.Bpm, "+78"))
        assertEquals(ParseResult.Rejected(RejectReason.NotANumber), parse(VitalKind.Bpm, "-"))
        assertEquals(ParseResult.Rejected(RejectReason.NotANumber), parse(VitalKind.Bpm, "٧٨"))
    }

    @Test
    fun `integer overflow is rejected as out of range`() {
        assertEquals(ParseResult.Rejected(RejectReason.OutOfRange), parse(VitalKind.Bpm, "99999999999"))
    }

    @Test
    fun `invalid utf8 is rejected`() {
        val result = VitalParser.parse(VitalKind.Bpm, byteArrayOf(0xC3.toByte(), 0x28))
        assertEquals(ParseResult.Rejected(RejectReason.NotUtf8), result)
    }
}
