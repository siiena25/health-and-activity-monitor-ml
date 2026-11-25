# Health and Activity Monitor ML

Trains a standing / sitting / walking / running classifier from the BMI160 (SEN0250) recordings made with `imu_logger.py`.

The parent's Android app (heart rate, SpO2, activity and heart rate warnings over BLE) is in [`android/`](android/README.md).

## Data

`data/raw/*.csv`, columns `t_ms, ax, ay, az (m/s²), gx, gy, gz (rad/s), label`, 20 Hz, accelerometer ±8 g, gyro ±2000 °/s.

Loading rules (`src/data.py`):
- files that are empty, shorter than 30 s, or not named `<activity>_<date>_<time>.csv` are skipped (`test_*` are ignored);
- timestamps are interpolated onto a uniform 20 Hz grid (BLE drops leave small gaps);
- the first and last 5 s of each recording are trimmed;
- windows are 3 s (60 samples), 50% overlap.

## Features

`src/features.py`: for each of ax, ay, az, gx, gy, gz, |acc|, |gyro|: mean, std, min, max, quartiles, RMS, mean absolute difference, zero-crossing rate, dominant frequency and amplitude, spectral entropy. 96 features. The orientation-free variant drops mean/min/max/quartiles/RMS of the raw accelerometer axes.

## Run

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python src/train.py
```

Output goes to `reports/results.txt` and `reports/*.png`.

## Export to Portenta

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

## Evaluation rule

Never split windows randomly: neighbouring windows are near-duplicates and the score is inflated. Split by recording, and once subject labels exist, by person.
