# Health and Activity Monitor ML

A prototype child monitor. The child wears a wrist device on an Arduino Portenta H7, the parent watches heart rate,
SpO2 and activity on an Android phone over Bluetooth Low Energy.

| Part | Where |
|---|---|
| Parent's Android app | [`android/`](android/README.md) |
| Arduino firmware with the deployed activity model | [`firmware/child_monitor_v2/`](firmware/child_monitor_v2) |
| Training script of the deployed model | [`training/`](training) |
| Recordings and an offline baseline experiment | `data/`, `src/`, `export/` |

## Activity model on the board

Trained with scikit-learn `MLPClassifier` (cross-entropy, Adam with the default learning rate 0.001, L2
`alpha=0.01`, at most 3000 iterations, fixed seed). Recordings are resampled to an exact 20 Hz grid, the first 2 s are
removed, recordings shorter than 40 s are skipped and smaller classes are oversampled.

No random window split is used, because overlapping windows would appear in both training and test.

| Evaluation | Training data | Test data | Accuracy |
|---|---|---|---|
| Leave one recording out | 18 recordings, three adults, 1960 windows | each recording in turn | 96.8% |
| Unseen person | 14 recordings, two adults, 1503 windows | a third adult, 457 windows | 97.4% |

- On the unseen person most errors are standing predicted as sitting, at the start of the standing recording.
- Without the orientation features (mean acceleration) the leave-one-recording-out accuracy drops to 94.7%.
- 4 to 64 hidden units all give 96.6–96.8%, so the network was kept at 16.
- The model on the board is trained on all 18 recordings.
- The model was not tested on children.

Not all 18 recordings are published here, so running the script on `data/raw` does not reproduce these numbers or the
shipped `model_data.h`. To train on the published files:

```bash
cd data/raw && ../../.venv/bin/python ../../training/train_model.py
```

It reads the CSV files of the current folder and writes `model_data.h` there.

## Wearable firmware

`firmware/child_monitor_v2/` runs on the Portenta H7. Everything is computed on the board, the phone only shows it.

| Part | I2C address | Role |
|---|---|---|
| MAX30102 (DFRobot SEN0344) | `0x57` | heart rate and SpO2 |
| BMI160 (DFRobot SEN0250) | `0x69` or `0x68` | accelerometer and gyroscope |
| OLED 128x64 | `0x3C` | shows activity, heart rate, SpO2 |

- **Activity**: a multilayer perceptron (13 features, 16 hidden ReLU units, 4 classes, 292 parameters). IMU at
  20 Hz, window of 4 s, a prediction every second, majority of the last 3 predictions. The weights are in
  `model_data.h`, features and inference in `model_inference.h`, training in
  [`training/train_model.py`](training/train_model.py) (see below).
- **Heart rate and SpO2**: Maxim algorithm over 4 s of samples at 25 Hz, recomputed every second. Values outside
  40–180 BPM or 80–100% are dropped, the median of the last 7 valid results is reported, `--` without a finger.
- **Outputs, once per second**: OLED, on-board LED (blue sitting, green standing, yellow walking, red running),
  BLE and serial port.

Flash `child_monitor_v2.ino` with the Arduino IDE (board package for Portenta, libraries ArduinoBLE, Adafruit GFX,
Adafruit SSD1306, SparkFun MAX3010x).

Known issues:
- if the algorithm stops producing valid results while the finger stays on the sensor, the previous median is still
  sent, so the app shows an old pulse as current;
- if the IMU stops answering while running, the last activity is still sent instead of `--`.

### Power

Tested with a USB power bank. Do not power the board from two 3 V coin cells: in series they give 6 V while VIN is a
5 V input, and a coin cell supplies a few milliamperes while the board can draw up to 230 mA (datasheet). A series
resistor does not fix either problem. The intended battery is a single-cell 3.7 V Li-Po or Li-Ion of at least
700 mAh on the J4 battery connector, which the board can also recharge.

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
| High heart rate | above 130 at rest, above 170 with activity | about 10 s |
| High for a long time | above 150 while walking or running | 5 min |

- Shown as a red banner and as a phone notification with vibration.
- Limits and duration are adjustable, warnings can be switched off, past warnings are listed.
- Single spikes of the noisy sensor are ignored, and the activity limit stays for 3 minutes after a run.
- No warnings on SpO2, because that reading is not validated.

The firmware reports heart rate up to 180 BPM only, so the high limits cannot be set above 175.

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

## Offline baseline experiment

This part is **not** what runs on the board (that is the perceptron in `firmware/`). It is a Random Forest baseline
trained on the BMI160 (SEN0250) recordings made with `imu_logger.py`, kept for comparison.

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

The export was checked on a computer only, it has not been run on the board.

### Evaluation rule

Never split windows randomly: neighbouring windows are near-duplicates and the score is inflated. Split by recording, and once subject labels exist, by person.
