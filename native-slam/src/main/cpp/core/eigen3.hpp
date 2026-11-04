/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// Symmetric 3x3 eigen-decomposition — the one implementation, used by everything that needs a
// direction out of a scatter matrix (the Manhattan frame's dominant axis, a fitted plane's
// normal). No Eigen, no LAPACK: the NDK build stays BSD-3/MIT only, and a 3x3 symmetric matrix is
// solved exactly by a few sweeps of Jacobi rotations.
//
// Values come back **ascending**, so the smallest is `values[0]` (a plane's normal is the
// eigenvector of the smallest eigenvalue of its covariance) and the largest is `values[2]` (the
// direction a cloud of plane normals leans on).
//
// Jacobi, rather than the closed-form cubic: the closed form needs a branch on the discriminant
// and loses half its digits when two eigenvalues are close, which is exactly the case that matters
// here — a square room's two horizontal families differ by a few percent (finding 187). Jacobi
// converges quadratically regardless, costs a few hundred flops for a 3x3, and is deterministic
// for a given input, which is what lets the host suite pin an answer bit for bit.
#pragma once

#include <cmath>

#include "types.hpp"

namespace constructar {

struct Eigen3 {
  /** Ascending: `values[0] <= values[1] <= values[2]`. */
  float values[3] = {0.f, 0.f, 0.f};
  /** Unit eigenvectors, `vectors[i]` belonging to `values[i]`. Mutually orthogonal to rounding. */
  Vec3 vectors[3] = {{1.f, 0.f, 0.f}, {0.f, 1.f, 0.f}, {0.f, 0.f, 1.f}};
};

/** Cyclic Jacobi eigen-decomposition of a symmetric 3x3 matrix. */
inline Eigen3 eigenDecomposeSymmetric(const float matrix[3][3]) {
  float a[3][3] = {
      {matrix[0][0], matrix[0][1], matrix[0][2]},
      {matrix[1][0], matrix[1][1], matrix[1][2]},
      {matrix[2][0], matrix[2][1], matrix[2][2]},
  };
  float v[3][3] = {{1.f, 0.f, 0.f}, {0.f, 1.f, 0.f}, {0.f, 0.f, 1.f}};

  for (int sweep = 0; sweep < 16; ++sweep) {
    const float offDiagonal = std::fabs(a[0][1]) + std::fabs(a[0][2]) + std::fabs(a[1][2]);
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

  Eigen3 result;
  int order[3] = {0, 1, 2};
  for (int i = 0; i < 2; ++i) {
    for (int j = i + 1; j < 3; ++j) {
      if (a[order[j]][order[j]] < a[order[i]][order[i]]) {
        const int swapped = order[i];
        order[i] = order[j];
        order[j] = swapped;
      }
    }
  }
  for (int i = 0; i < 3; ++i) {
    const int index = order[i];
    result.values[i] = a[index][index];
    result.vectors[i] = Vec3(v[0][index], v[1][index], v[2][index]).normalized();
  }
  return result;
}

}  // namespace constructar
