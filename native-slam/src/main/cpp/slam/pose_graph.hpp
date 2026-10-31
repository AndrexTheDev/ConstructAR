/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// SE(3) pose graph with Gauss-Newton optimisation.
//
// This is the *loop-closure engine* of the app. It consumes
//   * odometry / keyframe edges (camera → camera between consecutive keyframes) and
//   * loop-closure edges (a revisited place, verified by RTAB-Map or by the proximity
//     proposer when RTAB-Map is not compiled in),
// and returns the globally consistent keyframe poses used to re-anchor every measured node.
//
// Numerical choice: Jacobians are computed by central differences over the 6 twist
// dimensions of each incident node instead of hand-derived SE(3) Jacobians. For the graph
// size a room scan produces (50-500 keyframes, 2-6 edges/node) this costs a few
// milliseconds per iteration and removes an entire class of sign/adjoint bugs — the kind
// that silently bend a wall by 20 mm. The linear system is solved with preconditioned
// conjugate gradients on the block-sparse Hessian, which is exact to rounding, keeps
// memory at O(edges), and never densifies a 3000×3000 matrix.
#pragma once

#include <cstdint>
#include <map>
#include <utility>
#include <vector>

#include "../core/types.hpp"

namespace constructar {

struct PoseGraphNode {
  Mat4 pose;
  bool fixed = false;
};

struct PoseGraphEdge {
  int from = 0;
  int to = 0;
  /** Measured relative transform T_from⁻¹ · T_to. */
  Mat4 measurement;
  /** Diagonal information weights (inverse variance proxies), rotation / translation. */
  double rotationWeight = 1.0;
  double translationWeight = 1.0;
  /** True for loop-closure edges (kept for reporting / colouring in the UI). */
  bool loopClosure = false;
};

/** Per-edge residual report, used by the diagnostics screen. */
struct EdgeResidual {
  int from = 0;
  int to = 0;
  double rotationErrorRadians = 0.0;
  double translationErrorMeters = 0.0;
  bool loopClosure = false;
};

struct OptimizationReport {
  double initialCost = 0.0;
  double finalCost = 0.0;
  /** RMS translation residual over all edges, metres. */
  double rmsTranslationMeters = 0.0;
  double rmsRotationRadians = 0.0;
  int iterations = 0;
  bool converged = false;
  double milliseconds = 0.0;
  std::vector<EdgeResidual> residuals;
};

class PoseGraph {
 public:
  /** Adds a node; returns its index. `fixed` pins it (typically the first keyframe). */
  int addNode(const Mat4& pose, bool fixed = false);

  /** Adds a relative-pose edge (duplicate edges between the same pair are allowed). */
  void addEdge(int from, int to, const Mat4& measurement, double rotationWeight = 1.0,
               double translationWeight = 1.0, bool loopClosure = false);

  /** Runs up to [iterations] Gauss-Newton steps. Mutates the stored node poses. */
  OptimizationReport optimize(int iterations = 12, double convergenceCostDelta = 1e-7);

  size_t nodeCount() const { return nodes_.size(); }
  size_t edgeCount() const { return edges_.size(); }
  const std::vector<PoseGraphNode>& nodes() const { return nodes_; }
  const std::vector<PoseGraphEdge>& edges() const { return edges_; }
  std::vector<Mat4> poses() const;

  void setFixed(int index, bool fixed);
  /** T_from⁻¹ · T_to using the current (optimised) poses. */
  Mat4 relativePose(int from, int to) const;

  /** Sum of squared weighted residuals — the objective minimised by [optimize]. */
  double cost() const;

  void reset();

 private:
  /** Residual vector (6) of one edge, `[rotation, translation]`, in the tangent space. */
  Twist residual(int from, int to, const Mat4& measurement) const;
  double edgeCost(const PoseGraphEdge& edge) const;

  std::vector<PoseGraphNode> nodes_;
  std::vector<PoseGraphEdge> edges_;
};

/**
 * Proximity loop-closure *proposer*.
 *
 * Without RTAB-Map compiled in, the app can still flag "you have walked back past keyframe
 * N" using pose proximity. It only ever *proposes*: a candidate is promoted to a real edge
 * by RTAB-Map's appearance verification (or by the operator, via the UI) — never silently,
 * because a false loop closure folds a room in half.
 */
struct LoopCandidate {
  int from = 0;
  int to = 0;
  double distanceMeters = 0.0;
  double headingErrorRadians = 0.0;
};

class ProximityLoopClosureDetector {
 public:
  struct Config {
    int minIndexGap = 20;             // keyframes apart before a revisit can count
    double maxDistanceMeters = 0.35;  // how close in space counts as "the same place"
    double maxHeadingErrorRadians = 0.5;
  };

  ProximityLoopClosureDetector() = default;
  explicit ProximityLoopClosureDetector(Config config) : config_(config) {}

  std::vector<LoopCandidate> findCandidates(const std::vector<Mat4>& poses) const;

 private:
  Config config_;
};

}  // namespace constructar
