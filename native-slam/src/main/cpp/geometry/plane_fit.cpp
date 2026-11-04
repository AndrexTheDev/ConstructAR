/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// RANSAC -> least squares -> remove -> repeat. See the header for the decisions.
//
// The generator is xorshift32 rather than `std::mt19937` for one reason: it is six lines, its
// state is the seed, and the host suite can therefore assert that the same seed gives the same
// planes on every compiler and every optimisation level. A Mersenne twister would be equally
// deterministic and harder to keep that way across toolchains.
#include "plane_fit.hpp"

#include <algorithm>
#include <cmath>

#include "../core/eigen3.hpp"

namespace constructar {
namespace {

/** xorshift32: the whole of this file's randomness. */
class Xorshift32 {
 public:
  explicit Xorshift32(std::uint32_t seed) : state_(seed == 0u ? 0x9E3779B9u : seed) {}

  std::uint32_t next() {
    state_ ^= state_ << 13;
    state_ ^= state_ >> 17;
    state_ ^= state_ << 5;
    return state_;
  }

  /** Uniform-ish index in [0, bound). The modulo bias is irrelevant here and stated instead. */
  std::size_t index(std::size_t bound) { return static_cast<std::size_t>(next()) % bound; }

 private:
  std::uint32_t state_;
};

Vec3 centroidOf(const std::vector<Vec3>& points, const std::vector<char>& used) {
  Vec3 sum;
  int count = 0;
  for (std::size_t i = 0; i < points.size(); ++i) {
    if (!used[i]) continue;
    sum += points[i];
    ++count;
  }
  return count > 0 ? sum / static_cast<float>(count) : Vec3();
}

/** Least-squares plane through the marked points, with the statistics that judge it. */
bool refinePlane(const std::vector<Vec3>& points, const std::vector<char>& used,
                 FittedPlane& plane) {
  const Vec3 centroid = centroidOf(points, used);
  float covariance[3][3] = {{0.f, 0.f, 0.f}, {0.f, 0.f, 0.f}, {0.f, 0.f, 0.f}};
  int count = 0;
  for (std::size_t i = 0; i < points.size(); ++i) {
    if (!used[i]) continue;
    const Vec3 d = points[i] - centroid;
    covariance[0][0] += d.x * d.x;
    covariance[0][1] += d.x * d.y;
    covariance[0][2] += d.x * d.z;
    covariance[1][1] += d.y * d.y;
    covariance[1][2] += d.y * d.z;
    covariance[2][2] += d.z * d.z;
    ++count;
  }
  if (count < 3) return false;
  covariance[1][0] = covariance[0][1];
  covariance[2][0] = covariance[0][2];
  covariance[2][1] = covariance[1][2];
  const float scale = 1.f / static_cast<float>(count);
  for (int row = 0; row < 3; ++row) {
    for (int col = 0; col < 3; ++col) covariance[row][col] *= scale;
  }

  const Eigen3 eigen = eigenDecomposeSymmetric(covariance);
  const float total = eigen.values[0] + eigen.values[1] + eigen.values[2];
  if (!(total > 0.f)) return false;

  plane.normal = eigen.vectors[0].normalized();
  plane.offset = plane.normal.dot(centroid);
  plane.centroid = centroid;
  plane.inliers = count;
  plane.planarity = 1.f - eigen.values[0] / total;
  plane.extentMetres = std::sqrt(std::max(0.f, eigen.values[1] + eigen.values[2]));
  return true;
}

}  // namespace

std::vector<FittedPlane> fitPlanes(const std::vector<Vec3>& points,
                                   const PlaneFitOptions& options) {
  std::vector<FittedPlane> planes;
  std::vector<Vec3> cloud;
  cloud.reserve(points.size());
  for (const Vec3& point : points) {
    if (point.isFinite()) cloud.push_back(point);
  }
  if (cloud.size() < 3) return planes;

  // Where "into the room" points. Computed once, from every point, before any of them is claimed.
  const std::vector<char> everything(cloud.size(), 1);
  const Vec3 cloudCentre = centroidOf(cloud, everything);

  Xorshift32 random(options.seed);
  std::vector<char> used(cloud.size(), 0);
  std::vector<char> best(cloud.size(), 0);
  int remaining = static_cast<int>(cloud.size());

  // `attempts` counts candidates tried, not planes reported: a candidate that is refused spends
  // its consensus and the next attempt looks elsewhere, so the loop is bounded by the number of
  // *rejections* the fit is willing to absorb rather than by the number of surfaces it reports.
  int reported = 0;
  const int maxAttempts = options.maxPlanes + 8;
  // A refused candidate's points leave the cloud, so the same set cannot be proposed again - that
  // is what bounds the loop - and the fit carries on instead of ending. Both refusals below spend
  // and continue, and this lambda is the only place that behaves differently from reporting.
  const auto spendConsensus = [&used, &best, &remaining, &cloud](void) {
    for (std::size_t i = 0; i < cloud.size(); ++i) {
      if (best[i]) {
        used[i] = 1;
        --remaining;
      }
    }
  };
  for (int attempt = 0; attempt < maxAttempts && reported < options.maxPlanes; ++attempt) {
    if (remaining < options.minInliers) break;

    int bestCount = 0;
    for (int iteration = 0; iteration < options.ransacIterations; ++iteration) {
      const std::size_t i0 = random.index(cloud.size());
      const std::size_t i1 = random.index(cloud.size());
      const std::size_t i2 = random.index(cloud.size());
      if (i0 == i1 || i0 == i2 || i1 == i2) continue;
      const Vec3 a = cloud[i0];
      const Vec3 b = cloud[i1];
      const Vec3 c = cloud[i2];
      const Vec3 normal = (b - a).cross(c - a);
      const float length = normal.length();
      if (!(length > 1e-8f)) continue;
      const Vec3 unit = normal / length;
      const float offset = unit.dot(a);

      int count = 0;
      for (std::size_t i = 0; i < cloud.size(); ++i) {
        if (used[i]) continue;
        if (std::fabs(unit.dot(cloud[i]) - offset) <= options.distanceThreshold) ++count;
      }
      if (count > bestCount) {
        bestCount = count;
        for (std::size_t i = 0; i < cloud.size(); ++i) {
          best[i] = static_cast<char>(
              !used[i] && std::fabs(unit.dot(cloud[i]) - offset) <= options.distanceThreshold);
        }
      }
      if (bestCount == remaining) break;  // everything left is on this plane
    }

    if (bestCount < options.minInliers) break;

    FittedPlane candidate;
    if (!refinePlane(cloud, best, candidate)) break;
    if (candidate.inliers < options.minInliers ||
        candidate.planarity < options.minPlanarity ||
        candidate.extentMetres < options.minExtentMetres) {
      // The consensus set is not a plane - a chord across a curved wall, a line of points, a
      // sliver. It is *spent* rather than abandoned: its points leave the cloud so the same set
      // cannot be proposed again (that is what bounds the loop), and the next attempt looks for
      // the next surface. Ending the fit here was wrong in the obvious way (finding 191): one
      // curved wall in front of a straight one cost the room every plane behind it, and the
      // caller cannot tell "no planes" from "the first candidate was refused".
      spendConsensus();
      continue;
    }

    // Sign convention: point at the middle of the cloud, i.e. into the room.
    if (candidate.normal.dot(cloudCentre - candidate.centroid) < 0.f) {
      candidate.normal = -candidate.normal;
      candidate.offset = -candidate.offset;
    }

    // Re-open the consensus set against the refined plane: the triple's threshold and the fitted
    // plane's are not the same set, and reporting the triple's count would overstate the support.
    int inliers = 0;
    for (std::size_t i = 0; i < cloud.size(); ++i) {
      if (used[i]) continue;
      const bool onPlane =
          std::fabs(candidate.normal.dot(cloud[i]) - candidate.offset) <= options.distanceThreshold;
      best[i] = static_cast<char>(onPlane);
      if (onPlane) ++inliers;
    }
    if (inliers < options.minInliers) {
      // Same rule as above: the refined plane explains too little, so those points are spent and
      // the fit carries on rather than reporting nothing.
      spendConsensus();
      continue;
    }
    candidate.inliers = inliers;

    for (std::size_t i = 0; i < cloud.size(); ++i) {
      if (best[i]) {
        used[i] = 1;
        --remaining;
      }
    }
    planes.push_back(candidate);
    ++reported;
  }
  return planes;
}

std::vector<PlaneObservation> asPlaneObservations(const std::vector<FittedPlane>& planes,
                                                  int roomId) {
  std::vector<PlaneObservation> observations;
  observations.reserve(planes.size());
  for (const FittedPlane& plane : planes) {
    observations.push_back(
        PlaneObservation(plane.normal, plane.offset, plane.inliers, plane.centroid));
    observations.back().roomId = roomId;
  }
  return observations;
}

}  // namespace constructar
