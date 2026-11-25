package ch.usi.childmonitor.alerts

import android.content.Context
import androidx.core.content.edit
import ch.usi.childmonitor.domain.AlertSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AlertSettingsStore {
    val settings: StateFlow<AlertSettings>
    fun save(settings: AlertSettings)
}

class PreferencesAlertSettingsStore(context: Context) : AlertSettingsStore {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    override val settings: StateFlow<AlertSettings> = state.asStateFlow()

    override fun save(settings: AlertSettings) {
        val safe = settings.clamped()
        preferences.edit {
            putBoolean(ENABLED, safe.enabled)
            putInt(LOW, safe.lowBpm)
            putInt(RESTING_HIGH, safe.restingHighBpm)
            putInt(ACTIVE_HIGH, safe.activeHighBpm)
            putInt(PROLONGED, safe.prolongedBpm)
            putInt(PROLONGED_MINUTES, safe.prolongedMinutes)
        }
        state.value = safe
    }

    private fun load(): AlertSettings {
        val defaults = AlertSettings()
        return AlertSettings(
            enabled = preferences.getBoolean(ENABLED, defaults.enabled),
            lowBpm = preferences.getInt(LOW, defaults.lowBpm),
            restingHighBpm = preferences.getInt(RESTING_HIGH, defaults.restingHighBpm),
            activeHighBpm = preferences.getInt(ACTIVE_HIGH, defaults.activeHighBpm),
            prolongedBpm = preferences.getInt(PROLONGED, defaults.prolongedBpm),
            prolongedMinutes = preferences.getInt(PROLONGED_MINUTES, defaults.prolongedMinutes),
        ).clamped()
    }

    private companion object {
        const val FILE = "alert_settings"
        const val ENABLED = "enabled"
        const val LOW = "low_bpm"
        const val RESTING_HIGH = "resting_high_bpm"
        const val ACTIVE_HIGH = "active_high_bpm"
        const val PROLONGED = "prolonged_bpm"
        const val PROLONGED_MINUTES = "prolonged_minutes"
    }
}
