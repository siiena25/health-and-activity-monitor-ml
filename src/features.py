import numpy as np

from data import SAMPLE_RATE_HZ

SIGNALS = ["ax", "ay", "az", "gx", "gy", "gz", "acc_mag", "gyro_mag"]
ORIENTATION_SIGNALS = {"ax", "ay", "az"}
ORIENTATION_STATS = {"mean", "min", "max", "p25", "p75", "rms"}


def _signals(windows):
    acc_mag = np.linalg.norm(windows[:, :, 0:3], axis=2)
    gyro_mag = np.linalg.norm(windows[:, :, 3:6], axis=2)
    stacked = np.concatenate([windows, acc_mag[:, :, None], gyro_mag[:, :, None]], axis=2)
    return {name: stacked[:, :, i] for i, name in enumerate(SIGNALS)}


def _stats(x):
    centered = x - x.mean(axis=1, keepdims=True)
    spectrum = np.abs(np.fft.rfft(centered, axis=1))
    freqs = np.fft.rfftfreq(x.shape[1], d=1.0 / SAMPLE_RATE_HZ)
    peak = spectrum[:, 1:].argmax(axis=1) + 1
    power = spectrum[:, 1:] ** 2
    probs = power / (power.sum(axis=1, keepdims=True) + 1e-12)
    signs = np.sign(centered)
    return {
        "mean": x.mean(axis=1),
        "std": x.std(axis=1),
        "min": x.min(axis=1),
        "max": x.max(axis=1),
        "p25": np.percentile(x, 25, axis=1),
        "p75": np.percentile(x, 75, axis=1),
        "rms": np.sqrt((x ** 2).mean(axis=1)),
        "mad": np.abs(np.diff(x, axis=1)).mean(axis=1),
        "zcr": (signs[:, 1:] * signs[:, :-1] < 0).mean(axis=1),
        "dom_freq": freqs[peak],
        "dom_amp": spectrum[np.arange(len(x)), peak] / x.shape[1],
        "spec_entropy": -(probs * np.log(probs + 1e-12)).sum(axis=1),
    }


COMPACT_STATS = ["mean", "std", "min", "max", "rms", "mad", "dom_freq", "dom_amp"]


def extract(windows, orientation=True, stats=None):
    names, columns = [], []
    for signal_name, values in _signals(windows).items():
        for stat_name, column in _stats(values).items():
            if stats is not None and stat_name not in stats:
                continue
            is_orientation = signal_name in ORIENTATION_SIGNALS and stat_name in ORIENTATION_STATS
            if is_orientation and not orientation:
                continue
            names.append(f"{signal_name}_{stat_name}")
            columns.append(column)
    return np.column_stack(columns), names
