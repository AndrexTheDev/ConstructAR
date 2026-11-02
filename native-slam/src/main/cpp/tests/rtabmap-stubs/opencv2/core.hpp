/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

/**
 * The two OpenCV types `rtabmap_bridge.cpp` touches, enough to compile it.
 *
 * This is a *compile-shape* stub, not OpenCV: `native-tests` builds the RTAB-Map branch of the
 * bridge with these headers so that branch cannot rot while the real dependency (a BSD-clean
 * OpenCV + RTAB-Map checkout) is absent from CI. Nothing here is ever linked into the app; the
 * Android build uses the real libraries or compiles the stub branch (finding 107).
 */
#pragma once

#include <cstddef>

#define CV_8UC1 0
#define CV_8UC3 16
#define CV_16UC1 2
#define CV_32F 5

namespace cv {

class Mat {
 public:
  Mat() = default;
  Mat(int rows, int cols, int type, void* data, std::size_t step)
      : rows_(rows), cols_(cols), type_(type), data_(data), step_(step) {}

  void convertTo(Mat& destination, int type, double scale = 1.0) const {
    destination = *this;
    destination.type_ = type;
    destination.data_ = nullptr;
    destination.step_ = 0;
    (void)scale;
  }

  int rows() const { return rows_; }
  int cols() const { return cols_; }
  int type() const { return type_; }
  void* data() const { return data_; }
  std::size_t step() const { return step_; }

 private:
  int rows_ = 0;
  int cols_ = 0;
  int type_ = 0;
  void* data_ = nullptr;
  std::size_t step_ = 0;
};

}  // namespace cv
