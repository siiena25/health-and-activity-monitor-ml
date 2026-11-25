// On-device inference for the activity model: feature extraction + small neural network.
// Requires model_data.h to be included first.
#include <math.h>

// ---------- Activity recognition core ----------
#define WIN 80          // window length: 4 s of data (80 samples at 20 Hz)
#define HOP 20          // a new prediction every 1 s (20 samples)

const char *const ACTIVITY_NAMES[N_CLASS] = {"sitting", "standing", "walking", "running"};

// w: WIN rows in time order, each row = ax, ay, az (m/s^2), gx, gy, gz (rad/s)
// f: output, N_FEAT (13) features
void computeFeatures(const float w[WIN][6], float *f) {
  float sum[6] = {0}, sq[6] = {0}, mag[WIN];
  float mSum = 0, mSq = 0, mMin = 1e9f, mMax = -1e9f;
  for (int i = 0; i < WIN; i++) {
    for (int c = 0; c < 6; c++) { sum[c] += w[i][c]; sq[c] += w[i][c] * w[i][c]; }
    float m = sqrtf(w[i][0] * w[i][0] + w[i][1] * w[i][1] + w[i][2] * w[i][2]);
    mag[i] = m; mSum += m; mSq += m * m;
    if (m < mMin) mMin = m;
    if (m > mMax) mMax = m;
  }
  float mean[6], sd[6];
  for (int c = 0; c < 6; c++) {
    mean[c] = sum[c] / WIN;
    float v = sq[c] / WIN - mean[c] * mean[c];
    sd[c] = v > 0 ? sqrtf(v) : 0;
  }
  float mMean = mSum / WIN, mVar = mSq / WIN - mMean * mMean;
  int cross = 0;                                   // times the acceleration magnitude crosses its mean (how fast the movement is)
  for (int i = 1; i < WIN; i++)
    if ((mag[i] >= mMean) != (mag[i - 1] >= mMean)) cross++;

  f[0] = mean[0]; f[1] = mean[1]; f[2] = mean[2];  // gravity direction (arm orientation)
  f[3] = sd[0];   f[4] = sd[1];   f[5] = sd[2];    // acceleration variability
  f[6] = sd[3];   f[7] = sd[4];   f[8] = sd[5];    // angular velocity variability
  f[9] = mMean;                                    // acceleration magnitude: mean
  f[10] = mVar > 0 ? sqrtf(mVar) : 0;              //                         standard deviation
  f[11] = mMax - mMin;                             //                         range
  f[12] = (float)cross;                            //                         mean crossings
}

// Returns the index of the predicted class (see ACTIVITY_NAMES)
int classify(const float *f) {
  float x[N_FEAT], h[N_HID];
  for (int i = 0; i < N_FEAT; i++) x[i] = (f[i] - F_MEAN[i]) / F_SCALE[i];   // standardize
  for (int j = 0; j < N_HID; j++) {                                          // hidden layer, ReLU
    float s = NN_B1[j];
    for (int i = 0; i < N_FEAT; i++) s += NN_W1[j * N_FEAT + i] * x[i];
    h[j] = s > 0 ? s : 0;
  }
  int best = 0; float bestScore = -1e9f;                                     // output layer, argmax
  for (int k = 0; k < N_CLASS; k++) {
    float s = NN_B2[k];
    for (int j = 0; j < N_HID; j++) s += NN_W2[k * N_HID + j] * h[j];
    if (s > bestScore) { bestScore = s; best = k; }
  }
  return best;
}
