# Health and Activity Monitor ML

A prototype child monitor. The child wears a wrist device on an Arduino Portenta H7, the parent watches heart rate,
SpO2 and activity on an Android phone over Bluetooth Low Energy.

| Part | Where |
|---|---|
| Parent's Android app | [`android/`](android/README.md) |
| Activity model: data, training, export to C++ | `src/`, `data/`, `export/`, described below |
| Arduino firmware | not in this repository |

## Android app

### Connecting to the wearable

- Asks for the Bluetooth permission and offers to turn Bluetooth on.
- Scans for a device named `ChildMonitor` or advertising its service, then connects and disconnects on tap.
- After an unexpected disconnect it retries 3 times with a growing pause, then shows an error.

### Monitor tab

- Heart rate (BPM) and blood oxygen (SpO2) in large digits with a short trend chart for each.
- Every value has a status: live, stale (older than 10 s), disconnected, no valid reading or waiting for data.
- Implausible values (BPM outside 30–220, SpO2 outside 70–100, garbage text) are shown as `--`, never as a number.
- Current activity and active heart rate warnings.

### Activity tab

- What the wearable recognised: sitting, standing, walking or running, or "Unknown" while it has no result.
- The last 10 changes of activity with how long ago they happened.

### Heart rate warnings (Alerts tab)

| Warning | Default | How long it must last |
|---|---|---|
| Low heart rate | below 60 BPM | about 10 s |
| High heart rate | above 130 at rest, above 180 with activity | about 10 s |
| High for a long time | above 150 while walking or running | 5 min |

- Shown as a red banner and as a phone notification with vibration.
- Limits and duration are adjustable, warnings can be switched off, past warnings are listed.
- Single spikes of the noisy sensor are ignored, and the activity limit stays for 3 minutes after a run.
- No warnings on SpO2, because that reading is not validated.

Full rules are in [`android/README.md`](android/README.md#heart-rate-warnings).

### Diagnostics tab

- Connection state, expected and discovered BLE characteristics.
- Raw messages from the wearable (text, hex and whether they were accepted) and a log of connection events.

### Demo mode

A switch on the Device tab replaces the wearable with simulated values, so the app can be tried without hardware.
The Monitor tab shows a red **DEMO MODE** banner while it is on.

### Run it

Android 8.0 or newer with Bluetooth LE. Open the `android/` folder in Android Studio, or from a terminal on macOS:

```bash
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew installDebug
```

### Limitations

- Not a medical device: readings and default limits are for a prototype only.
- No background service, so monitoring works only while the app is running.
- Works only within Bluetooth range, history is not saved after the app closes.

## Activity model

Trains a standing / sitting / walking / running classifier from the BMI160 (SEN0250) recordings made with `imu_logger.py`.

### Data

`data/raw/*.csv`, columns `t_ms, ax, ay, az (m/s²), gx, gy, gz (rad/s), label`, 20 Hz, accelerometer ±8 g, gyro ±2000 °/s.

Loading rules (`src/data.py`):
- files that are empty, shorter than 30 s, or not named `<activity>_<date>_<time>.csv` are skipped (`test_*` are ignored);
- timestamps are interpolated onto a uniform 20 Hz grid (BLE drops leave small gaps);
- the first and last 5 s of each recording are trimmed;
- windows are 3 s (60 samples), 50% overlap.

### Model features

`src/features.py`: for each of ax, ay, az, gx, gy, gz, |acc|, |gyro|: mean, std, min, max, quartiles, RMS, mean absolute difference, zero-crossing rate, dominant frequency and amplitude, spectral entropy. 96 features. The orientation-free variant drops mean/min/max/quartiles/RMS of the raw accelerometer axes.

### Train

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python src/train.py
```

Output goes to `reports/results.txt` and `reports/*.png`.

### Export to Portenta

```bash
.venv/bin/python src/export.py
clang++ -std=c++17 -O2 -o /tmp/host_test export/host_test.cpp && /tmp/host_test export/test_vectors.csv
```

`src/export.py` trains the compact model (64 features: mean, std, min, max, RMS, mean absolute difference, dominant frequency and amplitude for each of the 8 signals; 30 trees, depth 6, 476 nodes) and writes:

- `export/activity_model.h`: the forest as constant arrays, about 14 KB of flash;
- `export/feature_names.txt`: feature order;
- `export/test_vectors.csv`: every window with the Python features and probabilities (ignored by git).

`export/activity_classifier.h` is the header-only C++ side: feature extraction and forest voting. `host_test.cpp` checks it against Python: all windows must get the same label, features must agree to 1e-4 relative, and the streaming `Classifier` must reproduce the first two windows. Last run: 866 windows, 0 label mismatches, worst feature error 2e-7.

Firmware use:

```cpp
#include "activity_classifier.h"

activity::Classifier classifier;

void onImuSample(float ax, float ay, float az, float gx, float gy, float gz) {
  float sample[6] = {ax, ay, az, gx, gy, gz};
  activity::Result result;
  if (classifier.push(sample, &result)) {
    // result.name, result.confidence: one result every 1.5 s after the first 3 s
  }
}
```

Requirements on the firmware side:
- samples must arrive at 20 Hz without gaps (the Python side resampled gaps by interpolation, the device does not);
- units must match the logger: acceleration in m/s² (raw × 9.80665 / 4096 for ±8 g), angular rate in rad/s (raw × π / 180 / 16.4 for ±2000 °/s);
- the sensor must sit on the wrist with the same axis orientation as during recording, because sitting and standing are told apart by wrist orientation.

Publishing the result over BLE (the Android app already reads this; the current team firmware sends the label alone once per second, which the app accepts, confidence is optional):

- characteristic `12345678-1234-1234-1234-123456789abf` in the `ChildMonitor` service, `BLERead | BLENotify`, `BLEStringCharacteristic` with at least 20 bytes;
- value `label|confidence` such as `walking|0.91` (class name from `result.name`, confidence with two decimals), or `--` before the first result or when the IMU fails;
- write it on every result, about every 1.5 s.

```cpp
BLEStringCharacteristic activityChar("12345678-1234-1234-1234-123456789abf", BLERead | BLENotify, 20);
healthService.addCharacteristic(activityChar);
activityChar.writeValue("--");

activityChar.writeValue(String(result.name) + "|" + String(result.confidence, 2));
```

Not yet run on the board.

### Evaluation rule

Never split windows randomly: neighbouring windows are near-duplicates and the score is inflated. Split by recording, and once subject labels exist, by person.
