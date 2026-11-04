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

/**
 * Cyclic Jacobi eigen-decomposition of a symmetric 3×3 matrix, in place.
 *
 * On return `a` holds the eigenvalues on its diagonal and `vectors[k]` is the unit eigenvector
 * for `a[k][k]`. Sixteen sweeps is far more than the three or four a 3×3 needs; the loop exits
 * as soon as the off-diagonal mass is below rounding.
 */
void jacobiEigen(float a[3][3], Vec3 vectors[3]) {
  float v[3][3] = {{1.f, 0.f, 0.f}, {0.f, 1.f, 0.f}, {0.f, 0.f, 1.f}};
  for (int sweep = 0; sweep < 16; ++sweep) {
    const float offDiagonal =
        std::fabs(a[0][1]) + std::fabs(a[0][2]) + std::fabs(a[1][2]);
    if (offDiagonal < 1e-10f) break;
    for (int p = 0; p < 2; ++p) {
      for (int q = p + 1; q < 3; ++q) {
        if (std::fabs(a[p][q]) < 1e-12f) continue;
        const float theta = (a[q][q] - a[p][p]) / (2.f * a[p][q]);
        const float sign = theta >= 0.f ? 1.f : -1.f;
        const float t = sign / (std::fabs(theta) + std::sqrt(theta * theta + 1.f));
        const float c = 1.f / std::sqrt(t * t + 1.f);
        const float s = t * c;
        for (int k = 0; k < 3; ++k) {
          const float akp = a[k][p];
          const float akq = a[k][q];
          a[k][p] = c * akp - s * akq;
          a[k][q] = s * akp + c * akq;
        }
        for (int k = 0; k < 3; ++k) {
          const float apk = a[p][k];
          const float aqk = a[q][k];
          a[p][k] = c * apk - s * aqk;
          a[q][k] = s * apk + c * aqk;
        }
        for (int k = 0; k < 3; ++k) {
          const float vkp = v[k][p];
          const float vkq = v[k][q];
          v[k][p] = c * vkp - s * vkq;
          v[k][q] = s * vkp + c * vkq;
        }
      }
    }
  }
  for (int i = 0; i < 3; ++i) {
    vectors[i] = Vec3(v[0][i], v[1][i], v[2][i]);
  }
}

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

  // 1. The dominant direction: the top eigenvector of the weighted scatter. This one is
  //    well-conditioned whenever the room has a floor worth the name, which is why the
  //    degenerate case (below) is the only one that is refused.
  Vec3 eigenvectors[3];
  jacobiEigen(scatter, eigenvectors);
  const float eigenvalues[3] = {scatter[0][0], scatter[1][1], scatter[2][2]};
  int dominantIndex = 0;
  for (int i = 1; i < 3; ++i) {
    if (eigenvalues[i] > eigenvalues[dominantIndex]) dominantIndex = i;
  }
  const Vec3 vertical = eigenvectors[dominantIndex].normalized();
  if (!vertical.isFinite() || std::fabs(vertical.length() - 1.f) > 1e-3f) return frame;

  // How much of the evidence lies *outside* that direction? Floor and ceiling alone leave
  // nothing, and one axis is not a frame: a lone wall determines where a second axis could be,
  // not where it is. Report "keep the measurement" rather than inventing one.
  float perpendicularWeight = 0.f;
  for (size_t i = 0; i < normals.size(); ++i) {
    const float along = normals[i].dot(vertical);
    perpendicularWeight += weights[i] * (1.f - along * along);
  }
  if (perpendicularWeight < options.minDirectionRatio * totalWeight) return frame;
  // Note: floor and ceiling contribute nothing here (their normals *are* the vertical), which is
  // exactly the point - they say where the vertical is and nothing about the other two axes.

  // 2. An orthonormal basis of the perpendicular plane, seeded from whichever world axis is
  //    least aligned with the dominant one, so the basis never degenerates.
  Vec3 seed = std::fabs(vertical.x) < std::fabs(vertical.y)
                  ? (std::fabs(vertical.x) < std::fabs(vertical.z) ? Vec3(1.f, 0.f, 0.f)
                                                                   : Vec3(0.f, 0.f, 1.f))
                  : (std::fabs(vertical.y) < std::fabs(vertical.z) ? Vec3(0.f, 1.f, 0.f)
                                                                   : Vec3(0.f, 0.f, 1.f));
  const Vec3 u = (seed - vertical * seed.dot(vertical)).normalized();
  const Vec3 v = vertical.cross(u).normalized();

  // Each plane's components in the (u, v) basis, so the score below is two multiplies per plane
  // per sample instead of a dot product.
  std::vector<float> alongU;
  std::vector<float> alongV;
  alongU.reserve(normals.size());
  alongV.reserve(normals.size());
  for (const Vec3& normal : normals) {
    alongU.push_back(normal.dot(u));
    alongV.push_back(normal.dot(v));
  }

  // The score a frame is judged by: the turn angle that puts one axis on a wall family, with each
  // plane weighted by a Tukey biweight on the angle between its normal and that axis, falling to
  // zero exactly at the snap tolerance. Two decisions are in here, and both were paid for:
  //
  //  * the Tukey kernel, not an "inside the tolerance or not" cut. A cliff is worse than it
  //    looks: the moment the frame steps just inside the tolerance it collects a whole plane's
  //    weight, so a bay window 35° off square was worth enough at 19.4° to tilt a four-wall room
  //    by 15° - the optimiser finds that cliff, and it is exactly the error this solver exists to
  //    remove.
  //  * inliers, not planes. 40 000 points of wall outvote 300 points of skirting board, which is
  //    the only sane ordering when the two disagree about where "forward" is.
  //
  // The search is over one angle because the frame has one degree of freedom once the dominant
  // direction is fixed: the other axis of the pair is the cross product. Taking *that* axis from
  // the eigen-decomposition instead is the defect this replaced (finding 187) - in a square room
  // the two horizontal eigenvalues are within a few percent, so a single crooked wall decided
  // their directions and the "square" frame came out 15.7° off the walls it was squaring.
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

  // 3. One-dimensional search over the turn of the second axis. Cos² has a 180° period, so a
  //    quarter turn covers every distinct frame; the coarse pass cannot miss a maximum that a
  //    plane family creates, and the golden-section pass pins it to ~1e-4 rad.
  constexpr int kCoarseSteps = 180;
  constexpr float kQuarter = kPiLocal * 0.5f;
  const float step = kQuarter / static_cast<float>(kCoarseSteps);
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
  float phi = step * static_cast<float>(bestStep);
  for (int i = 0; i < 40; ++i) {
    const float left = high - kGolden * (high - low);
    const float right = low + kGolden * (high - low);
    if (score(left) > score(right)) {
      high = right;
    } else {
      low = left;
    }
  }
  phi = 0.5f * (low + high);

  frame.axes[0].direction = vertical;
  frame.axes[1].direction = (u * std::cos(phi) + v * std::sin(phi)).normalized();
  // The third axis is derived, never fitted.
  frame.axes[2].direction = frame.axes[0].direction.cross(frame.axes[1].direction).normalized();

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
