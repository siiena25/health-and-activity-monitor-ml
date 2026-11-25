package ch.usi.childmonitor.domain

enum class Channel(val title: String, val required: Boolean) {
    Bpm(title = "Heart rate", required = true),
    Spo2(title = "Blood oxygen (SpO₂)", required = true),
    Activity(title = "Activity", required = false),
    ;

    val vitalKind: VitalKind? get() = VitalKind.entries.firstOrNull { it.channel == this }
}

enum class VitalKind(val channel: Channel, val unit: String, val validRange: IntRange) {
    Bpm(channel = Channel.Bpm, unit = "BPM", validRange = 30..220),
    Spo2(channel = Channel.Spo2, unit = "%", validRange = 70..100),
    ;

    val title: String get() = channel.title
}
