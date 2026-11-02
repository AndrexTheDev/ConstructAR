/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

/** Stand-in for `rtabmap::CameraModel`: the intrinsics constructor the bridge uses. */
#pragma once

#include "Transform.h"

namespace rtabmap {

class CameraModel {
 public:
  CameraModel() = default;
  CameraModel(float fx, float fy, float cx, float cy, float Tx, float Ty, int width, int height,
              const Transform& localTransform = Transform()) {
    (void)fx;
    (void)fy;
    (void)cx;
    (void)cy;
    (void)Tx;
    (void)Ty;
    (void)width;
    (void)height;
    (void)localTransform;
  }
};

}  // namespace rtabmap
