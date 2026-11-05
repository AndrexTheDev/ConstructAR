/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// See the header for why the mask exists and what it refuses to guess.
#include "surface_mask.hpp"

#include <cmath>

namespace constructar {

WallMask maskVerticalSurfaces(const std::vector<OrientedPoint>& slice,
                              const SurfaceMaskOptions& options) {
  WallMask mask;
  const float tilt = std::min(std::max(options.maxTiltFromVerticalDegrees, 0.f), 90.f);
  // A surface tilted `tilt` from vertical has |n·up| = sin(tilt); anything more upright than that
  // in the up direction is horizontal enough to be furniture or floor.
  const float cosineLimit = std::sin(tilt * kPi / 180.f);

  for (const OrientedPoint& point : slice) {
    if (!point.position.isFinite()) continue;
    if (!point.normal.isFinite() || point.normal.lengthSquared() < 1e-8f) {
      ++mask.unoriented;
      continue;
    }
    const Vec3 normal = point.normal.normalized();
    if (std::fabs(normal.y) > cosineLimit) {
      ++mask.horizontal;
      continue;
    }
    ++mask.walls;
    mask.points.push_back(SlicePoint{point.position.x, point.position.z});
  }

  const int total = mask.walls + mask.horizontal + mask.unoriented;
  mask.keptFraction = total > 0 ? static_cast<float>(mask.walls) / static_cast<float>(total) : 0.f;
  mask.valid = mask.walls > 0;
  return mask;
}

}  // namespace constructar
