#pragma once

#include <math.h>
#include <stdint.h>

#include "activity_model.h"

namespace activity {

constexpr int kWindow = 60;
constexpr int kStride = 30;
constexpr int kChannels = 6;
constexpr int kSignals = 8;
constexpr int kStatsPerSignal = 8;
constexpr double kSampleRateHz = 20.0;
constexpr double kPi = 3.14159265358979323846;

static_assert(kSignals * kStatsPerSignal == activity_model::kNumFeatures, "feature count mismatch");

struct Result {
  int label;
  float confidence;
  const char* name;
};

namespace detail {

inline const double* trigTable() {
  static double table[kWindow * 2];
  static bool ready = false;
  if (!ready) {
    for (int i = 0; i < kWindow; ++i) {
      const double angle = 2.0 * kPi * i / kWindow;
      table[i] = cos(angle);
      table[kWindow + i] = sin(angle);
    }
    ready = true;
  }
  return table;
}

inline void signalFeatures(const double* x, float* out) {
  const double* cosTable = trigTable();
  const double* sinTable = cosTable + kWindow;

  double sum = 0.0, squares = 0.0, lo = x[0], hi = x[0];
  for (int i = 0; i < kWindow; ++i) {
    sum += x[i];
    squares += x[i] * x[i];
    if (x[i] < lo) lo = x[i];
    if (x[i] > hi) hi = x[i];
  }
  const double mean = sum / kWindow;

  double centered[kWindow];
  double variance = 0.0;
  for (int i = 0; i < kWindow; ++i) {
    centered[i] = x[i] - mean;
    variance += centered[i] * centered[i];
  }

  double diffs = 0.0;
  for (int i = 1; i < kWindow; ++i) diffs += fabs(x[i] - x[i - 1]);

  int peak = 1;
  double peakMagnitude = -1.0;
  for (int k = 1; k <= kWindow / 2; ++k) {
    double re = 0.0, im = 0.0;
    for (int i = 0; i < kWindow; ++i) {
      const int phase = (k * i) % kWindow;
      re += centered[i] * cosTable[phase];
      im -= centered[i] * sinTable[phase];
    }
    const double magnitude = sqrt(re * re + im * im);
    if (magnitude > peakMagnitude) {
      peakMagnitude = magnitude;
      peak = k;
    }
  }

  out[0] = (float)mean;
  out[1] = (float)sqrt(variance / kWindow);
  out[2] = (float)lo;
  out[3] = (float)hi;
  out[4] = (float)sqrt(squares / kWindow);
  out[5] = (float)(diffs / (kWindow - 1));
  out[6] = (float)(peak * kSampleRateHz / kWindow);
  out[7] = (float)(peakMagnitude / kWindow);
}

}

inline void computeFeatures(const float window[kWindow][kChannels], float* features) {
  double signals[kSignals][kWindow];
  for (int i = 0; i < kWindow; ++i) {
    for (int c = 0; c < kChannels; ++c) signals[c][i] = window[i][c];
    signals[6][i] = sqrt((double)window[i][0] * window[i][0] + (double)window[i][1] * window[i][1] +
                         (double)window[i][2] * window[i][2]);
    signals[7][i] = sqrt((double)window[i][3] * window[i][3] + (double)window[i][4] * window[i][4] +
                         (double)window[i][5] * window[i][5]);
  }
  for (int s = 0; s < kSignals; ++s) detail::signalFeatures(signals[s], features + s * kStatsPerSignal);
}

inline Result classify(const float* features) {
  using namespace activity_model;
  float votes[kNumClasses] = {0.0f};
  for (int t = 0; t < kNumTrees; ++t) {
    int node = kTreeRoot[t];
    while (kFeature[node] >= 0) {
      node = ((double)features[kFeature[node]] <= kThreshold[node]) ? kLeft[node] : kRight[node];
    }
    for (int c = 0; c < kNumClasses; ++c) votes[c] += kLeaf[node][c];
  }
  int best = 0;
  for (int c = 1; c < kNumClasses; ++c) {
    if (votes[c] > votes[best]) best = c;
  }
  return Result{best, votes[best] / kNumTrees, kClassNames[best]};
}

class Classifier {
 public:
  bool push(const float sample[kChannels], Result* result) {
    for (int c = 0; c < kChannels; ++c) buffer_[head_][c] = sample[c];
    head_ = (head_ + 1) % kWindow;
    if (count_ < kWindow) ++count_;
    ++sinceLast_;
    if (count_ < kWindow || sinceLast_ < kStride) return false;
    sinceLast_ = 0;

    float ordered[kWindow][kChannels];
    for (int i = 0; i < kWindow; ++i) {
      const int source = (head_ + i) % kWindow;
      for (int c = 0; c < kChannels; ++c) ordered[i][c] = buffer_[source][c];
    }
    float features[activity_model::kNumFeatures];
    computeFeatures(ordered, features);
    *result = classify(features);
    return true;
  }

  void reset() {
    head_ = 0;
    count_ = 0;
    sinceLast_ = 0;
  }

 private:
  float buffer_[kWindow][kChannels] = {};
  int head_ = 0;
  int count_ = 0;
  int sinceLast_ = 0;
};

}
