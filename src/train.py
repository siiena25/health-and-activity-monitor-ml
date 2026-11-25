import argparse
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score
from sklearn.model_selection import KFold, LeaveOneGroupOut, cross_val_predict

from data import CLASSES, build_dataset, load_recordings
from features import extract


def make_model():
    return RandomForestClassifier(n_estimators=300, class_weight="balanced", random_state=0, n_jobs=-1)


def evaluate_by_recording(X, y, groups):
    predicted = cross_val_predict(make_model(), X, y, groups=groups, cv=LeaveOneGroupOut())
    return predicted


def evaluate_random_windows(X, y):
    cv = KFold(n_splits=5, shuffle=True, random_state=0)
    return cross_val_predict(make_model(), X, y, cv=cv)


def summarize(title, y, predicted, out):
    accuracy = accuracy_score(y, predicted)
    macro_f1 = f1_score(y, predicted, average="macro")
    out.append(f"== {title}")
    out.append(f"accuracy {accuracy:.3f}   macro-F1 {macro_f1:.3f}")
    return accuracy, macro_f1


def save_confusion(y, predicted, path, title):
    matrix = confusion_matrix(y, predicted, labels=CLASSES, normalize="true")
    fig, ax = plt.subplots(figsize=(5, 4.5))
    image = ax.imshow(matrix, vmin=0, vmax=1, cmap="Blues")
    ax.set_xticks(range(len(CLASSES)), CLASSES, rotation=30)
    ax.set_yticks(range(len(CLASSES)), CLASSES)
    ax.set_xlabel("predicted")
    ax.set_ylabel("true")
    ax.set_title(title)
    for i in range(len(CLASSES)):
        for j in range(len(CLASSES)):
            ax.text(j, i, f"{matrix[i, j]:.2f}", ha="center", va="center",
                    color="white" if matrix[i, j] > 0.5 else "black")
    fig.colorbar(image)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", default="data/raw")
    parser.add_argument("--out", default="reports")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(exist_ok=True)
    recordings, skipped = load_recordings(args.raw)
    dataset = build_dataset(recordings)
    lines = []

    lines.append("== recordings used")
    for r in recordings:
        lines.append(f"{r.name:34s} {r.label:9s} {len(r.signal) / 20:6.1f} s after trimming")
    lines.append("== skipped")
    for name, reason in skipped:
        lines.append(f"{name:34s} {reason}")
    counts = {c: int((dataset.labels == c).sum()) for c in CLASSES}
    lines.append(f"== windows per class (3 s, 50% overlap): {counts}")

    X_full, names = extract(dataset.windows, orientation=True)
    X_free, _ = extract(dataset.windows, orientation=False)
    lines.append(f"== features: {X_full.shape[1]} with orientation, {X_free.shape[1]} without")

    leaky = evaluate_random_windows(X_full, dataset.labels)
    summarize("RANDOM window split (leaky, do NOT trust)", dataset.labels, leaky, lines)

    honest = evaluate_by_recording(X_full, dataset.labels, dataset.groups)
    summarize("leave-one-recording-out, all features", dataset.labels, honest, lines)
    lines.append(classification_report(dataset.labels, honest, labels=CLASSES, digits=3))
    lines.append("confusion (rows true, cols predicted) " + str(CLASSES))
    lines.append(str(confusion_matrix(dataset.labels, honest, labels=CLASSES)))
    save_confusion(dataset.labels, honest, out_dir / "confusion_all_features.png", "Leave-one-recording-out")

    free = evaluate_by_recording(X_free, dataset.labels, dataset.groups)
    summarize("leave-one-recording-out, orientation-free features", dataset.labels, free, lines)
    lines.append(str(confusion_matrix(dataset.labels, free, labels=CLASSES)))
    save_confusion(dataset.labels, free, out_dir / "confusion_orientation_free.png", "Orientation-free")

    lines.append("== per held-out recording accuracy (all features)")
    for group in np.unique(dataset.groups):
        mask = dataset.groups == group
        lines.append(f"{group:34s} {accuracy_score(dataset.labels[mask], honest[mask]):.3f}")

    model = make_model().fit(X_full, dataset.labels)
    order = np.argsort(model.feature_importances_)[::-1][:15]
    lines.append("== top features (trained on everything)")
    for i in order:
        lines.append(f"{names[i]:20s} {model.feature_importances_[i]:.3f}")

    report = "\n".join(lines)
    (out_dir / "results.txt").write_text(report + "\n")
    print(report)


if __name__ == "__main__":
    main()
