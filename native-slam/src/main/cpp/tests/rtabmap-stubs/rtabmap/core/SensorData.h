/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

/** Stand-in for `rtabmap::SensorData`, mirroring the RGB-D constructor the bridge uses. */
#pragma once

#include <cstdint>
#include <opencv2/core.hpp>

#include "CameraModel.h"
#include "Transform.h"

namespace rtabmap {

class SensorData {
 public:
  SensorData() = default;
  SensorData(const cv::Mat& rgb, const cv::Mat& depth, const CameraModel& cameraModel, int id = 0,
             double stamp = 0.0, const Transform& odomPose = Transform()) {
    (void)rgb;
    (void)depth;
    (void)cameraModel;
    (void)id;
    (void)stamp;
    (void)odomPose;
  }
};

}  // namespace rtabmap
