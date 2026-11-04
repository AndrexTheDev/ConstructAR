/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// Plane fitting — the step that gives the Manhattan frame something to square.
//
// The room solver (`geometry/manhattan_world.hpp`) works on planes; nothing in the pipeline
// produced planes. This fits them: RANSAC for the consensus set, a covariance eigen-decomposition
// for the plane itself, then the points that plane explains are removed and the next one is
// looked for.
//
// Three decisions are worth stating, because each one is a way to be wrong quietly:
//
//  * **RANSAC is seeded from the options, never from the clock.** A fitter that is different
//    every run cannot be regression-tested, cannot be reproduced by a user reporting a bad room,
//    and makes "it worked on my scan" a true statement about nothing. `PlaneFitOptions::seed`
//    is the only randomness in the file.
//  * **the plane is re-fitted by least squares before it is reported.** A RANSAC triple is three
//    noisy points; the covariance's smallest eigenvector is every inlier. The triple only decides
//    *which* points belong to the plane, never where the plane is.
//  * **a plane has to be a plane.** `planarity` is `1 - λ0 / (λ0 + λ1 + λ2)` and `extentMetres` is
//    `sqrt(λ1 + λ2)`: a sliver of skirting board, a chord across a curved wall and a line of
//    points all fail one of the two, and a line in particular fits infinitely many planes but only
//    one of them is nonsense. Real curved walls do still yield facets — that is what a facet is —
//    which is why the extent floor is an option and not a proof.
//
// The normal's sign is fixed to point *towards the centre of the scanned cloud*, i.e. into the
// room for a scan taken from inside it. The sign carries no information (a plane is a plane either
// way), but a convention means two fits of the same room agree, and the room solver can then
// sign-match a snapped plane to the measurement rather than to the fit's mood.
//
// **Not wired.** Nothing feeds this a cloud yet: the app still measures on ARCore's own tracking
// and the TSDF mesh is drawn, not fitted. This is the engine a scan-side caller will use, and the
// host suite runs the whole chain - cloud, planes, frame, snapped planes - so the day it is wired
// the arithmetic is already proved.
#pragma once

#include <cstdint>
#include <vector>

#include "../core/types.hpp"
#include "manhattan_world.hpp"

namespace constructar {

struct PlaneFitOptions {
  /** How many planes to look for. A room needs six; a corridor needs four. */
  int maxPlanes = 10;
  /** Distance from the plane that counts as belonging to it. 2 cm is the TSDF's voxel size. */
  float distanceThreshold = 0.02f;
  /** A plane smaller than this many points is noise, however well it fits. */
  int minInliers = 80;
  /**
   * RANSAC samples per plane.
   *
   * The number is not a taste. A sample is clean when all three of its points are on the same
   * surface, so with a surface holding a fraction `w` of the cloud that happens with probability
   * `w³`; for 99 % confidence the count is `log(0.01) / log(1 - w³)`. A six-surface room scanned
   * as one cloud has `w ≈ 1/6`, which needs ~990 samples - and a budget below that does not fail
   * *loudly*: it reports the best of too few triples, which is a *facet of a real surface*, so a
   * wall arrives in two pieces and every downstream count is plausible. 1500 is that arithmetic
   * with room for an uneven room, and costs ~30 ms for 5 000 points.
   */
  int ransacIterations = 1500;
  /** `1 - λ0 / (λ0 + λ1 + λ2)` below which the inliers are not flat enough to be a plane. */
  float minPlanarity = 0.995f;
  /** `sqrt(λ1 + λ2)` below which the plane is a sliver, not a surface. */
  float minExtentMetres = 0.15f;
  /** The only source of randomness, so a fit is reproducible from its options alone. */
  std::uint32_t seed = 0x5EED1234u;
};

struct FittedPlane {
  /** Unit normal, pointing towards the centre of the cloud that produced it. */
  Vec3 normal{0.f, 1.f, 0.f};
  /** The plane is `normal · x = offset`. */
  float offset = 0.f;
  int inliers = 0;
  /** Centroid of the inliers: what the room solver squares a plane through. */
  Vec3 centroid;
  /** `sqrt(λ1 + λ2)` of the inlier covariance: how much surface the plane actually covers. */
  float extentMetres = 0.f;
  /** `1 - λ0 / (λ0 + λ1 + λ2)`: 1 for perfectly flat, 0 for a ball. */
  float planarity = 0.f;
};

/**
 * Fits up to `options.maxPlanes` planes to a point cloud, biggest first.
 *
 * Points that are not finite are ignored. The result is deterministic for a given input and
 * options; two calls return identical planes, bit for bit.
 */
std::vector<FittedPlane> fitPlanes(const std::vector<Vec3>& points,
                                   const PlaneFitOptions& options = {});

/**
 * The fitted planes as observations for the Manhattan frame solver, with their centroids, so the
 * solver squares each plane through the points it was actually fitted to.
 */
std::vector<PlaneObservation> asPlaneObservations(const std::vector<FittedPlane>& planes,
                                                  int roomId = 0);

}  // namespace constructar
