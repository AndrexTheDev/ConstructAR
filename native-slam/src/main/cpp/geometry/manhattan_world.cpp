/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// Manhattan-World frame recovery — see the header for why this exists and what it refuses to do.
//
// The frame is the eigen-decomposition of the weighted scatter matrix Σ w·n·nᵀ, which is the
// least-squares set of directions three orthogonal axes can take through a cloud of normals:
// maximise Σ w·(n·a)² subject to the axes being orthonormal. For a symmetric 3×3 matrix that is
// solved by cyclic Jacobi — a few sweeps of plane rotations, no Eigen, no LAPACK, exact to
// rounding, and deterministic for a given input (which the tests pin).
//
// The weights are inlier counts rather than plane counts: a 40 000-point wall is better evidence
// of where "forward" is than a 300-point sliver of skirting board, and the two disagree by more
// than the tolerance whenever the sliver is the one that is crooked.
#include "manhattan_world.hpp"

#include <algorithm>
#include <cmath>

#include "../core/eigen3.hpp"

namespace constructar {
namespace {

constexpr float kTiny = 1e-8f;
constexpr float kRadToDeg = 57.29577951308232f;
constexpr float kPiLocal = 3.14159265358979323846f;

/** A plane is evidence of a direction only if it has inliers and a usable normal. */
bool isEvidence(const PlaneObservation& plane, const ManhattanOptions& options) {
  if (plane.inliers < options.minInliers) return false;
  if (!plane.normal.isFinite() || !std::isfinite(plane.offset)) return false;
  return plane.normal.lengthSquared() > kTiny;
}

float clampCosine(float value) { return value > 1.f ? 1.f : (value < -1.f ? -1.f : value); }

/** Index of the axis a normal is closest to, or -1 when it is outside the tolerance. */
int nearestAxis(const Vec3& normal, const ManhattanFrame& frame, float minAlignment,
                float* alignedOut) {
  int best = -1;
  float bestAlignment = -1.f;
  for (int i = 0; i < 3; ++i) {
    const float alignment = std::fabs(normal.dot(frame.axes[i].direction));
    // Strict `>` keeps ties on the lowest axis index, so the result does not depend on the
    // floating-point evaluation order of equal candidates.
    if (alignment > bestAlignment) {
      bestAlignment = alignment;
      best = i;
    }
  }
  if (alignedOut != nullptr) *alignedOut = clampCosine(bestAlignment);
  return bestAlignment >= minAlignment ? best : -1;
}

}  // namespace

namespace {

/** The frame that `primary` implies: the axis itself, one searched axis, and the cross product. */
bool buildFrameFor(const Vec3& primary, const std::vector<Vec3>& normals,
                   const std::vector<float>& weights, const ManhattanOptions& options,
                   ManhattanFrame& frame) {
  if (!primary.isFinite() || std::fabs(primary.length() - 1.f) > 1e-3f) return false;
  const Vec3 vertical = primary.normalized();

  // An orthonormal basis of the plane perpendicular to the primary, seeded from whichever world
  // axis is least aligned with it, so the basis never degenerates.
  const Vec3 seed = std::fabs(vertical.x) < std::fabs(vertical.y)
                        ? (std::fabs(vertical.x) < std::fabs(vertical.z) ? Vec3(1.f, 0.f, 0.f)
                                                                        : Vec3(0.f, 0.f, 1.f))
                        : (std::fabs(vertical.y) < std::fabs(vertical.z) ? Vec3(0.f, 1.f, 0.f)
                                                                        : Vec3(0.f, 0.f, 1.f));
  const Vec3 u = (seed - vertical * seed.dot(vertical)).normalized();
  const Vec3 v = vertical.cross(u).normalized();

  std::vector<float> alongU;
  std::vector<float> alongV;
  alongU.reserve(normals.size());
  alongV.reserve(normals.size());
  for (const Vec3& normal : normals) {
    alongU.push_back(normal.dot(u));
    alongV.push_back(normal.dot(v));
  }

  // The score one axis is judged by: its Tukey-weighted support among the inliers. Two decisions
  // are in here, and both were paid for:
  //
  //  * the Tukey kernel, not an "inside the tolerance or not" cut. A cliff is worse than it
  //    looks: the moment the axis steps just inside the tolerance it collects a whole plane's
  //    weight, so a bay window 35° off square was worth enough at 19.4° to tilt a four-wall room
  //    by 15° - the optimiser finds that cliff.
  //  * inliers, not planes. 40 000 points of wall outvote 300 points of skirting board.
  const float toleranceAngle = std::acos(clampCosine(options.minAlignment));
  const auto score = [&alongU, &alongV, &weights, toleranceAngle](float phi) {
    const float c = std::cos(phi);
    const float s = std::sin(phi);
    float total = 0.f;
    for (size_t i = 0; i < alongU.size(); ++i) {
      const float aligned = std::fabs(alongU[i] * c + alongV[i] * s);
      if (aligned <= 1e-6f) continue;  // a floor normal says nothing about the other two axes
      const float angle = std::acos(aligned > 1.f ? 1.f : aligned);
      if (angle >= toleranceAngle) continue;
      const float ratio = angle / toleranceAngle;
      const float weight = 1.f - ratio * ratio;
      total += weights[i] * weight * weight;
    }
    return total;
  };

  // One-dimensional search over the turn of the second axis: the primary is fixed, so the frame
  // has exactly one degree of freedom and the third axis is the cross product.
  constexpr int kCoarseSteps = 180;
  const float step = (kPiLocal * 0.5f) / static_cast<float>(kCoarseSteps);
  int bestStep = 0;
  float bestScore = -1.f;
  for (int i = 0; i < kCoarseSteps; ++i) {
    const float value = score(step * static_cast<float>(i));
    if (value > bestScore) {
      bestScore = value;
      bestStep = i;
    }
  }
  float low = step * static_cast<float>(bestStep) - step;
  float high = step * static_cast<float>(bestStep) + step;
  constexpr float kGolden = 0.6180339887498949f;
  for (int i = 0; i < 40; ++i) {
    const float left = high - kGolden * (high - low);
    const float right = low + kGolden * (high - low);
    if (score(left) > score(right)) {
      high = right;
    } else {
      low = left;
    }
  }
  const float phi = 0.5f * (low + high);

  frame = ManhattanFrame{};
  frame.axes[0].direction = vertical;
  frame.axes[1].direction = (u * std::cos(phi) + v * std::sin(phi)).normalized();
  frame.axes[2].direction = frame.axes[0].direction.cross(frame.axes[1].direction).normalized();
  return true;
}

/** How well a whole frame explains the planes: each plane voted for its nearest of the three. */
float frameSupport(const ManhattanFrame& frame, const std::vector<Vec3>& normals,
                   const std::vector<float>& weights, const ManhattanOptions& options) {
  const float toleranceAngle = std::acos(clampCosine(options.minAlignment));
  float total = 0.f;
  for (size_t i = 0; i < normals.size(); ++i) {
    float aligned = 0.f;
    for (int axis = 0; axis < 3; ++axis) {
      aligned = std::max(aligned, std::fabs(normals[i].dot(frame.axes[axis].direction)));
    }
    if (aligned <= 1e-6f) continue;  // a floor normal says nothing about the other two axes
    const float angle = std::acos(clampCosine(aligned));
    if (angle >= toleranceAngle) continue;
    const float ratio = angle / toleranceAngle;
    const float weight = 1.f - ratio * ratio;
    total += weights[i] * weight * weight;
  }
  return total;
}

}  // namespace

ManhattanFrame solveManhattanFrame(const std::vector<PlaneObservation>& planes,
                                   const ManhattanOptions& options) {
  ManhattanFrame frame;

  // The usable planes, once: unit normals and inlier weights. Everything below works on this.
  std::vector<Vec3> normals;
  std::vector<float> weights;
  normals.reserve(planes.size());
  weights.reserve(planes.size());
  float scatter[3][3] = {{0.f, 0.f, 0.f}, {0.f, 0.f, 0.f}, {0.f, 0.f, 0.f}};
  for (const PlaneObservation& plane : planes) {
    if (!isEvidence(plane, options)) continue;
    const Vec3 n = plane.normal.normalized();
    const float w = static_cast<float>(plane.inliers);
    normals.push_back(n);
    weights.push_back(w);
    for (int row = 0; row < 3; ++row) {
      for (int col = 0; col < 3; ++col) scatter[row][col] += w * n[row] * n[col];
    }
  }
  if (normals.empty()) return frame;

  float totalWeight = 0.f;
  for (float w : weights) totalWeight += w;
  if (totalWeight <= 0.f) return frame;
  if (scatter[0][0] + scatter[1][1] + scatter[2][2] <= kTiny) return frame;

  // Three candidate directions, not one. The eigen-decomposition's top vector is *not* the
  // vertical in any room whose floor and walls carry comparable weight: the two horizontal
  // families couple through one crooked wall, and their rotated pair can out-eigenvalue the
  // vertical outright - a 4 x 3 m room sampled evenly put the top vector 35 degrees off vertical
  // (finding 189). So each eigenvector is tried as the primary, each gets the full search for its
  // perpendicular pair, and the frame that explains the most inliers wins. The frame is a *set*
  // of three axes; which of them is called vertical is what the room decides, not the eigenvalues.
  const Eigen3 eigen = eigenDecomposeSymmetric(scatter);
  float bestSupport = -1.f;
  for (int candidate = 0; candidate < 3; ++candidate) {
    ManhattanFrame attempt;
    if (!buildFrameFor(eigen.vectors[candidate], normals, weights, options, attempt)) continue;

    // One direction is not a frame. Floor and ceiling alone leave nothing perpendicular, and a
    // lone wall determines where a second axis could be, not where it is: refuse rather than
    // invent.
    float perpendicularWeight = 0.f;
    for (size_t i = 0; i < normals.size(); ++i) {
      const float along = normals[i].dot(attempt.axes[0].direction);
      perpendicularWeight += weights[i] * (1.f - along * along);
    }
    if (perpendicularWeight < options.minDirectionRatio * totalWeight) continue;

    const float support = frameSupport(attempt, normals, weights, options);
    if (support > bestSupport) {
      bestSupport = support;
      frame = attempt;
    }
  }
  if (bestSupport < 0.f) return frame;  // no candidate determined a frame

  double weightedSquaredDegrees = 0.0;
  double assignedWeight = 0.0;
  for (size_t i = 0; i < normals.size(); ++i) {
    float aligned = 0.f;
    const int axis = nearestAxis(normals[i], frame, options.minAlignment, &aligned);
    if (axis < 0) continue;
    const float degrees = std::acos(clampCosine(aligned)) * kRadToDeg;
    ManhattanAxis& entry = frame.axes[axis];
    ++entry.supportingPlanes;
    entry.weight += weights[i];
    weightedSquaredDegrees += static_cast<double>(weights[i]) * degrees * degrees;
    assignedWeight += static_cast<double>(weights[i]);
  }
  if (assignedWeight > 0.0) {
    frame.residualDegrees = static_cast<float>(std::sqrt(weightedSquaredDegrees / assignedWeight));
  }

  // A frame is determined when at least two of its three axes are evidenced. A floor and a
  // ceiling give one: the vertical, and then *any* pair of horizontal directions explains the
  // data equally well, which is a frame with a free parameter and not a frame. Three axes from a
  // normal room, two from a corridor whose vertical is fixed by two perpendicular walls - both
  // are determined, and both are accepted.
  int supportedAxes = 0;
  for (const ManhattanAxis& axis : frame.axes) {
    if (axis.supportingPlanes > 0) ++supportedAxes;
  }
  if (supportedAxes < 2) return ManhattanFrame{};
  frame.valid = true;
  return frame;
}

std::vector<SnappedPlane> snapPlanes(const std::vector<PlaneObservation>& planes,
                                     const ManhattanFrame& frame,
                                     const ManhattanOptions& options) {
  std::vector<SnappedPlane> snapped(planes.size());
  for (size_t i = 0; i < planes.size(); ++i) {
    const PlaneObservation& plane = planes[i];
    SnappedPlane& out = snapped[i];
    // A plane that is not snapped comes back exactly as it was measured - same normal, same
    // offset, same room - so a caller can diff the two and see only the planes that moved.
    out.measuredNormal = plane.normal;
    out.measuredOffset = plane.offset;
    out.normal = plane.normal;
    out.offset = plane.offset;
    out.roomId = plane.roomId;

    if (!frame.valid || !isEvidence(plane, options)) continue;

    const float length = plane.normal.length();
    const Vec3 unitNormal = plane.normal / length;
    // `offset` is defined against the unit normal; a non-unit input is rescaled with it rather
    // than silently interpreted as a distance.
    const float unitOffset = plane.offset / length;

    float aligned = 0.f;
    const int axis = nearestAxis(unitNormal, frame, options.minAlignment, &aligned);
    if (axis < 0) continue;

    const Vec3 direction = frame.axes[axis].direction;
    // Keep the sign the measurement had: a wall normal pointing into the room stays pointing
    // into the room, so callers that test "inside" keep working.
    const float sign = unitNormal.dot(direction) >= 0.f ? 1.f : -1.f;
    out.normal = direction * sign;
    // Two ways to place the square plane, both through the measured surface:
    //   * with a centroid, through that point - `a·x = a·c`, which keeps a wall at the distance
    //     the user measured rather than at that distance times a cosine;
    //   * without one, rotated about the closest point to the origin: `d·|a·n|`.
    // An exactly aligned plane is left bit-for-bit unchanged either way.
    out.offset = plane.hasCentroid ? out.normal.dot(plane.centroid) : unitOffset * aligned;
    out.axis = axis;
    out.angleDegrees = std::acos(clampCosine(aligned)) * kRadToDeg;
    out.snapped = true;
  }
  return snapped;
}

}  // namespace constructar
