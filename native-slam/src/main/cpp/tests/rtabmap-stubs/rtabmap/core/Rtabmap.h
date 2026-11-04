/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

/**
 * Stand-in for `rtabmap::Rtabmap`: the calls the bridge makes, with upstream's signatures.
 *
 * The three methods that used to be invented here are the point of the file:
 *
 *   * `setParameters(map)` - upstream has no such method; parameters are handed to
 *     `init(parameters, databasePath = "", loadDatabaseParameters = false)`.
 *   * `process(data)` - upstream's overloads all take the **odometry pose** with the data
 *     (`process(const SensorData&, Transform odomPose, …)`); there is no data-only overload for
 *     RGB-D SLAM.
 *   * `setOptimized()` - does not exist anywhere in RTAB-Map. The explicit global pass is
 *     `globalBundleAdjustment(optimizerType = 1, rematchFeatures = true, iterations = 0,
 *     pixelVariance = 0.0f)`.
 *
 * A stub that declares whatever the bridge writes proves only that the bridge is self-consistent
 * (finding 183).
 */
#pragma once

#include <string>

#include "Parameters.h"
#include "SensorData.h"
#include "Transform.h"

namespace rtabmap {

class Rtabmap {
 public:
  /** Upstream: parameters + database path ("" = in-memory) + whether to load stored parameters. */
  void init(const ParametersMap& parameters, const std::string& databasePath = "",
            bool loadDatabaseParameters = false) {
    (void)parameters;
    (void)databasePath;
    (void)loadDatabaseParameters;
  }

  void close(bool databaseSaved = true, const std::string& outputDatabasePath = "") {
    (void)databaseSaved;
    (void)outputDatabasePath;
  }

  /** Upstream: the odometry pose travels with the frame; the covariance is optional. */
  bool process(const SensorData& data, Transform odomPose,
               const cv::Mat& odomCovariance = cv::Mat()) {
    (void)data;
    (void)odomPose;
    (void)odomCovariance;
    return false;
  }

  int getLoopClosureId() const { return 0; }

  bool globalBundleAdjustment(int optimizerType = 1, bool rematchFeatures = true,
                              int iterations = 0, float pixelVariance = 0.0f) {
    (void)optimizerType;
    (void)rematchFeatures;
    (void)iterations;
    (void)pixelVariance;
    return false;
  }
};

}  // namespace rtabmap
