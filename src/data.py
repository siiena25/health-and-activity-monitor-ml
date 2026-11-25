import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

CLASSES = ["running", "sitting", "standing", "walking"]
CHANNELS = ["ax", "ay", "az", "gx", "gy", "gz"]
SAMPLE_RATE_HZ = 20
MIN_DURATION_S = 30.0
TRIM_S = 5.0
WINDOW_S = 3.0
STRIDE_S = 1.5

FILE_NAME = re.compile(r"^(?P<label>[a-z]+)_(?P<stamp>\d{8}_\d{6})\.csv$")


@dataclass(frozen=True)
class Recording:
    name: str
    label: str
    stamp: str
    signal: np.ndarray


@dataclass(frozen=True)
class Dataset:
    windows: np.ndarray
    labels: np.ndarray
    groups: np.ndarray


def load_recordings(raw_dir):
    recordings = []
    skipped = []
    for path in sorted(Path(raw_dir).glob("*.csv")):
        match = FILE_NAME.match(path.name)
        if match is None or match["label"] not in CLASSES:
            skipped.append((path.name, "not a labelled activity file"))
            continue
        frame = pd.read_csv(path)
        if len(frame) < 2:
            skipped.append((path.name, "empty"))
            continue
        seconds = (frame["t_ms"].to_numpy(dtype=np.float64) - frame["t_ms"].iloc[0]) / 1000.0
        if seconds[-1] < MIN_DURATION_S:
            skipped.append((path.name, f"too short ({seconds[-1]:.0f} s)"))
            continue
        grid = np.arange(0.0, seconds[-1], 1.0 / SAMPLE_RATE_HZ)
        signal = np.column_stack([np.interp(grid, seconds, frame[c].to_numpy()) for c in CHANNELS])
        inside = (grid >= TRIM_S) & (grid <= seconds[-1] - TRIM_S)
        recordings.append(Recording(path.name, match["label"], match["stamp"], signal[inside]))
    return recordings, skipped


def make_windows(signal):
    size = int(WINDOW_S * SAMPLE_RATE_HZ)
    stride = int(STRIDE_S * SAMPLE_RATE_HZ)
    starts = range(0, len(signal) - size + 1, stride)
    return np.stack([signal[s:s + size] for s in starts])


def build_dataset(recordings):
    windows, labels, groups = [], [], []
    for recording in recordings:
        chunk = make_windows(recording.signal)
        windows.append(chunk)
        labels.extend([recording.label] * len(chunk))
        groups.extend([recording.name] * len(chunk))
    return Dataset(np.concatenate(windows), np.array(labels), np.array(groups))
