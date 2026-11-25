package ch.usi.childmonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertStateTest {

    private val settings = AlertSettings()

    private fun AlertState.feed(
        bpm: Int,
        seconds: IntRange,
        activity: String? = "sitting",
        with: AlertSettings = settings,
    ): AlertState = seconds.fold(this) { state, second ->
        state.onHeartRate(bpm, activity, second * 1_000L, with)
    }

    private val AlertState.types get() = active.keys

    @Test
    fun `normal pulse raises nothing`() {
        assertTrue(AlertState().feed(80, 0..600).types.isEmpty())
    }

    @Test
    fun `low pulse is raised only after ten seconds`() {
        val early = AlertState().feed(50, 0..9)
        val later = early.feed(50, 10..10)

        assertTrue(early.types.isEmpty())
        assertEquals(setOf(AlertType.LowHeartRate), later.types)
        assertEquals(60, later.active.getValue(AlertType.LowHeartRate).limit)
    }

    @Test
    fun `a single spike is ignored`() {
        val state = AlertState().feed(80, 0..5).feed(214, 6..6).feed(80, 7..30)

        assertTrue(state.types.isEmpty())
    }

    @Test
    fun `two spikes far apart are not a sustained pulse`() {
        val state = AlertState()
            .onHeartRate(200, "sitting", 0, settings)
            .onHeartRate(200, "sitting", 10_000, settings)

        assertTrue(state.types.isEmpty())
    }

    @Test
    fun `alternating noisy readings do not raise`() {
        var state = AlertState()
        for (second in 0..40) {
            state = state.onHeartRate(if (second % 2 == 0) 160 else 80, "sitting", second * 1_000L, settings)
        }

        assertTrue(state.types.isEmpty())
    }

    @Test
    fun `high pulse at rest uses the resting limit`() {
        val state = AlertState().feed(140, 0..12)

        assertEquals(setOf(AlertType.HighHeartRate), state.types)
        assertEquals(130, state.active.getValue(AlertType.HighHeartRate).limit)
    }

    @Test
    fun `unknown activity is treated as rest`() {
        assertEquals(setOf(AlertType.HighHeartRate), AlertState().feed(140, 0..12, activity = null).types)
    }

    @Test
    fun `the same pulse while running is not high`() {
        assertTrue(AlertState().feed(140, 0..30, activity = "running").types.isEmpty())
    }

    @Test
    fun `very high pulse while running uses the active limit`() {
        val state = AlertState().feed(180, 0..12, activity = "running")

        assertEquals(170, state.active.getValue(AlertType.HighHeartRate).limit)
    }

    @Test
    fun `every high limit can be exceeded by a pulse the firmware is able to send`() {
        val highest = AlertSettings(restingHighBpm = 999, activeHighBpm = 999, prolongedBpm = 999).clamped()

        assertTrue(highest.restingHighBpm < AlertSettings.FIRMWARE_MAX_BPM)
        assertTrue(highest.activeHighBpm < AlertSettings.FIRMWARE_MAX_BPM)
        assertTrue(highest.prolongedBpm < AlertSettings.FIRMWARE_MAX_BPM)
    }

    @Test
    fun `stopping after a run keeps the active limit during cool down`() {
        val state = AlertState()
            .feed(140, 0..5, activity = "running")
            .feed(140, 6..120, activity = "sitting")

        assertTrue(state.types.isEmpty())
    }

    @Test
    fun `high pulse long after the run is raised`() {
        val state = AlertState()
            .feed(140, 0..5, activity = "running")
            .feed(140, 6..200, activity = "sitting")

        assertEquals(setOf(AlertType.HighHeartRate), state.types)
    }

    @Test
    fun `prolonged high pulse while moving is raised after the configured minutes`() {
        val early = AlertState().feed(160, 0..299, activity = "running")
        val later = early.feed(160, 300..300, activity = "running")

        assertTrue(early.types.isEmpty())
        assertEquals(setOf(AlertType.ProlongedHighHeartRate), later.types)
    }

    @Test
    fun `short pauses do not reset the prolonged timer`() {
        val state = AlertState()
            .feed(160, 0..150, activity = "running")
            .feed(120, 151..158, activity = "standing")
            .feed(160, 159..300, activity = "walking")

        assertTrue(AlertType.ProlongedHighHeartRate in state.types)
    }

    @Test
    fun `a long break resets the prolonged timer`() {
        val state = AlertState()
            .feed(160, 0..150, activity = "running")
            .feed(100, 151..200, activity = "sitting")
            .feed(160, 201..400, activity = "running")

        assertTrue(AlertType.ProlongedHighHeartRate !in state.types)
    }

    @Test
    fun `alert clears after pulse returns to normal and is closed in history`() {
        val raised = AlertState().feed(50, 0..12)
        val cleared = raised.feed(80, 13..16)

        assertTrue(cleared.types.isEmpty())
        assertEquals(1, cleared.history.size)
        assertNotNull(cleared.history.single().endedAtMillis)
    }

    @Test
    fun `one normal reading does not clear an alert or duplicate history`() {
        val state = AlertState().feed(50, 0..12).feed(80, 13..13).feed(50, 14..20)

        assertEquals(setOf(AlertType.LowHeartRate), state.types)
        assertEquals(1, state.history.size)
        assertNull(state.history.single().endedAtMillis)
    }

    @Test
    fun `alert expires when data stops`() {
        val state = AlertState().feed(50, 0..12).expire(23_000)

        assertTrue(state.types.isEmpty())
        assertEquals(23_000L, state.history.single().endedAtMillis)
    }

    @Test
    fun `disabled warnings raise nothing and clear active ones`() {
        val state = AlertState().feed(50, 0..12).feed(50, 13..30, with = settings.copy(enabled = false))

        assertTrue(state.types.isEmpty())
    }

    @Test
    fun `settings are clamped to their ranges`() {
        val clamped = AlertSettings(lowBpm = 1, restingHighBpm = 999, prolongedMinutes = 0).clamped()

        assertEquals(40, clamped.lowBpm)
        assertEquals(175, clamped.restingHighBpm)
        assertEquals(1, clamped.prolongedMinutes)
    }

    @Test
    fun `message describes the alert`() {
        val alert = ActiveAlert(AlertType.HighHeartRate, bpm = 150, limit = 130, sinceMillis = 0, duringActivity = false)

        assertEquals("150 BPM, above 130 for 25 s, at rest", AlertText.message(alert, 25_000))
        assertEquals("3 min", AlertText.duration(185_000))
    }
}
