package ch.usi.childmonitor.domain

enum class AlertType { LowHeartRate, HighHeartRate, ProlongedHighHeartRate }

data class AlertSettings(
    val enabled: Boolean = true,
    val lowBpm: Int = 60,
    val restingHighBpm: Int = 130,
    val activeHighBpm: Int = 170,
    val prolongedBpm: Int = 150,
    val prolongedMinutes: Int = 5,
) {
    fun clamped(): AlertSettings = copy(
        lowBpm = lowBpm.coerceIn(LOW_RANGE),
        restingHighBpm = restingHighBpm.coerceIn(RESTING_HIGH_RANGE),
        activeHighBpm = activeHighBpm.coerceIn(ACTIVE_HIGH_RANGE),
        prolongedBpm = prolongedBpm.coerceIn(PROLONGED_RANGE),
        prolongedMinutes = prolongedMinutes.coerceIn(PROLONGED_MINUTES_RANGE),
    )

    companion object {
        const val FIRMWARE_MAX_BPM = 180
        private const val MAX_HIGH_LIMIT_BPM = FIRMWARE_MAX_BPM - 5
        val LOW_RANGE = 40..100
        val RESTING_HIGH_RANGE = 90..MAX_HIGH_LIMIT_BPM
        val ACTIVE_HIGH_RANGE = 130..MAX_HIGH_LIMIT_BPM
        val PROLONGED_RANGE = 110..MAX_HIGH_LIMIT_BPM
        val PROLONGED_MINUTES_RANGE = 1..30
    }
}

data class ConditionTracker(
    val sinceMillis: Long,
    val lastHitMillis: Long,
    val hits: Int = 1,
    val total: Int = 1,
    val misses: Int = 0,
)

data class ActiveAlert(
    val type: AlertType,
    val bpm: Int,
    val limit: Int,
    val sinceMillis: Long,
    val duringActivity: Boolean,
)

data class AlertRecord(
    val type: AlertType,
    val bpm: Int,
    val limit: Int,
    val raisedAtMillis: Long,
    val endedAtMillis: Long? = null,
)

private data class AlertRule(
    val sustainMillis: Long,
    val minHits: Int,
    val maxMisses: Int,
    val maxGapMillis: Long,
)

private data class Observation(
    val type: AlertType,
    val qualifies: Boolean,
    val bpm: Int,
    val limit: Int,
    val duringActivity: Boolean,
    val atMillis: Long,
)

data class AlertState(
    val trackers: Map<AlertType, ConditionTracker> = emptyMap(),
    val active: Map<AlertType, ActiveAlert> = emptyMap(),
    val history: List<AlertRecord> = emptyList(),
    val lastMovingMillis: Long? = null,
) {

    fun onHeartRate(bpm: Int, activity: String?, nowMillis: Long, settings: AlertSettings): AlertState {
        if (!settings.enabled) return endAll(nowMillis)
        val moving = activity in MOVING_ACTIVITIES
        val lastMoving = if (moving) nowMillis else lastMovingMillis
        val exerting = lastMoving != null && nowMillis - lastMoving <= COOL_DOWN_MILLIS
        val highLimit = if (exerting) settings.activeHighBpm else settings.restingHighBpm
        val prolongedRule = prolongedRule(settings)
        return expire(nowMillis)
            .copy(lastMovingMillis = lastMoving)
            .observe(
                Observation(AlertType.LowHeartRate, bpm < settings.lowBpm, bpm, settings.lowBpm, exerting, nowMillis),
                SHORT_RULE,
            )
            .observe(
                Observation(AlertType.HighHeartRate, bpm > highLimit, bpm, highLimit, exerting, nowMillis),
                SHORT_RULE,
            )
            .observe(
                Observation(
                    type = AlertType.ProlongedHighHeartRate,
                    qualifies = moving && bpm > settings.prolongedBpm,
                    bpm = bpm,
                    limit = settings.prolongedBpm,
                    duringActivity = true,
                    atMillis = nowMillis,
                ),
                prolongedRule,
            )
    }

    fun expire(nowMillis: Long): AlertState = trackers.entries
        .filter { (type, tracker) -> nowMillis - tracker.lastHitMillis > maxGapFor(type) }
        .fold(this) { state, entry -> state.end(entry.key, nowMillis) }

    fun endAll(nowMillis: Long): AlertState =
        trackers.keys.fold(this) { state, type -> state.end(type, nowMillis) }

    private fun observe(observation: Observation, rule: AlertRule): AlertState {
        val type = observation.type
        val current = trackers[type]
        val next = when {
            observation.qualifies && current == null ->
                ConditionTracker(observation.atMillis, observation.atMillis)
            observation.qualifies && current != null -> current.copy(
                lastHitMillis = observation.atMillis,
                hits = current.hits + 1,
                total = current.total + 1,
                misses = 0,
            )
            current == null -> return this
            current.misses + 1 >= rule.maxMisses -> return end(type, observation.atMillis)
            else -> current.copy(total = current.total + 1, misses = current.misses + 1)
        }
        val tracked = copy(trackers = trackers + (type to next))
        val sustained = next.lastHitMillis - next.sinceMillis >= rule.sustainMillis &&
            next.hits >= rule.minHits &&
            next.hits * PERCENT >= next.total * MIN_HIT_PERCENT
        return if (observation.qualifies && (type in active || sustained)) {
            tracked.raise(observation, next.sinceMillis)
        } else {
            tracked
        }
    }

    private fun raise(observation: Observation, sinceMillis: Long): AlertState {
        val alert = ActiveAlert(
            type = observation.type,
            bpm = observation.bpm,
            limit = observation.limit,
            sinceMillis = sinceMillis,
            duringActivity = observation.duringActivity,
        )
        val updatedHistory = if (observation.type in active) {
            history
        } else {
            val record = AlertRecord(observation.type, observation.bpm, observation.limit, observation.atMillis)
            (listOf(record) + history).take(MAX_HISTORY)
        }
        return copy(active = active + (observation.type to alert), history = updatedHistory)
    }

    private fun end(type: AlertType, nowMillis: Long): AlertState = copy(
        trackers = trackers - type,
        active = active - type,
        history = history.map {
            if (it.type == type && it.endedAtMillis == null) it.copy(endedAtMillis = nowMillis) else it
        },
    )

    private fun maxGapFor(type: AlertType): Long =
        if (type == AlertType.ProlongedHighHeartRate) PROLONGED_MAX_GAP_MILLIS else SHORT_RULE.maxGapMillis

    private fun prolongedRule(settings: AlertSettings) = AlertRule(
        sustainMillis = settings.prolongedMinutes * MILLIS_PER_MINUTE,
        minHits = SHORT_RULE.minHits,
        maxMisses = PROLONGED_MAX_MISSES,
        maxGapMillis = PROLONGED_MAX_GAP_MILLIS,
    )

    companion object {
        val MOVING_ACTIVITIES = setOf("walking", "running")
        const val COOL_DOWN_MILLIS = 180_000L
        const val MAX_HISTORY = 20
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val PROLONGED_MAX_MISSES = 15
        private const val PROLONGED_MAX_GAP_MILLIS = 15_000L
        private const val PERCENT = 100
        private const val MIN_HIT_PERCENT = 70
        private val SHORT_RULE = AlertRule(
            sustainMillis = 10_000L,
            minHits = 5,
            maxMisses = 3,
            maxGapMillis = 10_000L,
        )
    }
}
