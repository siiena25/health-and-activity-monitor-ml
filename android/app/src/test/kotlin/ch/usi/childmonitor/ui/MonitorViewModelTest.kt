package ch.usi.childmonitor.ui

import ch.usi.childmonitor.alerts.AlertNotifier
import ch.usi.childmonitor.alerts.AlertSettingsStore
import ch.usi.childmonitor.ble.ConnectionState
import ch.usi.childmonitor.ble.GattDiagnostics
import ch.usi.childmonitor.ble.MonitorLink
import ch.usi.childmonitor.ble.ScannedDevice
import ch.usi.childmonitor.domain.ActiveAlert
import ch.usi.childmonitor.domain.AlertSettings
import ch.usi.childmonitor.domain.AlertType
import ch.usi.childmonitor.domain.Channel
import ch.usi.childmonitor.domain.RawPayload
import ch.usi.childmonitor.domain.VitalKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val real = FakeLink()
    private val mock = FakeLink()
    private val bluetoothEnabled = MutableStateFlow(true)
    private var now = 1_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val alertSettings = FakeAlertSettingsStore()
    private val notifier = FakeNotifier()

    private fun viewModel() =
        MonitorViewModel(real, mock, bluetoothEnabled, clock = { now }, alertSettings, notifier)

    private fun feedLowPulse() {
        for (second in 1..12) {
            now = 1_000L + second * 1_000L
            real.emit(payload(VitalKind.Bpm, "45"))
        }
    }

    @Test
    fun `sustained low pulse raises an alert and one notification`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }
        real.connectionState.value = ConnectionState.Connected

        feedLowPulse()

        assertEquals(setOf(AlertType.LowHeartRate), vm.uiState.value.alerts.active.keys)
        assertEquals(listOf("show:LowHeartRate"), notifier.calls)
        job.cancel()
    }

    @Test
    fun `losing the connection ends alerts and cancels the notification`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }
        real.connectionState.value = ConnectionState.Connected
        feedLowPulse()

        real.connectionState.value = ConnectionState.Disconnected

        assertTrue(vm.uiState.value.alerts.active.isEmpty())
        assertEquals(listOf("show:LowHeartRate", "cancel:LowHeartRate"), notifier.calls)
        job.cancel()
    }

    @Test
    fun `changing limits saves them and resets alerts`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }
        real.connectionState.value = ConnectionState.Connected
        feedLowPulse()

        vm.saveAlertSettings(AlertSettings(lowBpm = 40))

        assertEquals(40, vm.uiState.value.alertSettings.lowBpm)
        assertTrue(vm.uiState.value.alerts.active.isEmpty())
        job.cancel()
    }

    private class FakeAlertSettingsStore : AlertSettingsStore {
        private val state = MutableStateFlow(AlertSettings())
        override val settings: StateFlow<AlertSettings> = state

        override fun save(settings: AlertSettings) {
            state.value = settings.clamped()
        }
    }

    private class FakeNotifier : AlertNotifier {
        val calls = mutableListOf<String>()

        override fun show(alert: ActiveAlert, nowMillis: Long, demo: Boolean) {
            calls += "show:${alert.type}"
        }

        override fun cancel(type: AlertType) {
            calls += "cancel:$type"
        }
    }

    private fun payload(kind: VitalKind, text: String) = RawPayload(kind.channel, text.toByteArray(), now)

    private fun activityPayload(text: String) = RawPayload(Channel.Activity, text.toByteArray(), now)

    @Test
    fun `initial state is disconnected without data`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        val state = vm.uiState.value
        assertEquals(ConnectionState.Disconnected, state.connection)
        assertNull(state.vitals.bpm)
        assertFalse(state.connected)
        job.cancel()
    }

    @Test
    fun `payloads from the active link reach the vitals`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        real.connectionState.value = ConnectionState.Connected
        real.emit(payload(VitalKind.Bpm, "77"))
        real.emit(payload(VitalKind.Spo2, "98"))

        val state = vm.uiState.value
        assertTrue(state.connected)
        assertEquals(77, state.vitals.bpm?.value)
        assertEquals(98, state.vitals.spo2?.value)
        job.cancel()
    }

    @Test
    fun `activity payload reaches the activity state`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        real.connectionState.value = ConnectionState.Connected
        real.emit(activityPayload("walking|0.91"))

        val activity = vm.uiState.value.vitals.activity
        assertEquals("walking", activity?.label)
        assertEquals(0.91f, activity?.confidence)
        job.cancel()
    }

    @Test
    fun `malformed activity payload does not change the label`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        real.emit(activityPayload("walking|1.7"))

        assertNull(vm.uiState.value.vitals.activity?.label)
        job.cancel()
    }

    @Test
    fun `invalid payload leaves the reading unavailable`()= runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        real.emit(payload(VitalKind.Bpm, "112932"))

        assertNull(vm.uiState.value.vitals.bpm?.value)
        job.cancel()
    }

    @Test
    fun `actions are forwarded to the real link`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.startScan()
        vm.connect("AA:BB")
        vm.stopScan()
        vm.disconnect()

        assertEquals(listOf("startScan", "connect:AA:BB", "stopScan", "disconnect"), real.calls)
        assertTrue(mock.calls.isEmpty())
    }

    @Test
    fun `enabling mock mode switches link and clears data`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }
        real.emit(payload(VitalKind.Bpm, "77"))

        vm.setMockMode(true)
        mock.connectionState.value = ConnectionState.Connected
        mock.emit(payload(VitalKind.Bpm, "90"))
        vm.startScan()

        val state = vm.uiState.value
        assertTrue(state.useMock)
        assertEquals(90, state.vitals.bpm?.value)
        assertEquals(listOf("disconnect"), real.calls)
        assertEquals(listOf("startScan"), mock.calls)
        job.cancel()
    }

    @Test
    fun `payloads from the inactive link are ignored`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }
        vm.setMockMode(true)

        real.emit(payload(VitalKind.Bpm, "77"))

        assertNull(vm.uiState.value.vitals.bpm)
        job.cancel()
    }

    @Test
    fun `bluetooth state is exposed`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        bluetoothEnabled.value = false

        assertFalse(vm.uiState.value.bluetoothEnabled)
        job.cancel()
    }

    private class FakeLink : MonitorLink {
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        private val payloadFlow = MutableSharedFlow<RawPayload>(extraBufferCapacity = 16)
        val calls = mutableListOf<String>()

        override val connection: StateFlow<ConnectionState> = connectionState
        override val devices: StateFlow<List<ScannedDevice>> = MutableStateFlow(emptyList())
        override val diagnostics: StateFlow<GattDiagnostics> = MutableStateFlow(GattDiagnostics())
        override val payloads: Flow<RawPayload> = payloadFlow

        fun emit(payload: RawPayload) {
            payloadFlow.tryEmit(payload)
        }

        override fun startScan() {
            calls += "startScan"
        }

        override fun stopScan() {
            calls += "stopScan"
        }

        override fun connect(address: String) {
            calls += "connect:$address"
        }

        override fun disconnect() {
            calls += "disconnect"
        }
    }
}
