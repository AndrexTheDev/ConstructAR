/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

/**
 * Stand-in for `rtabmap::CameraModel`.
 *
 * Mirrors upstream's minimal-intrinsics constructor, parameter for parameter
 * (`corelib/include/rtabmap/core/CameraModel.h`):
 *
 *     CameraModel(double fx, double fy, double cx, double cy,
 *                 const Transform & localTransform = opticalRotation(),
 *                 double Tx = 0.0f,
 *                 const cv::Size & imageSize = cv::Size(0,0));
 *
 * The stub used to take nine arguments ending in `int width, int height` and a local transform -
 * a signature that does not exist upstream, and that the bridge had been written against
 * (finding 183).
 */
#pragma once

#include <opencv2/core.hpp>

#include "Transform.h"

namespace rtabmap {

class CameraModel {
 public:
  /** Upstream: the camera's optical rotation, `Transform(0,0,1,0, -1,0,0,0, 0,-1,0,0)`. */
  static Transform opticalRotation() { return Transform(0, 0, 1, 0, -1, 0, 0, 0, 0, -1, 0, 0); }

  CameraModel(double fx = 0.0, double fy = 0.0, double cx = 0.0, double cy = 0.0,
              const Transform& localTransform = opticalRotation(), double Tx = 0.0,
              const cv::Size& imageSize = cv::Size(0, 0)) {
    (void)fx;
    (void)fy;
    (void)cx;
    (void)cy;
    (void)localTransform;
    (void)Tx;
    (void)imageSize;
  }
};

}  // namespace rtabmap
