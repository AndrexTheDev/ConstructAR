/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

/** Stand-in for the handful of `rtabmap::Parameters` keys the bridge sets. */
#pragma once

#include <map>
#include <string>

namespace rtabmap {

struct Feature2D {
  enum class Type { kFeatureORB = 0 };
};

struct Parameters {
  static constexpr const char* kMemIncrementalMemory = "Mem/IncrementalMemory";
  static constexpr const char* kMemInitWMWithAllNodes = "Mem/InitWMWithAllNodes";
  static constexpr const char* kRGBDEnabled = "RGBD/Enabled";
  static constexpr const char* kRGBDCreateOccupancyGrid = "RGBD/CreateOccupancyGrid";
  static constexpr const char* kKpDetectorStrategy = "Kp/DetectorStrategy";
  static constexpr const char* kKpMaxFeatures = "Kp/MaxFeatures";
  static constexpr const char* kVisMinInliers = "Vis/MinInliers";
  static constexpr const char* kOptimizerStrategy = "Optimizer/Strategy";
  static constexpr const char* kOptimizerIterations = "Optimizer/Iterations";
};

using ParametersMap = std::map<std::string, std::string>;

}  // namespace rtabmap
