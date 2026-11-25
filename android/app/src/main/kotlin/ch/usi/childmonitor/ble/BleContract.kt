package ch.usi.childmonitor.ble

import ch.usi.childmonitor.domain.Channel
import java.util.UUID

object BleContract {
    const val DEVICE_NAME = "ChildMonitor"

    val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    val BPM_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abd")
    val SPO2_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abe")
    val ACTIVITY_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abf")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun uuidFor(channel: Channel): UUID = when (channel) {
        Channel.Bpm -> BPM_UUID
        Channel.Spo2 -> SPO2_UUID
        Channel.Activity -> ACTIVITY_UUID
    }

    fun channelFor(uuid: UUID): Channel? = Channel.entries.firstOrNull { uuidFor(it) == uuid }
}
