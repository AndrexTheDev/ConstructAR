/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// Manhattan-World frame recovery — the geometry half of Phase 1.
//
// A room is built from planes that are *meant* to be mutually orthogonal: a floor, a ceiling and
// four walls. The planes we fit from depth are not orthogonal. Each one is off by the noise of
// its own inliers, by the drift of the pose graph, and occasionally by the fact that the wall
// really is bowed. Measured as-is, two opposite walls that should be 4.00 m apart come out 4.03 m
// and the corner is 87.6° instead of 90° — and the error is systematic, so it survives averaging.
//
// The Manhattan assumption removes exactly that error and nothing else:
//   1. find the frame the room is trying to be. The dominant direction of the normals is the
//      top eigenvector of Σ w·n·nᵀ. The remaining degree of freedom is one turn angle inside
//      the plane perpendicular to it, found by a one-dimensional search that maximises a
//      Tukey-weighted count of the inliers lying near that axis; the third axis is the cross
//      product, so the frame is exactly orthogonal and exactly right-handed. Both the search
//      and the kernel are deliberate. Taking the second axis from the eigen-decomposition is
//      wrong exactly when it matters: in a square room the two horizontal families carry almost
//      equal weight, the decomposition is ill-conditioned, and a single crooked wall tilted the
//      "square" pair by 15.7° (finding 187). And a hard "inside the tolerance" cut is a cliff
//      the search will find: a bay window 35° off square collected a whole plane's weight at
//      19.4° and tilted the same room by 15°;
//   2. snap to it only the planes that are already close to an axis. A wall 8° off is drift; a
//      wall 35° off is a bay window or a chamfer, and forcing it square would be a lie, so it is
//      returned untouched with `snapped == false`.
//
// What does *not* change: the plane keeps passing through the points it was fitted to. When the
// caller supplies the inlier centroid, the snapped offset is `axis · centroid` — a wall at x = 4
// stays at x = 4 even though its measured normal was 8° off. Without a centroid the plane is
// rotated about its point nearest the origin, i.e. `offset' = offset · |axis · normal|`, which is
// the best that can be done from (normal, offset) alone and moves the wall by millimetres. Either
// way the plane is the only thing touched: node positions and measured lengths are untouched, and
// this only decides what "square" means when a report quotes an area or a floor-to-ceiling height.
//
// A frame is only reported when it is actually determined: two distinct directions are needed
// (a floor and a wall, say), because one wall determines one axis and a frame from one axis is
// an invention. `ManhattanFrame::valid == false` means "keep the measured geometry", and every
// consumer must then leave its planes alone — `snapPlanes` does exactly that.
#pragma once

#include <vector>

#include "../core/types.hpp"

namespace constructar {

/** A fitted plane: `normal · x = offset`, normal pointing away from the observed surface. */
struct PlaneObservation {
  Vec3 normal{0.f, 1.f, 0.f};
  float offset = 0.f;
  int inliers = 0;
  int roomId = 0;
  /**
   * Centroid of the inlier points, when the fit produced one. It is what makes the snap exact
   * for the wall the user actually measured; `hasCentroid == false` means the snap falls back to
   * rotating about the plane's point nearest the origin.
   */
  Vec3 centroid{0.f, 0.f, 0.f};
  bool hasCentroid = false;

  PlaneObservation() = default;
  PlaneObservation(Vec3 normal_, float offset_, int inliers_, int roomId_ = 0)
      : normal(normal_), offset(offset_), inliers(inliers_), roomId(roomId_) {}
  PlaneObservation(Vec3 normal_, float offset_, int inliers_, Vec3 centroid_)
      : normal(normal_), offset(offset_), inliers(inliers_), centroid(centroid_),
        hasCentroid(true) {}
};

struct ManhattanOptions {
  /** A plane within this cosine of an axis counts as that axis: 0.94 is ~20°. */
  float minAlignment = 0.94f;
  /** Below this many inliers a plane is not evidence of a direction (nor of a wall). */
  int minInliers = 3;
  /** Eigenvalue ratio below which the second direction is considered undetermined. */
  float minDirectionRatio = 1e-4f;
};

struct ManhattanAxis {
  Vec3 direction{1.f, 0.f, 0.f};
  /** Planes that snapped to this axis, and their total inlier weight. */
  int supportingPlanes = 0;
  float weight = 0.f;
};

struct ManhattanFrame {
  ManhattanAxis axes[3];
  /** RMS angle in degrees between a snapped plane and its axis, weighted by inliers. */
  float residualDegrees = 0.f;
  /** False when the planes do not determine two distinct directions; change nothing then. */
  bool valid = false;
};

struct SnappedPlane {
  /** 0..2 for the axis it snapped to, -1 when it was left as measured. */
  int axis = -1;
  Vec3 normal{0.f, 1.f, 0.f};
  float offset = 0.f;
  /** Angle between the measured normal and the axis, in degrees. */
  float angleDegrees = 0.f;
  bool snapped = false;
  int roomId = 0;
  /** The plane as it was measured, for callers that report both. */
  Vec3 measuredNormal{0.f, 1.f, 0.f};
  float measuredOffset = 0.f;
};

/**
 * Recovers the Manhattan frame from plane observations.
 *
 * Planes with a non-finite or zero normal, a non-finite offset, or fewer than
 * `options.minInliers` inliers are ignored — but they keep their index in the input, so
 * `snapPlanes` still returns one entry per observation, in the same order.
 */
ManhattanFrame solveManhattanFrame(const std::vector<PlaneObservation>& planes,
                                   const ManhattanOptions& options = {});

/**
 * Snaps each plane to the frame, or leaves it exactly as measured.
 *
 * The result always has one entry per input plane, in input order. An invalid frame, a plane
 * outside `minAlignment`, and an ignored plane all produce `snapped == false` with the measured
 * normal, offset and room copied through unchanged - bit for bit, so a caller can diff the two
 * and see only the planes that moved.
 */
std::vector<SnappedPlane> snapPlanes(const std::vector<PlaneObservation>& planes,
                                     const ManhattanFrame& frame,
                                     const ManhattanOptions& options = {});

}  // namespace constructar
