/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

/** Stand-in for `rtabmap::Transform`, mirroring the constructor the bridge uses. */
#pragma once

namespace rtabmap {

/**
 * The 12-float constructor reads a ROW-major 3x4 matrix - (r11, r12, r13, o14, r21, ...) - which
 * is exactly the signature in introlab/rtabmap `corelib/include/rtabmap/core/Transform.h` and
 * the reason the bridge converts its column-major `Mat4` by hand (finding 107).
 */
class Transform {
 public:
  Transform() = default;
  Transform(float, float, float, float, float, float, float, float, float, float, float, float) {}
  static Transform getIdentity() { return Transform(); }
};

}  // namespace rtabmap
