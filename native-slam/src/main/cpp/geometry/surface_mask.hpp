/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// Semantic surface masking for the waist-height slice: keep the walls, drop what a cup could
// stand on.
//
// A slice at 1.0 m is not a slice through a room's walls. It cuts the table the operator was
// measuring over, the kitchen counter, the workbench, the top of a radiator, and - if the height
// estimate was off by half a metre - the floor slab's own truncation band. Every one of those is a
// *horizontal* patch, and a 2D vectoriser fed one of them draws a wall across the middle of the
// room, doubles the room's wall area in the material estimate, and puts a partition in the plan
// that the customer will read as a wall they own.
//
// The normal is the only thing that separates them, and it is a cheap thing to keep: the slice
// already comes out of a TSDF, whose gradient *is* the surface normal. So the rule is geometric
// and has one number in it - how far off vertical a surface may lean and still be a wall. Twenty
// degrees is the default because a real wall is within a couple of degrees of vertical and the
// TSDF gradient on a noisy patch wanders by five; a table is ninety degrees away, not twenty.
//
// What this deliberately does not do: decide what a *doorway* is, classify furniture it cannot
// see the top of, or use the camera's up vector. It looks at normals only.
#ifndef CONSTRUCTAR_GEOMETRY_SURFACE_MASK_HPP
#define CONSTRUCTAR_GEOMETRY_SURFACE_MASK_HPP

#include <vector>

#include "../core/types.hpp"
#include "floorplan.hpp"

namespace constructar {

struct SurfaceMaskOptions {
  /**
   * A surface whose normal is within this angle of horizontal is dropped, in degrees.
   *
   * 0 would keep only exactly-vertical surfaces, which no measured wall is; 90 would keep the
   * ceiling. Negative values are treated as 0.
   */
  float maxTiltFromVerticalDegrees = 20.f;
};

/** What the mask kept, and what it threw away - the numbers a diagnostic wants. */
struct WallMask {
  /** The kept points, projected to the floor plane (y dropped), in input order. */
  std::vector<SlicePoint> points;
  int walls = 0;
  int horizontal = 0;
  /** Points whose normal could not be measured (a zero or non-finite normal). */
  int unoriented = 0;
  /** `walls / total`, 0 for an empty slice. */
  float keptFraction = 0.f;
  /** True when at least one wall point survived. */
  bool valid = false;
};

/**
 * Keeps the vertical surfaces of a slice and drops the horizontal ones.
 *
 * A point whose normal is zero or not finite is *not* kept and *is* counted as unoriented: the
 * mask has no evidence that it is a wall, and admitting it would let unmeasured voxels reintroduce
 * exactly the phantoms this exists to remove. The count is published so that a slice consisting
 * mostly of unmeasured voxels is visible as such instead of looking like a clean wall set.
 */
WallMask maskVerticalSurfaces(const std::vector<OrientedPoint>& slice,
                              const SurfaceMaskOptions& options = {});

}  // namespace constructar

#endif  // CONSTRUCTAR_GEOMETRY_SURFACE_MASK_HPP
