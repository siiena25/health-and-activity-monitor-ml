#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "activity_classifier.h"

namespace {

constexpr int kValuesPerWindow = activity::kWindow * activity::kChannels;
constexpr int kFeatures = activity_model::kNumFeatures;
constexpr int kClasses = activity_model::kNumClasses;

std::vector<double> parseRow(const std::string& line) {
  std::vector<double> values;
  std::stringstream stream(line);
  std::string cell;
  while (std::getline(stream, cell, ',')) values.push_back(std::strtod(cell.c_str(), nullptr));
  return values;
}

void toWindow(const std::vector<double>& row, float window[activity::kWindow][activity::kChannels]) {
  for (int i = 0; i < activity::kWindow; ++i) {
    for (int c = 0; c < activity::kChannels; ++c) window[i][c] = (float)row[i * activity::kChannels + c];
  }
}

}

int main(int argc, char** argv) {
  if (argc < 2) {
    std::fprintf(stderr, "usage: host_test test_vectors.csv\n");
    return 2;
  }
  std::ifstream file(argv[1]);
  std::vector<std::vector<double>> rows;
  std::string line;
  while (std::getline(file, line)) {
    if (!line.empty()) rows.push_back(parseRow(line));
  }

  int labelMismatch = 0;
  double worstFeature = 0.0;
  double worstConfidence = 0.0;
  for (const auto& row : rows) {
    float window[activity::kWindow][activity::kChannels];
    toWindow(row, window);
    float features[kFeatures];
    activity::computeFeatures(window, features);
    for (int f = 0; f < kFeatures; ++f) {
      const double expected = row[kValuesPerWindow + f];
      const double error = std::fabs(features[f] - expected) / (1.0 + std::fabs(expected));
      if (error > worstFeature) worstFeature = error;
    }
    const activity::Result result = activity::classify(features);
    const int expectedLabel = (int)row[kValuesPerWindow + kFeatures];
    double expectedConfidence = 0.0;
    for (int c = 0; c < kClasses; ++c) {
      const double p = row[kValuesPerWindow + kFeatures + 1 + c];
      if (p > expectedConfidence) expectedConfidence = p;
    }
    if (result.label != expectedLabel) ++labelMismatch;
    const double confidenceError = std::fabs(result.confidence - expectedConfidence);
    if (confidenceError > worstConfidence) worstConfidence = confidenceError;
  }

  int streamingMismatch = 0;
  int streamingChecked = 0;
  if (rows.size() >= 2) {
    activity::Classifier classifier;
    float first[activity::kWindow][activity::kChannels];
    float second[activity::kWindow][activity::kChannels];
    toWindow(rows[0], first);
    toWindow(rows[1], second);
    activity::Result result{};
    int emitted = 0;
    activity::Result outputs[2];
    for (int i = 0; i < activity::kWindow; ++i) {
      if (classifier.push(first[i], &result)) outputs[emitted++] = result;
    }
    for (int i = activity::kWindow - activity::kStride; i < activity::kWindow; ++i) {
      if (classifier.push(second[i], &result) && emitted < 2) outputs[emitted++] = result;
    }
    streamingChecked = emitted;
    for (int k = 0; k < emitted; ++k) {
      if (outputs[k].label != (int)rows[k][kValuesPerWindow + kFeatures]) ++streamingMismatch;
    }
  }

  std::printf("windows checked        %zu\n", rows.size());
  std::printf("label mismatches       %d\n", labelMismatch);
  std::printf("worst feature error    %.2e (relative)\n", worstFeature);
  std::printf("worst confidence error %.2e\n", worstConfidence);
  std::printf("streaming outputs      %d, mismatches %d\n", streamingChecked, streamingMismatch);
  std::printf("model: %d trees, %d nodes\n", activity_model::kNumTrees, activity_model::kNumNodes);
  const bool ok = labelMismatch == 0 && worstFeature < 1e-4 && worstConfidence < 1e-4 &&
                  streamingChecked == 2 && streamingMismatch == 0;
  std::printf("%s\n", ok ? "PASS" : "FAIL");
  return ok ? 0 : 1;
}
