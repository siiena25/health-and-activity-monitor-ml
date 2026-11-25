import argparse
from pathlib import Path

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import LeaveOneGroupOut, cross_val_predict

from data import CLASSES, build_dataset, load_recordings
from features import COMPACT_STATS, extract

TREES = 30
MAX_DEPTH = 6
MIN_LEAF = 3


def make_model():
    return RandomForestClassifier(
        n_estimators=TREES,
        max_depth=MAX_DEPTH,
        min_samples_leaf=MIN_LEAF,
        class_weight="balanced",
        random_state=0,
        n_jobs=-1,
    )


def flatten(model):
    feature, threshold, left, right, leaf, roots = [], [], [], [], [], []
    for estimator in model.estimators_:
        tree = estimator.tree_
        base = len(feature)
        roots.append(base)
        for i in range(tree.node_count):
            is_leaf = tree.children_left[i] == -1
            feature.append(-1 if is_leaf else int(tree.feature[i]))
            threshold.append(0.0 if is_leaf else float(tree.threshold[i]))
            left.append(0 if is_leaf else base + int(tree.children_left[i]))
            right.append(0 if is_leaf else base + int(tree.children_right[i]))
            value = tree.value[i, 0]
            leaf.append((value / value.sum()).tolist())
    return feature, threshold, left, right, leaf, roots


def float_literal(value):
    text = f"{value:.7g}"
    if "." not in text and "e" not in text:
        text += ".0"
    return text + "f"


def render_header(model, feature_count):
    feature, threshold, left, right, leaf, roots = flatten(model)
    classes = ", ".join(f'"{c}"' for c in model.classes_)
    leaf_rows = ",\n".join("  {" + ", ".join(float_literal(p) for p in row) + "}" for row in leaf)
    return f"""#pragma once

#include <stdint.h>

namespace activity_model {{

constexpr int kNumFeatures = {feature_count};
constexpr int kNumClasses = {len(model.classes_)};
constexpr int kNumTrees = {len(roots)};
constexpr int kNumNodes = {len(feature)};

static const char* const kClassNames[kNumClasses] = {{{classes}}};

static const int16_t kTreeRoot[kNumTrees] = {{{", ".join(map(str, roots))}}};

static const int8_t kFeature[kNumNodes] = {{{", ".join(map(str, feature))}}};

static const double kThreshold[kNumNodes] = {{{", ".join(f"{t:.17g}" for t in threshold)}}};

static const int16_t kLeft[kNumNodes] = {{{", ".join(map(str, left))}}};

static const int16_t kRight[kNumNodes] = {{{", ".join(map(str, right))}}};

static const float kLeaf[kNumNodes][kNumClasses] = {{
{leaf_rows}
}};

}}
"""


def write_test_vectors(path, windows, features, model):
    probabilities = model.predict_proba(features.astype(np.float32))
    labels = probabilities.argmax(axis=1)
    flat = windows.reshape(len(windows), -1)
    rows = np.hstack([flat, features, labels[:, None], probabilities])
    np.savetxt(path, rows, delimiter=",", fmt="%.9g")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", default="data/raw")
    parser.add_argument("--out", default="export")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(exist_ok=True)
    recordings, _ = load_recordings(args.raw)
    dataset = build_dataset(recordings)
    X, names = extract(dataset.windows, orientation=True, stats=COMPACT_STATS)
    assert list(CLASSES) == sorted(CLASSES)

    predicted = cross_val_predict(make_model(), X, dataset.labels, groups=dataset.groups, cv=LeaveOneGroupOut())
    print(f"leave-one-recording-out: accuracy {accuracy_score(dataset.labels, predicted):.3f}, "
          f"macro-F1 {f1_score(dataset.labels, predicted, average='macro'):.3f}")

    model = make_model().fit(X, dataset.labels)
    (out_dir / "activity_model.h").write_text(render_header(model, X.shape[1]))
    (out_dir / "feature_names.txt").write_text("\n".join(names) + "\n")
    write_test_vectors(out_dir / "test_vectors.csv", dataset.windows, X, model)
    nodes = sum(e.tree_.node_count for e in model.estimators_)
    print(f"features {X.shape[1]}, trees {TREES}, nodes {nodes}, classes {list(model.classes_)}")


if __name__ == "__main__":
    main()
