package ch.usi.childmonitor.ble

import ch.usi.childmonitor.domain.Channel
import ch.usi.childmonitor.domain.RawPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

class MockMonitorLink(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) : MonitorLink {

    private val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val deviceList = MutableStateFlow<List<ScannedDevice>>(emptyList())
    private val diagnosticsState = MutableStateFlow(GattDiagnostics())
    private val payloadFlow = MutableSharedFlow<RawPayload>(
        extraBufferCapacity = PAYLOAD_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val connection: StateFlow<ConnectionState> = connectionState.asStateFlow()
    override val devices: StateFlow<List<ScannedDevice>> = deviceList.asStateFlow()
    override val diagnostics: StateFlow<GattDiagnostics> = diagnosticsState.asStateFlow()
    override val payloads: Flow<RawPayload> = payloadFlow.asSharedFlow()

    private var scanJob: Job? = null
    private var streamJob: Job? = null

    override fun startScan() {
        if (connectionState.value !is ConnectionState.Disconnected && connectionState.value !is ConnectionState.Error) {
            return
        }
        connectionState.value = ConnectionState.Scanning
        deviceList.value = emptyList()
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SCAN_DELAY_MILLIS)
            deviceList.value = listOf(ScannedDevice(MOCK_ADDRESS, "ChildMonitor (simulated)", MOCK_RSSI))
            delay(SCAN_DELAY_MILLIS)
            stopScan()
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        if (connectionState.value is ConnectionState.Scanning) {
            connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun connect(address: String) {
        scanJob?.cancel()
        streamJob?.cancel()
        connectionState.value = ConnectionState.Connecting
        streamJob = scope.launch {
            delay(CONNECT_DELAY_MILLIS)
            diagnosticsState.update {
                GattDiagnostics(
                    services = listOf(
                        GattServiceInfo(
                            uuid = BleContract.SERVICE_UUID.toString(),
                            characteristics = Channel.entries.map { BleContract.uuidFor(it).toString() },
                        ),
                    ),
                    serviceFound = true,
                    events = listOf(DiagnosticEvent(clock(), "Simulated connection established")),
                )
            }
            connectionState.value = ConnectionState.Connected
            stream()
        }
    }

    override fun disconnect() {
        scanJob?.cancel()
        streamJob?.cancel()
        connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun stream() {
        var bpm = START_BPM
        var spo2 = START_SPO2
        var tick = 0
        while (true) {
            val scene = SCRIPT[currentScene(tick)]
            if (tick % VITAL_EVERY_TICKS == 0) {
                bpm = (bpm + (scene.bpm - bpm).coerceIn(-BPM_STEP, BPM_STEP) + random.nextInt(-2, 3))
                    .coerceIn(BPM_MIN, BPM_MAX)
                spo2 = (spo2 + random.nextInt(-1, 2)).coerceIn(SPO2_MIN, SPO2_MAX)
                emit(Channel.Bpm, noisy(bpm.toString(), GARBAGE_VITAL))
                emit(Channel.Spo2, noisy(spo2.toString(), GARBAGE_VITAL))
                emit(Channel.Activity, if (tick < ACTIVITY_WARMUP_TICKS) "--" else scene.label)
            }
            tick++
            delay(TICK_MILLIS)
        }
    }

    private fun currentScene(tick: Int): Int {
        var remaining = tick % SCRIPT.sumOf { it.ticks }
        SCRIPT.forEachIndexed { index, scene ->
            if (remaining < scene.ticks) return index
            remaining -= scene.ticks
        }
        return 0
    }

    private fun noisy(text: String, garbage: String): String {
        val roll = random.nextInt(ROLL_BOUND)
        return when {
            roll < UNAVAILABLE_BELOW -> "--"
            roll < GARBAGE_BELOW -> garbage
            else -> text
        }
    }

    private fun emit(channel: Channel, text: String) {
        payloadFlow.tryEmit(RawPayload(channel, text.toByteArray(Charsets.UTF_8), clock()))
    }

    private data class Scene(val label: String, val bpm: Int, val ticks: Int)

    private companion object {
        const val MOCK_ADDRESS = "00:00:00:00:00:01"
        const val MOCK_RSSI = -52
        const val SCAN_DELAY_MILLIS = 1_000L
        const val CONNECT_DELAY_MILLIS = 700L
        const val TICK_MILLIS = 500L
        const val VITAL_EVERY_TICKS = 2
        const val ACTIVITY_WARMUP_TICKS = 8
        const val PAYLOAD_BUFFER = 64
        const val START_BPM = 82
        const val START_SPO2 = 97
        const val BPM_MIN = 55
        const val BPM_MAX = 170
        const val BPM_STEP = 3
        const val GARBAGE_VITAL = "112932"
        val SCRIPT = listOf(
            Scene("standing", bpm = 82, ticks = 30),
            Scene("walking", bpm = 105, ticks = 40),
            Scene("running", bpm = 150, ticks = 30),
            Scene("sitting", bpm = 72, ticks = 40),
        )
        const val SPO2_MIN = 92
        const val SPO2_MAX = 99
        const val ROLL_BOUND = 100
        const val UNAVAILABLE_BELOW = 8
        const val GARBAGE_BELOW = 10
    }
}
