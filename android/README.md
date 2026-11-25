# Child Monitor (parent app)

Android app that receives heart rate, SpO2 and activity from the Arduino Portenta H7 wearable over BLE and warns
about unusual heart rate.

## BLE contract

| Item | Value |
|---|---|
| Device name | `ChildMonitor` |
| Service | `12345678-1234-1234-1234-123456789abc` |
| BPM characteristic | `12345678-1234-1234-1234-123456789abd` (READ, NOTIFY) |
| SpO2 characteristic | `12345678-1234-1234-1234-123456789abe` (READ, NOTIFY) |
| Activity characteristic (optional) | `12345678-1234-1234-1234-123456789abf` (READ, NOTIFY, up to 20 bytes) |

Values are UTF-8 strings holding a decimal integer, or `--` when the firmware has no valid result.
Accepted ranges: BPM 30–220, SpO2 70–100. Anything else is shown as unavailable, never as a number.
A missing characteristic is tolerated and reported on the Diagnostics tab.

Activity payload: one of `sitting`, `standing`, `walking`, `running`, or `--` when there is no result yet
(first ~4 s after startup, or the motion sensor is not connected; shown as "Unknown"). The firmware notifies once
per second, together with BPM and SpO2. The app also accepts an optional confidence (`walking|0.91`).

## Tabs

- **Monitor**: BPM and SpO2, live / stale / unavailable status, trend.
- **Device**: permissions, Bluetooth on/off, scan, connect, disconnect, demo mode.
- **Activity**: current activity, confidence, recent changes. Shows "Not sent by this firmware" when the device has no activity characteristic.
- **Alerts**: heart rate warnings, limits, history, notification permission (see below).
- **Diagnostics**: expected vs discovered GATT, raw payloads (text, hex, parse result), link events.

Demo mode (switch on the Device tab) streams simulated values, including `--` and out-of-range payloads, so the UI works without hardware.

## Heart rate warnings

The app warns the parent about three situations. SpO2 never raises a warning, because that reading is not validated.

| Warning | Condition (default limit) | How long it must last |
|---|---|---|
| Low heart rate | below 60 BPM | about 10 s |
| High heart rate | above 130 BPM at rest, above 180 BPM with activity | about 10 s |
| High for a long time | above 150 BPM while walking or running | 5 min |

### Activity context

- `walking` and `running` count as moving; `sitting`, `standing`, `--` and a result older than 10 s count as rest.
- The higher "with activity" limit stays for 3 minutes after the child stops moving, so the pulse that is still
  coming down after a run does not raise a warning.

### Noise filtering

The optical sensor is noisy, so one bad value never raises a warning:

- at least 5 readings over the limit, spread over the required time, and at least 70% of the readings in that
  period over the limit;
- `--` and rejected values are ignored, they count neither for nor against;
- the 10 s warnings reset after 3 normal readings in a row or 10 s without a reading over the limit;
- the long-duration warning tolerates short pauses and resets after 15 normal readings in a row or 15 s without a
  reading over the limit.

The same reset rules end an active warning. Warnings also end when the connection is lost, when limits are changed,
when warnings are switched off and when demo mode is toggled.

### What the parent sees

- a red banner on the **Monitor** tab with the current value, the limit and the duration;
- a system notification with vibration (channel "Heart rate warnings"), posted once when a warning starts and
  removed when it ends. Android 13+ needs the permission: **Alerts** tab, **Allow notifications**;
- the last 20 warnings in the history on the **Alerts** tab. The history is kept in memory only and is cleared when
  the app restarts or demo mode is toggled.

### Settings

Set on the **Alerts** tab and saved on the phone.

| Setting | Range | Step | Default |
|---|---|---|---|
| Warnings on/off | | | on |
| Low pulse: below | 40–100 BPM | 5 | 60 |
| High at rest: above | 90–180 BPM | 5 | 130 |
| High with activity: above | 130–220 BPM | 5 | 180 |
| Long high pulse: above | 110–200 BPM | 5 | 150 |
| Long means at least | 1–30 min | 1 | 5 |

### Try it without the wearable

1. Allow notifications on the **Alerts** tab.
2. On the **Device** tab switch on **Simulated data**, scan and connect.
3. On the **Alerts** tab raise "Low pulse: below" to 100.
4. A low heart rate warning appears within about 20 s, its notification is marked `DEMO`.
5. Set the limit back to 60, it is saved between launches.

### Limitations

- The default limits are placeholders for the prototype, not medical advice. Normal heart rate depends on the child's
  age and health.
- A warning can be wrong or missed: it relies on a sensor that has not been validated.
- There is no foreground service. Warnings work only while the app process is alive, Android may stop it in the
  background, and closing the app drops the connection.

The rules are in `domain/Alerts.kt` and covered by `AlertStateTest`.

## Structure

```
domain/  VitalParser, ActivityParser, VitalsState, alert rules (pure Kotlin)
ble/     MonitorLink interface, AndroidMonitorLink (GATT), MockMonitorLink, permissions
alerts/  alert settings storage, system notifications
ui/      MonitorViewModel, Compose screens
```

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest assembleDebug
```

Not a medical device. Readings are experimental and must not drive health decisions; warnings are only a hint to
check on the child.
