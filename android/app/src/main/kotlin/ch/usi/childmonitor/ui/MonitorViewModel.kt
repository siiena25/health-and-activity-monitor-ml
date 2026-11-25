package ch.usi.childmonitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.usi.childmonitor.alerts.AlertNotifier
import ch.usi.childmonitor.alerts.AlertSettingsStore
import ch.usi.childmonitor.ble.ConnectionState
import ch.usi.childmonitor.ble.GattDiagnostics
import ch.usi.childmonitor.ble.MonitorLink
import ch.usi.childmonitor.ble.ScannedDevice
import ch.usi.childmonitor.domain.AlertSettings
import ch.usi.childmonitor.domain.AlertState
import ch.usi.childmonitor.domain.Channel
import ch.usi.childmonitor.domain.RawPayload
import ch.usi.childmonitor.domain.STALE_AFTER_MILLIS
import ch.usi.childmonitor.domain.VitalsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

data class MonitorUiState(
    val useMock: Boolean = false,
    val bluetoothEnabled: Boolean = true,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val devices: List<ScannedDevice> = emptyList(),
    val diagnostics: GattDiagnostics = GattDiagnostics(),
    val vitals: VitalsState = VitalsState(),
    val nowMillis: Long = 0L,
    val alerts: AlertState = AlertState(),
    val alertSettings: AlertSettings = AlertSettings(),
) {
    val connected: Boolean get() = connection is ConnectionState.Connected
}

private data class LinkSnapshot(
    val connection: ConnectionState,
    val devices: List<ScannedDevice>,
    val diagnostics: GattDiagnostics,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorViewModel(
    private val realLink: MonitorLink,
    private val mockLink: MonitorLink,
    bluetoothEnabled: Flow<Boolean>,
    private val clock: () -> Long,
    private val alertSettings: AlertSettingsStore,
    private val notifier: AlertNotifier,
) : ViewModel() {

    private val useMock = MutableStateFlow(false)
    private val activeLink = MutableStateFlow(realLink)
    private val vitals = MutableStateFlow(VitalsState())
    private val alerts = MutableStateFlow(AlertState())

    private val ticker: Flow<Long> = flow {
        while (true) {
            val now = clock()
            changeAlerts { it.expire(now) }
            emit(now)
            delay(TICK_MILLIS)
        }
    }

    private val linkSnapshot: Flow<LinkSnapshot> = activeLink.flatMapLatest { link ->
        combine(link.connection, link.devices, link.diagnostics, ::LinkSnapshot)
    }

    private val monitorState: Flow<MonitorUiState> = combine(
        linkSnapshot,
        vitals,
        useMock,
        bluetoothEnabled,
        ticker,
    ) { link, vitalsState, mock, btEnabled, now ->
        MonitorUiState(
            useMock = mock,
            bluetoothEnabled = btEnabled,
            connection = link.connection,
            devices = link.devices,
            diagnostics = link.diagnostics,
            vitals = vitalsState,
            nowMillis = now,
        )
    }

    val uiState: StateFlow<MonitorUiState> = combine(
        monitorState,
        alerts,
        alertSettings.settings,
    ) { state, alertState, settings ->
        state.copy(alerts = alertState, alertSettings = settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MonitorUiState())

    init {
        viewModelScope.launch {
            activeLink.flatMapLatest { it.payloads }.collect(::onPayload)
        }
        viewModelScope.launch {
            activeLink.flatMapLatest { it.connection }.collect { connection ->
                if (connection !is ConnectionState.Connected) changeAlerts { it.endAll(clock()) }
            }
        }
    }

    fun startScan() = activeLink.value.startScan()

    fun stopScan() = activeLink.value.stopScan()

    fun connect(address: String) = activeLink.value.connect(address)

    fun disconnect() = activeLink.value.disconnect()

    fun setMockMode(enabled: Boolean) {
        if (useMock.value == enabled) return
        activeLink.value.disconnect()
        vitals.value = VitalsState()
        changeAlerts { AlertState() }
        useMock.value = enabled
        activeLink.value = if (enabled) mockLink else realLink
    }

    fun saveAlertSettings(settings: AlertSettings) {
        if (settings.clamped() == alertSettings.settings.value) return
        alertSettings.save(settings)
        changeAlerts { it.endAll(clock()) }
    }

    override fun onCleared() {
        changeAlerts { it.endAll(clock()) }
        realLink.disconnect()
        mockLink.disconnect()
    }

    private fun onPayload(payload: RawPayload) {
        val updated = vitals.updateAndGet { it.withPayload(payload) }
        if (payload.channel != Channel.Bpm) return
        val bpm = updated.bpm?.value ?: return
        val now = payload.receivedAtMillis
        val activity = updated.activity?.takeIf { now - it.receivedAtMillis <= STALE_AFTER_MILLIS }?.label
        changeAlerts { it.onHeartRate(bpm, activity, now, alertSettings.settings.value) }
    }

    private fun changeAlerts(transform: (AlertState) -> AlertState) {
        val before = alerts.value
        val after = transform(before)
        if (after == before) return
        alerts.value = after
        (before.active.keys - after.active.keys).forEach(notifier::cancel)
        (after.active.keys - before.active.keys).forEach { type ->
            notifier.show(after.active.getValue(type), clock(), useMock.value)
        }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
