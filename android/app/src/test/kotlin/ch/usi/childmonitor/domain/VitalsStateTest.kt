package ch.usi.childmonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalsStateTest {

    private fun payload(kind: VitalKind, text: String, at: Long) =
        RawPayload(kind.channel, text.toByteArray(Charsets.UTF_8), at)

    @Test
    fun `valid bpm updates sample and history`() {
        val state = VitalsState().withPayload(payload(VitalKind.Bpm, "80", 1_000))

        assertEquals(VitalSample(80, 1_000), state.bpm)
        assertEquals(listOf(VitalPoint(80, 1_000)), state.bpmHistory)
        assertNull(state.spo2)
    }

    @Test
    fun `unavailable marker replaces the sample with null but keeps history`() {
        val state = VitalsState()
            .withPayload(payload(VitalKind.Bpm, "80", 1_000))
            .withPayload(payload(VitalKind.Bpm, "--", 2_000))

        assertEquals(VitalSample(null, 2_000), state.bpm)
        assertEquals(listOf(VitalPoint(80, 1_000)), state.bpmHistory)
    }

    @Test
    fun `rejected payload never becomes a reading`() {
        val state = VitalsState().withPayload(payload(VitalKind.Spo2, "10", 1_000))

        assertEquals(VitalSample(null, 1_000), state.spo2)
        assertTrue(state.spo2History.isEmpty())
        assertTrue(state.log.single().outcome is PayloadOutcome.Rejected)
    }

    @Test
    fun `kinds are tracked independently`() {
        val state = VitalsState()
            .withPayload(payload(VitalKind.Bpm, "80", 1_000))
            .withPayload(payload(VitalKind.Spo2, "97", 1_500))

        assertEquals(80, state.bpm?.value)
        assertEquals(97, state.spo2?.value)
    }

    @Test
    fun `history and log are capped`() {
        var state = VitalsState()
        repeat(VitalsState.MAX_HISTORY + 30) { state = state.withPayload(payload(VitalKind.Bpm, "80", it.toLong())) }

        assertEquals(VitalsState.MAX_HISTORY, state.bpmHistory.size)
        assertEquals(VitalsState.MAX_LOG, state.log.size)
        assertEquals((VitalsState.MAX_HISTORY + 29).toLong(), state.log.first().receivedAtMillis)
    }

    @Test
    fun `log keeps raw text and hex`() {
        val entry = VitalsState().withPayload(payload(VitalKind.Bpm, "82", 1_000)).log.single()

        assertEquals("82", entry.text)
        assertEquals("38-32", entry.hex)
    }

    @Test
    fun `status is waiting before any payload`() {
        assertEquals(VitalStatus.WaitingForData, (null as VitalSample?).statusAt(0, connected = true))
    }

    @Test
    fun `status is unavailable for a null value`() {
        assertEquals(VitalStatus.Unavailable, VitalSample(null, 0).statusAt(100, connected = true))
    }

    @Test
    fun `status is live while fresh and connected`() {
        assertEquals(VitalStatus.Live, VitalSample(80, 0).statusAt(STALE_AFTER_MILLIS, connected = true))
    }

    @Test
    fun `status becomes stale after the threshold`() {
        assertEquals(VitalStatus.Stale, VitalSample(80, 0).statusAt(STALE_AFTER_MILLIS + 1, connected = true))
    }

    @Test
    fun `status is stale when disconnected even if recent`() {
        assertEquals(VitalStatus.Stale, VitalSample(80, 0).statusAt(10, connected = false))
    }

    @Test
    fun `age label formats seconds minutes and hours`() {
        assertEquals("just now", ageLabel(400))
        assertEquals("12 s ago", ageLabel(12_000))
        assertEquals("2 min ago", ageLabel(125_000))
        assertEquals("1 h ago", ageLabel(3_700_000))
        assertEquals("just now", ageLabel(-5_000))
    }
}
