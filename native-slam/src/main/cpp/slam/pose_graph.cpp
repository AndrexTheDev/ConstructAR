/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

#include "pose_graph.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <unordered_map>
#include <utility>

namespace constructar {
namespace {

using Block6 = std::array<double, 36>;

inline void blockZero(Block6& m) { m.fill(0.0); }

/** dst += Jᵀ · diag(weights) · S, where J and S are 6×6 Jacobian blocks. */
inline void addWeightedCross(Block6& dst, const double* j, const double* s, const double weights[6]) {
  for (int r = 0; r < 6; ++r) {
    for (int c = 0; c < 6; ++c) {
      double sum = 0.0;
      for (int k = 0; k < 6; ++k) sum += j[k * 6 + r] * weights[k] * s[k * 6 + c];
      dst[r * 6 + c] += sum;
    }
  }
}

inline void blockVector(const Block6& m, const double* x, double* out) {
  for (int r = 0; r < 6; ++r) {
    double sum = 0.0;
    for (int c = 0; c < 6; ++c) sum += m[r * 6 + c] * x[c];
    out[r] = sum;
  }
}

/**
 * Solves a 6×6 system by Gaussian elimination with partial pivoting.
 * Returns false when the block is singular (a node with no constraint in some direction).
 */
bool solve6(const Block6& a, const double* b, double* x) {
  double m[6][7];
  for (int r = 0; r < 6; ++r) {
    for (int c = 0; c < 6; ++c) m[r][c] = a[r * 6 + c];
    m[r][6] = b[r];
  }
  for (int col = 0; col < 6; ++col) {
    int pivot = col;
    double best = std::fabs(m[col][col]);
    for (int r = col + 1; r < 6; ++r) {
      if (std::fabs(m[r][col]) > best) {
        best = std::fabs(m[r][col]);
        pivot = r;
      }
    }
    if (best < 1e-12) return false;
    if (pivot != col) {
      for (int c = col; c < 7; ++c) std::swap(m[col][c], m[pivot][c]);
    }
    const double inv = 1.0 / m[col][col];
    for (int r = col + 1; r < 6; ++r) {
      const double factor = m[r][col] * inv;
      if (factor == 0.0) continue;
      for (int c = col; c < 7; ++c) m[r][c] -= factor * m[col][c];
    }
  }
  for (int r = 5; r >= 0; --r) {
    double sum = m[r][6];
    for (int c = r + 1; c < 6; ++c) sum -= m[r][c] * x[c];
    x[r] = sum / m[r][r];
  }
  return true;
}

inline Twist twistFromArray(const double* values) {
  return Twist{Vec3(static_cast<float>(values[0]), static_cast<float>(values[1]), static_cast<float>(values[2])),
               Vec3(static_cast<float>(values[3]), static_cast<float>(values[4]), static_cast<float>(values[5]))};
}

inline void twistToArray(const Twist& t, double* out) {
  out[0] = t.omega.x;
  out[1] = t.omega.y;
  out[2] = t.omega.z;
  out[3] = t.upsilon.x;
  out[4] = t.upsilon.y;
  out[5] = t.upsilon.z;
}

inline int64_t blockKey(int i, int j) {
  return (static_cast<int64_t>(i) << 32) | static_cast<uint32_t>(j);
}

std::pair<double, double> odometryRms(const std::vector<PoseGraphEdge>& edges,
                                      const std::vector<Mat4>& poses) {
  double translationSum = 0.0;
  double rotationSum = 0.0;
  size_t count = 0;
  for (const auto& edge : edges) {
    if (edge.loopClosure) continue;
    const Mat4 relative = poses[static_cast<size_t>(edge.from)].inverseRigid() *
                          poses[static_cast<size_t>(edge.to)];
    const Twist r = se3Log(edge.measurement.inverseRigid() * relative);
    translationSum += r.upsilon.lengthSquared();
    rotationSum += r.omega.lengthSquared();
    ++count;
  }
  const double divisor = static_cast<double>(std::max<size_t>(1, count));
  return {std::sqrt(translationSum / divisor), std::sqrt(rotationSum / divisor)};
}

}  // namespace

int PoseGraph::addNode(const Mat4& pose, bool fixed) {
  nodes_.push_back(PoseGraphNode{pose, fixed});
  return static_cast<int>(nodes_.size()) - 1;
}

void PoseGraph::addEdge(int from, int to, const Mat4& measurement, double rotationWeight,
                        double translationWeight, bool loopClosure) {
  if (from == to) return;
  if (from < 0 || to < 0 || from >= static_cast<int>(nodes_.size()) ||
      to >= static_cast<int>(nodes_.size())) {
    return;
  }
  edges_.push_back(
      PoseGraphEdge{from, to, measurement, rotationWeight, translationWeight, loopClosure});
}

std::vector<Mat4> PoseGraph::poses() const {
  std::vector<Mat4> out;
  out.reserve(nodes_.size());
  for (const auto& node : nodes_) out.push_back(node.pose);
  return out;
}

void PoseGraph::setFixed(int index, bool fixed) {
  if (index < 0 || index >= static_cast<int>(nodes_.size())) return;
  nodes_[static_cast<size_t>(index)].fixed = fixed;
}

Mat4 PoseGraph::relativePose(int from, int to) const {
  const Mat4 inv = nodes_[static_cast<size_t>(from)].pose.inverseRigid();
  return inv * nodes_[static_cast<size_t>(to)].pose;
}

Twist PoseGraph::residual(int from, int to, const Mat4& measurement) const {
  // r = log(T_ij⁻¹ · T_i⁻¹ · T_j)
  return se3Log(measurement.inverseRigid() * relativePose(from, to));
}

double PoseGraph::edgeCost(const PoseGraphEdge& edge) const {
  const Twist r = residual(edge.from, edge.to, edge.measurement);
  return edge.rotationWeight * static_cast<double>(r.omega.lengthSquared()) +
         edge.translationWeight * static_cast<double>(r.upsilon.lengthSquared());
}

double PoseGraph::cost() const {
  double sum = 0.0;
  for (const auto& edge : edges_) sum += edgeCost(edge);
  return sum;
}

double PoseGraph::weightedCost(const std::vector<double>& edgeWeights) const {
  double sum = 0.0;
  for (size_t i = 0; i < edges_.size(); ++i) sum += edgeWeights[i] * edgeCost(edges_[i]);
  return sum;
}

void PoseGraph::fillResiduals(OptimizationReport& report,
                              const std::vector<double>& edgeWeights) const {
  double translationSum = 0.0;
  double rotationSum = 0.0;
  size_t activeEdges = 0;
  report.residuals.clear();
  report.residuals.reserve(edges_.size());
  for (size_t edgeIndex = 0; edgeIndex < edges_.size(); ++edgeIndex) {
    const auto& edge = edges_[edgeIndex];
    const Twist r = residual(edge.from, edge.to, edge.measurement);
    const double translation = r.upsilon.length();
    const double rotation = r.omega.length();
    if (edgeWeights[edgeIndex] > 0.0) {
      translationSum += translation * translation;
      rotationSum += rotation * rotation;
      ++activeEdges;
    }
    report.residuals.push_back(EdgeResidual{edge.from, edge.to, rotation, translation,
                                            edge.loopClosure, edgeWeights[edgeIndex]});
  }
  const double count = static_cast<double>(std::max<size_t>(1, activeEdges));
  report.rmsTranslationMeters = std::sqrt(translationSum / count);
  report.rmsRotationRadians = std::sqrt(rotationSum / count);
}

void PoseGraph::reset() {
  nodes_.clear();
  edges_.clear();
}

OptimizationReport PoseGraph::optimizeInPlace(const std::vector<double>& edgeWeights,
                                               int iterations, double convergenceCostDelta) {
  OptimizationReport report;
  const auto started = std::chrono::steady_clock::now();
  if (nodes_.size() < 2 || edges_.empty()) {
    report.converged = true;
    return report;
  }

  // Variable ordering: only free nodes have unknowns (fixed nodes pin the gauge).
  std::vector<int> variableOf(nodes_.size(), -1);
  int variableCount = 0;
  for (size_t i = 0; i < nodes_.size(); ++i) {
    if (!nodes_[i].fixed) variableOf[i] = variableCount++;
  }
  if (variableCount == 0) {
    report.converged = true;
    return report;
  }

  const int dim = variableCount * 6;
  double currentCost = weightedCost(edgeWeights);
  report.initialCost = currentCost;

  constexpr double kEpsilon = 1e-5;  // central-difference step (radians / metres)

  for (int iteration = 0; iteration < iterations; ++iteration) {
    std::unordered_map<int64_t, Block6> hessian;
    hessian.reserve(edges_.size() * 3);
    std::vector<double> gradient(static_cast<size_t>(dim), 0.0);

    for (size_t edgeIndex = 0; edgeIndex < edges_.size(); ++edgeIndex) {
      const auto& edge = edges_[edgeIndex];
      const double robustWeight = edgeWeights[edgeIndex];
      if (robustWeight <= 0.0) continue;
      const int vi = variableOf[static_cast<size_t>(edge.from)];
      const int vj = variableOf[static_cast<size_t>(edge.to)];
      if (vi < 0 && vj < 0) continue;

      const double weights[6] = {
          edge.rotationWeight * robustWeight, edge.rotationWeight * robustWeight,
          edge.rotationWeight * robustWeight, edge.translationWeight * robustWeight,
          edge.translationWeight * robustWeight, edge.translationWeight * robustWeight};

      Mat4& poseFrom = nodes_[static_cast<size_t>(edge.from)].pose;
      Mat4& poseTo = nodes_[static_cast<size_t>(edge.to)].pose;

      double r0[6];
      twistToArray(residual(edge.from, edge.to, edge.measurement), r0);

      double jFrom[36];
      double jTo[36];
      std::memset(jFrom, 0, sizeof(jFrom));
      std::memset(jTo, 0, sizeof(jTo));

      // Central-difference Jacobians in the right-multiplied tangent space.
      for (int k = 0; k < 6; ++k) {
        std::array<double, 6> delta{};
        delta[static_cast<size_t>(k)] = kEpsilon;
        const Mat4 step = se3Exp(twistFromArray(delta.data()));
        const Mat4 stepInverse = step.inverseRigid();

        if (vi >= 0) {
          const Mat4 saved = poseFrom;
          double plus[6];
          double minus[6];
          poseFrom = saved * step;
          twistToArray(residual(edge.from, edge.to, edge.measurement), plus);
          poseFrom = saved * stepInverse;
          twistToArray(residual(edge.from, edge.to, edge.measurement), minus);
          poseFrom = saved;
          for (int row = 0; row < 6; ++row) {
            jFrom[row * 6 + k] = (plus[row] - minus[row]) / (2.0 * kEpsilon);
          }
        }

        if (vj >= 0) {
          const Mat4 saved = poseTo;
          double plus[6];
          double minus[6];
          poseTo = saved * step;
          twistToArray(residual(edge.from, edge.to, edge.measurement), plus);
          poseTo = saved * stepInverse;
          twistToArray(residual(edge.from, edge.to, edge.measurement), minus);
          poseTo = saved;
          for (int row = 0; row < 6; ++row) {
            jTo[row * 6 + k] = (plus[row] - minus[row]) / (2.0 * kEpsilon);
          }
        }
      }

      auto hessianBlock = [&hessian](int i, int j) -> Block6& {
        const int64_t key = blockKey(i, j);
        auto it = hessian.find(key);
        if (it == hessian.end()) {
          Block6 block;
          blockZero(block);
          it = hessian.emplace(key, block).first;
        }
        return it->second;
      };

      if (vi >= 0) {
        addWeightedCross(hessianBlock(vi, vi), jFrom, jFrom, weights);
        for (int row = 0; row < 6; ++row) {
          double sum = 0.0;
          for (int k = 0; k < 6; ++k) sum += jFrom[k * 6 + row] * weights[k] * r0[k];
          gradient[static_cast<size_t>(vi * 6 + row)] += sum;
        }
      }
      if (vj >= 0) {
        addWeightedCross(hessianBlock(vj, vj), jTo, jTo, weights);
        for (int row = 0; row < 6; ++row) {
          double sum = 0.0;
          for (int k = 0; k < 6; ++k) sum += jTo[k * 6 + row] * weights[k] * r0[k];
          gradient[static_cast<size_t>(vj * 6 + row)] += sum;
        }
      }
      if (vi >= 0 && vj >= 0) {
        addWeightedCross(hessianBlock(vi, vj), jFrom, jTo, weights);
        addWeightedCross(hessianBlock(vj, vi), jTo, jFrom, weights);
      }
    }

    // Block-Jacobi preconditioner: invert each 6×6 diagonal block once per iteration.
    std::vector<Block6> inverseDiagonal(static_cast<size_t>(variableCount));
    for (int i = 0; i < variableCount; ++i) {
      Block6& inv = inverseDiagonal[static_cast<size_t>(i)];
      blockZero(inv);
      const auto it = hessian.find(blockKey(i, i));
      if (it == hessian.end()) continue;
      for (int column = 0; column < 6; ++column) {
        std::array<double, 6> unit{};
        unit[static_cast<size_t>(column)] = 1.0;
        double solution[6] = {0, 0, 0, 0, 0, 0};
        if (solve6(it->second, unit.data(), solution)) {
          for (int row = 0; row < 6; ++row) inv[row * 6 + column] = solution[row];
        }
      }
    }

    // Solve H·δ = −g with preconditioned conjugate gradients.
    std::vector<double> b(static_cast<size_t>(dim));
    for (int i = 0; i < dim; ++i) b[static_cast<size_t>(i)] = -gradient[static_cast<size_t>(i)];

    auto applyHessian = [&hessian](const std::vector<double>& x, std::vector<double>& y) {
      std::fill(y.begin(), y.end(), 0.0);
      for (const auto& entry : hessian) {
        const int i = static_cast<int>(entry.first >> 32);
        const int j = static_cast<int>(static_cast<uint32_t>(entry.first));
        double accumulated[6];
        blockVector(entry.second, &x[static_cast<size_t>(j * 6)], accumulated);
        for (int r = 0; r < 6; ++r) y[static_cast<size_t>(i * 6 + r)] += accumulated[r];
      }
    };

    std::vector<double> x(static_cast<size_t>(dim), 0.0);
    std::vector<double> residualVector(b);
    std::vector<double> z(static_cast<size_t>(dim), 0.0);
    for (int i = 0; i < variableCount; ++i) {
      blockVector(inverseDiagonal[static_cast<size_t>(i)], &b[static_cast<size_t>(i * 6)],
                  &z[static_cast<size_t>(i * 6)]);
    }
    std::vector<double> direction(z);
    double rz = 0.0;
    for (int i = 0; i < dim; ++i) {
      rz += residualVector[static_cast<size_t>(i)] * z[static_cast<size_t>(i)];
    }
    std::vector<double> hDirection(static_cast<size_t>(dim), 0.0);
    const int maxCgIterations = std::min(dim, 400);

    for (int cg = 0; cg < maxCgIterations; ++cg) {
      applyHessian(direction, hDirection);
      double dHd = 0.0;
      for (int i = 0; i < dim; ++i) {
        dHd += direction[static_cast<size_t>(i)] * hDirection[static_cast<size_t>(i)];
      }
      if (dHd <= 1e-18) break;
      const double alpha = rz / dHd;
      for (int i = 0; i < dim; ++i) {
        x[static_cast<size_t>(i)] += alpha * direction[static_cast<size_t>(i)];
        residualVector[static_cast<size_t>(i)] -= alpha * hDirection[static_cast<size_t>(i)];
      }
      for (int i = 0; i < variableCount; ++i) {
        blockVector(inverseDiagonal[static_cast<size_t>(i)],
                    &residualVector[static_cast<size_t>(i * 6)], &z[static_cast<size_t>(i * 6)]);
      }
      double rzNew = 0.0;
      for (int i = 0; i < dim; ++i) {
        rzNew += residualVector[static_cast<size_t>(i)] * z[static_cast<size_t>(i)];
      }
      if (std::fabs(rzNew) < 1e-16) break;
      const double beta = rzNew / rz;
      rz = rzNew;
      for (int i = 0; i < dim; ++i) {
        direction[static_cast<size_t>(i)] =
            z[static_cast<size_t>(i)] + beta * direction[static_cast<size_t>(i)];
      }
    }

    // Apply the update with a backtracking line search: a full Gauss-Newton step can
    // overshoot on a poorly-conditioned graph (a room with one verified closure is exactly
    // that), so halve it up to four times until the cost improves. Without this the
    // optimiser stops early and leaves the trajectory visibly bowed.
    const std::vector<Mat4> before = poses();
    double stepScale = 1.0;
    double newCost = currentCost;
    for (int attempt = 0; attempt < 5; ++attempt) {
      for (size_t i = 0; i < nodes_.size(); ++i) {
        const int variable = variableOf[i];
        if (variable < 0) {
          nodes_[i].pose = before[i];
          continue;
        }
        std::array<double, 6> delta{};
        for (int k = 0; k < 6; ++k) {
          delta[static_cast<size_t>(k)] = x[static_cast<size_t>(variable * 6 + k)] * stepScale;
        }
        nodes_[i].pose = before[i] * se3Exp(twistFromArray(delta.data()));
      }
      newCost = weightedCost(edgeWeights);
      if (std::isfinite(newCost) && newCost <= currentCost + 1e-9) break;
      stepScale *= 0.5;
    }

    report.iterations = iteration + 1;
    if (!std::isfinite(newCost) || newCost > currentCost + 1e-9) {
      report.rejectedCostBaseline = currentCost;
      report.rejectedCostIncrease = newCost - currentCost;
      for (size_t i = 0; i < nodes_.size(); ++i) nodes_[i].pose = before[i];
      // Finite-difference Jacobians and float poses eventually make a descent direction
      // indistinguishable from numerical noise. A rejected step whose relative cost increase
      // is below 1 ppm is stationarity, not a diverged graph; a material increase still fails.
      const double stationaryTolerance = std::max(1e-6, std::fabs(currentCost) * 1e-6);
      if (std::isfinite(newCost) && newCost - currentCost <= stationaryTolerance) {
        report.converged = true;
      }
      break;
    }
    const double improvement = currentCost - newCost;
    currentCost = newCost;
    if (improvement < convergenceCostDelta) {
      report.converged = true;
      break;
    }
  }

  report.finalCost = currentCost;
  fillResiduals(report, edgeWeights);

  const auto finished = std::chrono::steady_clock::now();
  report.milliseconds =
      std::chrono::duration_cast<std::chrono::microseconds>(finished - started).count() / 1000.0;
  return report;
}

OptimizationReport PoseGraph::optimize(int iterations, double convergenceCostDelta) {
  const auto started = std::chrono::steady_clock::now();
  const std::vector<Mat4> original = poses();
  const double initialCost = cost();
  std::vector<double> weights(edges_.size(), 1.0);
  OptimizationReport report;
  report.initialCost = initialCost;
  report.finalCost = initialCost;

  auto elapsedMilliseconds = [&started]() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now() - started)
               .count() /
           1000.0;
  };
  auto rollback = [&](OptimizationFailure failure) {
    for (size_t i = 0; i < nodes_.size(); ++i) nodes_[i].pose = original[i];
    report.initialCost = initialCost;
    report.finalCost = cost();
    report.converged = false;
    report.rolledBack = true;
    report.failure = failure;
    report.milliseconds = elapsedMilliseconds();
    fillResiduals(report, weights);
    return report;
  };
  if (!std::isfinite(initialCost)) return rollback(OptimizationFailure::NON_FINITE_RESULT);
  if (iterations <= 0) return rollback(OptimizationFailure::INVALID_INPUT);
  if (nodes_.size() < 2 || edges_.empty()) {
    report.converged = true;
    fillResiduals(report, weights);
    return report;
  }

  // GNC-TLS, super-linear schedule. `error` follows the conventional 0.5*r²
  // convention; c=50 therefore admits a combined 0.1 m/rad residual at the
  // closure's normal weight of 100. Odometry is a known inlier and never scaled.
  constexpr double kInlierCost = 50.0;
  constexpr double kLambdaStep = 1.4;
  constexpr int kMaxOuterIterations = 16;
  double lambda = std::numeric_limits<double>::infinity();
  bool hasLoop = false;
  for (const auto& edge : edges_) {
    if (!edge.loopClosure) continue;
    hasLoop = true;
    const double error = 0.5 * edgeCost(edge);
    const double denominator = 2.0 * error - kInlierCost;
    if (denominator > 0.0) lambda = std::min(lambda, kInlierCost / denominator);
  }
  const bool robust = hasLoop && std::isfinite(lambda) && lambda > 0.0;
  if (robust) lambda = std::max(lambda, 1e-6);

  auto updateWeights = [&]() {
    const double mu = robust ? lambda / (1.0 + lambda) : 0.0;
    bool binary = true;
    for (size_t i = 0; i < edges_.size(); ++i) {
      if (!edges_[i].loopClosure || !robust) {
        weights[i] = 1.0;
        continue;
      }
      const double error = 0.5 * edgeCost(edges_[i]);
      double weight = 0.0;
      if (error <= kInlierCost) {
        weight = 1.0;
      } else if (mu < 1.0 && error < kInlierCost / (mu * mu)) {
        weight = (std::sqrt(kInlierCost / error) - mu) / (1.0 - mu);
        weight = std::max(0.0, std::min(1.0, weight));
      }
      weights[i] = weight;
      if (std::fabs(weight - std::round(weight)) > 1e-4) binary = false;
    }
    return binary;
  };

  int totalIterations = 0;
  bool binaryWeights = !robust;
  const int outerCount = robust ? kMaxOuterIterations : 1;
  for (int outer = 0; outer < outerCount; ++outer) {
    binaryWeights = updateWeights();
    report = optimizeInPlace(weights, iterations, convergenceCostDelta);
    totalIterations += report.iterations;
    if (binaryWeights || !robust) break;
    // Super-linear GNC schedule: sqrt below one, ordinary multiplication above it.
    lambda = lambda < 1.0 ? std::sqrt(lambda) * kLambdaStep : lambda * kLambdaStep;
    lambda = std::min(lambda, 1e12);
  }

  report.initialCost = initialCost;
  report.finalCost = weightedCost(weights);
  report.iterations = totalIterations;
  const bool finiteResult = std::isfinite(report.finalCost) &&
                            std::isfinite(report.rmsTranslationMeters) &&
                            std::isfinite(report.rmsRotationRadians);
  const bool nonWorsening = report.finalCost <=
                            initialCost + std::max(1e-6, std::fabs(initialCost) * 1e-6);
  if (!report.converged) return rollback(OptimizationFailure::NOT_CONVERGED);
  if (!binaryWeights) return rollback(OptimizationFailure::AMBIGUOUS_GNC);
  if (!finiteResult) return rollback(OptimizationFailure::NON_FINITE_RESULT);
  if (!nonWorsening) return rollback(OptimizationFailure::COST_INCREASE);

  // Robust loss alone can converge to a folded local minimum. Bound the physical damage to
  // measured odometry as an independent gate. Existing odometry noise raises the allowance;
  // a clean chain permits at most 0.35 m / 0.5 rad RMS after all accepted closures.
  const auto baselineOdometry = odometryRms(edges_, original);
  const double translationLimit = std::max(0.35, baselineOdometry.first * 1.5 + 0.05);
  const double rotationLimit = std::max(0.5, baselineOdometry.second * 1.5 + 0.05);
  auto excessiveDeformation = [&]() {
    const auto current = odometryRms(edges_, poses());
    return !std::isfinite(current.first) || !std::isfinite(current.second) ||
           current.first > translationLimit || current.second > rotationLimit;
  };
  if (hasLoop && excessiveDeformation()) {
    // Conservative first implementation: without pairwise consistency there is no defensible
    // way to blame one of several loops, so disable all of them and recover measured odometry.
    for (size_t i = 0; i < edges_.size(); ++i) {
      if (edges_[i].loopClosure) weights[i] = 0.0;
    }
    binaryWeights = true;
    report = optimizeInPlace(weights, iterations, convergenceCostDelta);
    totalIterations += report.iterations;
    report.initialCost = initialCost;
    report.finalCost = weightedCost(weights);
    report.iterations = totalIterations;
    const bool fallbackFinite = std::isfinite(report.finalCost) &&
                                std::isfinite(report.rmsTranslationMeters) &&
                                std::isfinite(report.rmsRotationRadians);
    const bool fallbackNonWorsening =
        report.finalCost <= initialCost + std::max(1e-6, std::fabs(initialCost) * 1e-6);
    if (!report.converged) return rollback(OptimizationFailure::NOT_CONVERGED);
    if (!fallbackFinite) return rollback(OptimizationFailure::NON_FINITE_RESULT);
    if (!fallbackNonWorsening) return rollback(OptimizationFailure::COST_INCREASE);
    if (excessiveDeformation()) return rollback(OptimizationFailure::DEFORMATION);
  }

  report.converged = true;
  report.rolledBack = false;
  report.milliseconds = elapsedMilliseconds();
  fillResiduals(report, weights);
  return report;
}

