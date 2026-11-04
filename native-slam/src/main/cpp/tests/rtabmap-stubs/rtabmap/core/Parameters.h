/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

/**
 * Stand-in for `rtabmap::Parameters`.
 *
 * The *shapes* here are copied from upstream, not from the bridge: this header used to declare
 * the keys as `constexpr const char*`, which is a type RTAB-Map never had, and that is why the
 * bridge could pass `Parameters::kMemIncrementalMemory` as a value and still compile here while
 * the real header would have refused it (finding 183).
 *
 * Upstream generates every key with the RTABMAP_PARAM macro, which expands to a **static member
 * function returning std::string** plus its default and type:
 *
 *     RTABMAP_PARAM(Mem, IncrementalMemory, bool, true, "...")
 *         -> static std::string kMemIncrementalMemory() { return "Mem/IncrementalMemory"; }
 *
 * Only the keys the bridge sets are mirrored. `corelib/include/rtabmap/core/Parameters.h`.
 */
#pragma once

#include <map>
#include <string>

namespace rtabmap {

/**
 * Upstream declares this as a class with an *unscoped* enum:
 * `class Feature2D { public: enum Type { kFeatureSift = 1, kFeatureOrb = 2, … }; }`.
 * The enumerator is `kFeatureOrb` - there is no `kFeatureORB` in RTAB-Map.
 */
struct Feature2D {
  enum Type { kFeatureSift = 1, kFeatureOrb = 2 };
};

struct Parameters {
  static std::string kMemIncrementalMemory() { return "Mem/IncrementalMemory"; }
  static std::string kMemInitWMWithAllNodes() { return "Mem/InitWMWithAllNodes"; }
  static std::string kRGBDEnabled() { return "RGBD/Enabled"; }
  static std::string kRGBDCreateOccupancyGrid() { return "RGBD/CreateOccupancyGrid"; }
  static std::string kKpDetectorStrategy() { return "Kp/DetectorStrategy"; }
  static std::string kKpMaxFeatures() { return "Kp/MaxFeatures"; }
  static std::string kVisMinInliers() { return "Vis/MinInliers"; }
  static std::string kOptimizerStrategy() { return "Optimizer/Strategy"; }
  static std::string kOptimizerIterations() { return "Optimizer/Iterations"; }
};

using ParametersMap = std::map<std::string, std::string>;

}  // namespace rtabmap
