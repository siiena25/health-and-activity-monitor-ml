package ch.usi.childmonitor.ble

import ch.usi.childmonitor.domain.Channel
import ch.usi.childmonitor.domain.RawPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data object Connecting : ConnectionState
    data class Reconnecting(val attempt: Int) : ConnectionState
    data object Connected : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class ScannedDevice(val address: String, val name: String?, val rssi: Int)

data class GattServiceInfo(val uuid: String, val characteristics: List<String>)

data class DiagnosticEvent(val atMillis: Long, val message: String)

data class GattDiagnostics(
    val services: List<GattServiceInfo> = emptyList(),
    val missing: Set<Channel> = emptySet(),
    val serviceFound: Boolean? = null,
    val events: List<DiagnosticEvent> = emptyList(),
)

interface MonitorLink {
    val connection: StateFlow<ConnectionState>
    val devices: StateFlow<List<ScannedDevice>>
    val diagnostics: StateFlow<GattDiagnostics>
    val payloads: Flow<RawPayload>

    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
}
