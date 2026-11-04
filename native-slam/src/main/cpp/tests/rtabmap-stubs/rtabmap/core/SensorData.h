/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

/**
 * Stand-in for `rtabmap::SensorData`.
 *
 * Mirrors upstream's RGB-D constructor (`corelib/include/rtabmap/core/SensorData.h`):
 *
 *     SensorData(const cv::Mat & rgb, const cv::Mat & depth, const CameraModel & cameraModel,
 *                int id = 0, double stamp = 0.0, const cv::Mat & userData = cv::Mat());
 *
 * Note what the sixth parameter is: a user-data **matrix**, not an odometry pose. There is no
 * odometry argument and no setter - upstream takes the pose in `Rtabmap::process(data, odomPose)`,
 * which is where the bridge now hands it over. This stub used to declare `const Transform &
 * odomPose` in that position, which is what made finding 107's "fix" look right (finding 183).
 */
#pragma once

#include <opencv2/core.hpp>

#include "CameraModel.h"

namespace rtabmap {

class SensorData {
 public:
  SensorData() = default;
  SensorData(const cv::Mat& rgb, const cv::Mat& depth, const CameraModel& cameraModel, int id = 0,
             double stamp = 0.0, const cv::Mat& userData = cv::Mat()) {
    (void)rgb;
    (void)depth;
    (void)cameraModel;
    (void)id;
    (void)stamp;
    (void)userData;
  }
};

}  // namespace rtabmap