OptimizationReport PoseGraph::optimizeLastLoop(int iterations, double convergenceCostDelta) {
  if (edges_.empty() || !edges_.back().loopClosure) return optimize(iterations, convergenceCostDelta);
  const std::vector<Mat4> original = poses();
  auto report = optimize(iterations, convergenceCostDelta);
  const bool rejected = !report.residuals.empty() && report.residuals.back().loopClosure &&
                        report.residuals.back().robustWeight <= 1e-4;
  if (!report.rolledBack && !rejected) return report;
  if (!report.rolledBack) report.failure = OptimizationFailure::REJECTED_LOOP;

  for (size_t i = 0; i < nodes_.size(); ++i) nodes_[i].pose = original[i];
  edges_.pop_back();
  const std::vector<double> restoredWeights(edges_.size(), 1.0);
  report.initialCost = cost();
  report.finalCost = report.initialCost;
  report.converged = false;
  report.rolledBack = true;
  fillResiduals(report, restoredWeights);
  return report;
}

std::vector<LoopCandidate> ProximityLoopClosureDetector::findCandidates(
    const std::vector<Mat4>& poses) const {
  std::vector<LoopCandidate> candidates;
  const int count = static_cast<int>(poses.size());
  for (int i = 0; i < count; ++i) {
    for (int j = i + config_.minIndexGap; j < count; ++j) {
      const Vec3 pi = poses[static_cast<size_t>(i)].translation();
      const Vec3 pj = poses[static_cast<size_t>(j)].translation();
      const double distance = static_cast<double>((pi - pj).length());
      if (distance > config_.maxDistanceMeters) continue;

      const Vec3 fi = poses[static_cast<size_t>(i)].transformDirection(Vec3(0.f, 0.f, -1.f)).normalized();
      const Vec3 fj = poses[static_cast<size_t>(j)].transformDirection(Vec3(0.f, 0.f, -1.f)).normalized();
      const double cosAngle = std::max(-1.0, std::min(1.0, static_cast<double>(fi.dot(fj))));
      const double headingError = std::acos(cosAngle);
      if (headingError > config_.maxHeadingErrorRadians) continue;

      candidates.push_back(LoopCandidate{i, j, distance, headingError});
    }
  }
  std::sort(candidates.begin(), candidates.end(),
            [](const LoopCandidate& a, const LoopCandidate& b) {
              if (a.distanceMeters != b.distanceMeters) return a.distanceMeters < b.distanceMeters;
              return a.headingErrorRadians < b.headingErrorRadians;
            });
  return candidates;
}

}  // namespace constructar
