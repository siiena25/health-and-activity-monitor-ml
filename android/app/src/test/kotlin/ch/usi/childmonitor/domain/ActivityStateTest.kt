package ch.usi.childmonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityStateTest {

    private fun activity(text: String, at: Long) =
        RawPayload(Channel.Activity, text.toByteArray(Charsets.UTF_8), at)

    @Test
    fun `valid payload sets the sample and starts the timeline`() {
        val state = VitalsState().withPayload(activity("walking|0.9", 1_000))

        assertEquals(ActivitySample("walking", 0.9f, 1_000), state.activity)
        assertEquals(listOf(ActivityChange("walking", 1_000)), state.activityTimeline)
    }

    @Test
    fun `timeline only grows when the label changes`() {
        val state = VitalsState()
            .withPayload(activity("walking|0.9", 1_000))
            .withPayload(activity("walking|0.8", 2_500))
            .withPayload(activity("running|0.7", 4_000))
            .withPayload(activity("running|0.9", 5_500))

        assertEquals(
            listOf(ActivityChange("running", 4_000), ActivityChange("walking", 1_000)),
            state.activityTimeline,
        )
        assertEquals("running", state.activity?.label)
    }

    @Test
    fun `unavailable marker clears the label but keeps the timeline`() {
        val state = VitalsState()
            .withPayload(activity("walking|0.9", 1_000))
            .withPayload(activity("--", 2_500))

        assertNull(state.activity?.label)
        assertEquals(2_500L, state.activity?.receivedAtMillis)
        assertEquals(1, state.activityTimeline.size)
    }

    @Test
    fun `rejected payload is logged as rejected and never becomes a label`() {
        val state = VitalsState().withPayload(activity("walking|1.7", 1_000))

        assertNull(state.activity?.label)
        assertTrue(state.activityTimeline.isEmpty())
        assertTrue(state.log.single().outcome is PayloadOutcome.Rejected)
    }

    @Test
    fun `activity does not touch the vitals`() {
        val state = VitalsState().withPayload(activity("walking|0.9", 1_000))

        assertNull(state.bpm)
        assertNull(state.spo2)
    }

    @Test
    fun `timeline is capped`() {
        var state = VitalsState()
        repeat(VitalsState.MAX_TIMELINE + 10) {
            state = state.withPayload(activity(if (it % 2 == 0) "walking|0.9" else "running|0.9", it.toLong()))
        }

        assertEquals(VitalsState.MAX_TIMELINE, state.activityTimeline.size)
    }

    @Test
    fun `log shows the accepted summary with percent`() {
        val entry = VitalsState().withPayload(activity("walking|0.91", 1_000)).log.single()

        assertEquals(PayloadOutcome.Accepted("walking (91%)"), entry.outcome)
        assertEquals(Channel.Activity, entry.channel)
    }

    @Test
    fun `display is waiting before the first result`() {
        assertEquals(VitalStatus.WaitingForData, activityDisplay(null, 0, connected = true).status)
        assertEquals("Not connected", activityDisplay(null, 0, connected = false).caption)
    }

    @Test
    fun `display is live when fresh and connected`() {
        val display = activityDisplay(ActivitySample("walking", 0.9f, 0), 1_000, connected = true)

        assertEquals("Walking", display.headline)
        assertEquals(VitalStatus.Live, display.status)
        assertFalse(display.lowConfidence)
    }

    @Test
    fun `display is stale after the threshold or when disconnected`() {
        val sample = ActivitySample("walking", 0.9f, 0)

        assertEquals(VitalStatus.Stale, activityDisplay(sample, STALE_AFTER_MILLIS + 1, connected = true).status)
        assertEquals(VitalStatus.Stale, activityDisplay(sample, 100, connected = false).status)
    }

    @Test
    fun `display flags low confidence`() {
        assertTrue(activityDisplay(ActivitySample("running", 0.4f, 0), 100, connected = true).lowConfidence)
        assertFalse(activityDisplay(ActivitySample("running", LOW_CONFIDENCE, 0), 100, connected = true).lowConfidence)
        assertFalse(activityDisplay(ActivitySample("running", null, 0), 100, connected = true).lowConfidence)
    }

    @Test
    fun `display shows unknown for the no result marker`() {
        val state = VitalsState().withPayload(activity("--", 0))

        val display = activityDisplay(state.activity, 1_000, connected = true)

        assertEquals("Unknown", display.headline)
        assertEquals(VitalStatus.Unavailable, display.status)
    }

    @Test
    fun `every firmware label is accepted without confidence`() {
        listOf("sitting", "standing", "walking", "running").forEach { label ->
            val state = VitalsState().withPayload(activity(label, 1_000))

            assertEquals(ActivitySample(label, null, 1_000), state.activity)
        }
    }

    @Test
    fun `display names are readable`() {
        assertEquals("Walking", ActivityLabels.display("walking"))
        assertEquals("Climbing stairs", ActivityLabels.display("climbing_stairs"))
    }
}
