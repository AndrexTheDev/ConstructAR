/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// Host (desktop) test runner for the native SLAM core.
//
// Build & run without Android, NDK or CMake:
//
//   g++ -std=c++20 -O2 -pthread -I native-slam/src/main/cpp
//       native-slam/src/main/cpp/tsdf/volume.cpp
//       native-slam/src/main/cpp/tsdf/surface_nets.cpp
//       native-slam/src/main/cpp/slam/pose_graph.cpp
//       native-slam/src/main/cpp/slam/rtabmap_bridge.cpp
//       native-slam/src/main/cpp/tests/host_tests.cpp -o /tmp/constructar_host_tests
//   /tmp/constructar_host_tests
// (the script tools/verify-native.sh runs exactly this)
//
// Or via CMake: -DCONSTRUCTAR_BUILD_HOST_TESTS=ON then run the `constructar_host_tests`
// target. CI runs exactly this (see docs/VALIDATION.md).
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "../core/types.hpp"
#include "../slam/pose_graph.hpp"
#include "../slam/rtabmap_bridge.hpp"
#include "../tsdf/volume.hpp"

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool condition, const std::string& label) {
  ++g_checks;
  if (!condition) {
    ++g_failures;
    std::printf("  [FAIL] %s\n", label.c_str());
  }
}

void checkNear(double actual, double expected, double tolerance, const std::string& label) {
  check(std::fabs(actual - expected) <= tolerance,
        label + " (got " + std::to_string(actual) + ", expected " + std::to_string(expected) +
            " ± " + std::to_string(tolerance) + ")");
}

void section(const char* name) { std::printf("\n== %s\n", name); }

using constructar::DepthFrameView;
using constructar::Mat4;
using constructar::Mat3;
using constructar::PoseGraph;
using constructar::TsdfConfig;
using constructar::TsdfVolume;
using constructar::Vec3;

// ── Synthetic depth sources ───────────────────────────────────────────────────────────────

struct SyntheticCamera {
  int width = 320;
  int height = 240;
  float fx = 250.f;
  float fy = 250.f;
  float cx = 160.f;
  float cy = 120.f;
};

/**
 * Depth (millimetres, along the optical axis) of a sphere of [radius] centred at
 * [center] in camera space, seen by a pinhole camera at the origin looking down −Z.
 * 0 = no return.
 */
std::vector<uint16_t> renderSphereDepth(const SyntheticCamera& camera, const Vec3& center,
                                        float radius) {
  std::vector<uint16_t> depth(static_cast<size_t>(camera.width * camera.height), 0);
  for (int v = 0; v < camera.height; ++v) {
    for (int u = 0; u < camera.width; ++u) {
      const float px = (static_cast<float>(u) + 0.5f - camera.cx) / camera.fx;
      const float py = (static_cast<float>(v) + 0.5f - camera.cy) / camera.fy;
      // Point at optical-axis depth d is (px·d, py·d, −d).
      const float a = px * px + py * py + 1.f;
      const float b = -2.f * (px * center.x + py * center.y - center.z);
      const float c = center.lengthSquared() - radius * radius;
      const float discriminant = b * b - 4.f * a * c;
      if (discriminant <= 0.f) continue;
      const float sqrtD = std::sqrt(discriminant);
      const float d1 = (-b - sqrtD) / (2.f * a);
      const float d2 = (-b + sqrtD) / (2.f * a);
      const float d = d1 > 0.05f ? d1 : d2;
      if (d <= 0.05f || d > 8.f) continue;
      depth[static_cast<size_t>(v * camera.width + u)] =
          static_cast<uint16_t>(d * 1000.f);
    }
  }
  return depth;
}

DepthFrameView makeFrame(const SyntheticCamera& camera, const std::vector<uint16_t>& depth,
                         const Mat4& cameraToWorld) {
  DepthFrameView frame;
  frame.depth = depth.data();
  frame.width = camera.width;
  frame.height = camera.height;
  frame.rowStrideElements = camera.width;
  frame.intrinsics = constructar::CameraIntrinsics{camera.fx, camera.fy, camera.cx, camera.cy};
  frame.cameraToWorld = cameraToWorld;
  frame.timestampNs = 1;
  return frame;
}

// ── Tests ────────────────────────────────────────────────────────────────────────────────

void testSe3() {
  section("SE(3) exponential / logarithm");
  const constructar::Twist twist{Vec3(0.2f, -0.4f, 0.15f), Vec3(0.5f, -0.25f, 1.2f)};
  const Mat4 transform = constructar::se3Exp(twist);
  const constructar::Twist recovered = constructar::se3Log(transform);
  checkNear(recovered.omega.x, twist.omega.x, 1e-4, "omega.x recovered");
  checkNear(recovered.omega.y, twist.omega.y, 1e-4, "omega.y recovered");
  checkNear(recovered.omega.z, twist.omega.z, 1e-4, "omega.z recovered");
  checkNear(recovered.upsilon.x, twist.upsilon.x, 1e-4, "upsilon.x recovered");
  checkNear(recovered.upsilon.y, twist.upsilon.y, 1e-4, "upsilon.y recovered");
  checkNear(recovered.upsilon.z, twist.upsilon.z, 1e-4, "upsilon.z recovered");

  // Exact zero rotation case must not divide by zero.
  const constructar::Twist pureTranslation{Vec3(0.f, 0.f, 0.f), Vec3(1.f, 2.f, 3.f)};
  const auto back = constructar::se3Log(constructar::se3Exp(pureTranslation));
  checkNear(back.upsilon.z, 3.0, 1e-5, "pure translation recovered");

  // Inverse sanity.
  const Mat4 identity = transform * transform.inverseRigid();
  checkNear(identity.m[12], 0.0, 1e-5, "inverse translation zero");
  checkNear(identity.m[0], 1.0, 1e-5, "inverse rotation identity");
}

void testSphereReconstruction() {
  section("TSDF + Surface Nets: 0.5 m sphere at 2 m");
  SyntheticCamera camera;
  const Vec3 center(0.f, 0.f, -2.f);
  const float radius = 0.5f;
  const auto depth = renderSphereDepth(camera, center, radius);

  TsdfConfig config;
  config.voxelSize = 0.02f;
  config.truncationVoxels = 4.f;
  config.maxDepth = 4.f;
  config.minConfidence = 0;  // synthetic frames carry no confidence image
  config.workerThreads = 2;
  TsdfVolume volume(config);

  const auto stats = volume.integrate(makeFrame(camera, depth, Mat4::identity()), 1);
  check(stats.raysProcessed > 10000, "rays processed (" + std::to_string(stats.raysProcessed) + ")");
  check(stats.voxelUpdates > 50000, "voxel updates (" + std::to_string(stats.voxelUpdates) + ")");
  check(volume.blockCount() > 100, "blocks allocated (" + std::to_string(volume.blockCount()) + ")");

  const auto mesh = volume.extractMesh();
  check(mesh.triangleCount() > 500, "triangles extracted (" + std::to_string(mesh.triangleCount()) + ")");
  check(mesh.vertexCount() == mesh.normals.size() / 3, "one normal per vertex");
  check(!mesh.indices.empty() && *std::max_element(mesh.indices.begin(), mesh.indices.end()) < mesh.vertexCount(),
        "indices in range");

  // Every vertex must sit on the sphere surface (±1.5 voxels), and its normal must point
  // away from the sphere centre — i.e. the TSDF gradient orientation is correct.
  double maxRadialError = 0.0;
  int outwardNormals = 0;
  const size_t count = mesh.vertexCount();
  for (size_t i = 0; i < count; ++i) {
    const Vec3 p(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2]);
    const Vec3 n(mesh.normals[i * 3], mesh.normals[i * 3 + 1], mesh.normals[i * 3 + 2]);
    const float distance = (p - center).length();
    maxRadialError = std::max(maxRadialError, std::fabs(static_cast<double>(distance - radius)));
    const Vec3 outward = (p - center).normalized();
    if (n.dot(outward) > 0.5f) ++outwardNormals;
  }
  checkNear(maxRadialError, 0.0, 0.03, "max radial error of the reconstructed sphere");
  check(outwardNormals > static_cast<int>(count) * 9 / 10,
        "normals point outward (" + std::to_string(outwardNormals) + "/" + std::to_string(count) + ")");

  // Winding: triangle geometric normals must agree with the vertex normals.
  int consistentWinding = 0;
  const size_t triangles = mesh.triangleCount();
  for (size_t t = 0; t < triangles; ++t) {
    const uint32_t i0 = mesh.indices[t * 3];
    const uint32_t i1 = mesh.indices[t * 3 + 1];
    const uint32_t i2 = mesh.indices[t * 3 + 2];
    const Vec3 a(mesh.positions[i0 * 3], mesh.positions[i0 * 3 + 1], mesh.positions[i0 * 3 + 2]);
    const Vec3 b(mesh.positions[i1 * 3], mesh.positions[i1 * 3 + 1], mesh.positions[i1 * 3 + 2]);
    const Vec3 c(mesh.positions[i2 * 3], mesh.positions[i2 * 3 + 1], mesh.positions[i2 * 3 + 2]);
    const Vec3 geometric = (b - a).cross(c - a).normalized();
    const Vec3 shading(mesh.normals[i0 * 3], mesh.normals[i0 * 3 + 1], mesh.normals[i0 * 3 + 2]);
    if (geometric.dot(shading) > 0.f) ++consistentWinding;
  }
  check(consistentWinding > static_cast<int>(triangles) * 9 / 10,
        "winding agrees with normals (" + std::to_string(consistentWinding) + "/" +
            std::to_string(triangles) + ")");

  // Area check with a physically meaningful bound. A single view reconstructs the *visible*
  // hemisphere minus a rim where the surface is nearly parallel to the view ray (beyond
  // ~78° incidence a 2 cm voxel cannot resolve the crossing). Theoretical spherical-cap
  // area inside a given radius R is A = 2πr(r − √(r² − R²)).
  //   R = 0.45 m → 0.886 m²   (well-resolved region — must match closely)
  //   R = 0.50 m → 1.571 m²   (full hemisphere — the rim is expected to be cut)
  double areaInner = 0.0;
  double areaTotal = 0.0;
  for (size_t t = 0; t < triangles; ++t) {
    const Vec3 a(mesh.positions[mesh.indices[t * 3] * 3], mesh.positions[mesh.indices[t * 3] * 3 + 1],
                 mesh.positions[mesh.indices[t * 3] * 3 + 2]);
    const Vec3 b(mesh.positions[mesh.indices[t * 3 + 1] * 3], mesh.positions[mesh.indices[t * 3 + 1] * 3 + 1],
                 mesh.positions[mesh.indices[t * 3 + 1] * 3 + 2]);
    const Vec3 c(mesh.positions[mesh.indices[t * 3 + 2] * 3], mesh.positions[mesh.indices[t * 3 + 2] * 3 + 1],
                 mesh.positions[mesh.indices[t * 3 + 2] * 3 + 2]);
    const double triangleArea = static_cast<double>((b - a).cross(c - a).length()) * 0.5;
    areaTotal += triangleArea;
    const Vec3 centroid = (a + b + c) / 3.f;
    const float radial = std::sqrt(centroid.x * centroid.x + centroid.y * centroid.y);
    if (radial < 0.45f) areaInner += triangleArea;
  }
  // 10-11 % under the analytic cap is the expected bias where cells straddle the surface at
  // high incidence (free-space carving is off in this configuration, which is the production
  // default). Losing more than 15 % would mean Surface Nets is dropping patches.
  check(areaInner > 0.886 * 0.85 && areaInner < 0.886 * 1.10,
        "reconstructed cap area inside r = 0.45 m is within 15 % of the analytic 0.886 m² (got " +
            std::to_string(areaInner) + ")");
  check(areaTotal > 0.75 && areaTotal < 1.57,
        "total reconstructed area is the visible cap, not the whole sphere (" +
            std::to_string(areaTotal) + " m²)");
}

void testFlatWall() {
  section("TSDF + Surface Nets: fronto-parallel wall");
  SyntheticCamera camera;
  std::vector<uint16_t> depth(static_cast<size_t>(camera.width * camera.height), 2000);
  TsdfConfig config;
  config.voxelSize = 0.02f;
  config.minConfidence = 0;
  config.workerThreads = 1;
  TsdfVolume volume(config);
  volume.integrate(makeFrame(camera, depth, Mat4::identity()), 2);

  const auto mesh = volume.extractMesh();
  check(mesh.triangleCount() > 200, "wall triangles");

  double maxZError = 0.0;
  double maxNormalDeviation = 0.0;
  for (size_t i = 0; i < mesh.vertexCount(); ++i) {
    const double z = mesh.positions[i * 3 + 2];
    maxZError = std::max(maxZError, std::fabs(z - (-2.0)));
    const Vec3 n(mesh.normals[i * 3], mesh.normals[i * 3 + 1], mesh.normals[i * 3 + 2]);
    maxNormalDeviation = std::max(maxNormalDeviation, std::fabs(1.0 - static_cast<double>(n.z)));
  }
  checkNear(maxZError, 0.0, 0.02, "wall plane depth accuracy");
  checkNear(maxNormalDeviation, 0.0, 0.05, "wall normal is +Z");

  // The wall must span the full camera frustum width at 2 m: (320 px / 250 fx) · 2 m = 2.56 m.
  float minX = 1e30f, maxX = -1e30f;
  for (size_t i = 0; i < mesh.vertexCount(); ++i) {
    minX = std::min(minX, mesh.positions[i * 3]);
    maxX = std::max(maxX, mesh.positions[i * 3]);
  }
  checkNear(maxX - minX, 2.56, 0.15, "wall width at 2 m (m)");
}

void testPoseGraphLoopClosure() {
  section("Pose graph: drift removal with a loop closure");
  // A square room walked with 13 keyframes (12 steps of 1 m, a 90° turn every third step).
  // Each turn over-rotates by 2 % — the classic gyro scale error — so the 12th step lands
  // short of the start. A verified loop closure (weight 100× odometry) re-closes the graph.
  const int keyframes = 13;
  const float step = 1.0f;
  const float turn = 1.5707964f * 1.02f;

  std::vector<Mat4> odometry;
  odometry.push_back(Mat4::identity());
  for (int i = 1; i < keyframes; ++i) {
    const bool corner = (i % 3) == 0 && i < keyframes - 1;
    const Mat4 rotation = corner ? Mat4::fromRotationTranslation(
                                       constructar::Quat::fromAxisAngle(Vec3(0.f, 1.f, 0.f), turn).toMat3(),
                                       Vec3(0.f, 0.f, 0.f))
                                 : Mat4::identity();
    const Mat4 translation = Mat4::fromRotationTranslation(Mat3(), Vec3(0.f, 0.f, -step));
    odometry.push_back(odometry.back() * rotation * translation);
  }

  PoseGraph graph;
  for (int i = 0; i < keyframes; ++i) graph.addNode(odometry[static_cast<size_t>(i)], i == 0);
  for (int i = 0; i + 1 < keyframes; ++i) {
    const Mat4 relative =
        odometry[static_cast<size_t>(i)].inverseRigid() * odometry[static_cast<size_t>(i + 1)];
    graph.addEdge(i, i + 1, relative, 1.0, 1.0, false);
  }

  // Odometry edges are exactly consistent by construction, so the pre-closure cost is zero;
  // the drift only shows up as the gap between the last keyframe and the first one.
  checkNear(graph.cost(), 0.0, 1e-6, "odometry-only graph cost is zero");
  const double gapBefore = static_cast<double>(
      (graph.poses().back().translation() - graph.poses().front().translation()).length());
  check(gapBefore > 0.05, "odometry drifts before the closure (" + std::to_string(gapBefore) + " m)");

  // Appearance-verified loop closure: "I am standing where I started".
  graph.addEdge(0, keyframes - 1, Mat4::identity(), 100.0, 100.0, true);

  const auto report = graph.optimize(50, 1e-10);
  check(report.iterations > 0, "iterations executed");
  check(report.residuals.size() == static_cast<size_t>(keyframes), "one residual per edge");
  check(!report.residuals.empty() && report.residuals.back().loopClosure, "loop edge reported");
  // The closure has to absorb the whole odometry drift into 12 rigid links, so ~0.1 m of
  // per-edge translation residual is the expected optimum — not an error.
  check(report.rmsTranslationMeters < 0.15,
        "RMS translation after optimisation (" + std::to_string(report.rmsTranslationMeters) + " m)");
  check(report.converged || report.iterations < 50, "optimiser terminates on a cost criterion");
  check(!report.residuals.empty() && report.residuals.back().translationErrorMeters < 0.05,
        "loop closure residual (got " +
            std::to_string(report.residuals.empty() ? -1.0 : report.residuals.back().translationErrorMeters) +
            " m)");
  check(report.finalCost > 0.0, "cost reflects the closure constraint");

  const Vec3 lastTranslation = graph.poses().back().translation();
  check(lastTranslation.length() < 0.10f,
        "final keyframe pulled back to the start (" + std::to_string(lastTranslation.length()) + " m)");
  check(graph.poses().front().translation().length() < 1e-6f, "gauge node not moved");
}

void testProximityDetector() {
  section("Proximity loop-closure proposer");
  constructar::ProximityLoopClosureDetector detector;
  std::vector<Mat4> poses;
  const int count = 40;
  for (int i = 0; i < count; ++i) {
    // Walk out along −Z, then back; index 39 lands ~0.1 m from index 0.
    const float z = i < 20 ? -0.1f * static_cast<float>(i) : -0.1f * static_cast<float>(40 - i);
    poses.push_back(Mat4::fromRotationTranslation(Mat3(), Vec3(0.f, 0.f, z)));
  }
  const auto candidates = detector.findCandidates(poses);
  check(!candidates.empty(), "candidate proposed for the revisit");
  bool spansRevisit = false;
  for (const auto& candidate : candidates) {
    if (candidate.to - candidate.from >= 19) spansRevisit = true;
  }
  check(spansRevisit, "candidate spans the revisit (index gap respected)");
  if (!candidates.empty()) check(candidates.front().distanceMeters < 0.35, "candidate is spatially close");
}

void testTriangleBudgetAndReset() {
  section("Triangle budget and reset");
  SyntheticCamera camera;
  const auto depth = renderSphereDepth(camera, Vec3(0.f, 0.f, -2.f), 0.5f);
  TsdfConfig config;
  config.voxelSize = 0.01f;
  config.minConfidence = 0;
  TsdfVolume volume(config);
  volume.setTriangleBudget(64);
  volume.integrate(makeFrame(camera, depth, Mat4::identity()), 2);
  const auto mesh = volume.extractMesh();
  check(mesh.triangleCount() <= 64, "triangle budget respected (" + std::to_string(mesh.triangleCount()) + ")");

  volume.reset();
  check(volume.blockCount() == 0, "reset frees every block");
  check(volume.extractMesh().empty(), "reset mesh is empty");
}

void testInvalidInput() {
  section("Invalid input handling");
  TsdfConfig config;
  TsdfVolume volume(config);
  DepthFrameView frame;  // everything null / zero
  const auto stats = volume.integrate(frame, 2);
  check(stats.raysProcessed == 0, "invalid frame ignored");
  check(volume.extractMesh().empty(), "empty volume produces an empty mesh");

  SyntheticCamera camera;
  std::vector<uint16_t> zeros(static_cast<size_t>(camera.width * camera.height), 0);
  const auto depthStats = volume.integrate(makeFrame(camera, zeros, Mat4::identity()), 2);
  check(depthStats.raysProcessed == 0, "zero depth (no return) ignored");
}

}  // namespace

int main() {
  std::printf("ConstructAR native SLAM host tests\n");
  testSe3();
  testSphereReconstruction();
  testFlatWall();
  testPoseGraphLoopClosure();
  testProximityDetector();
  testTriangleBudgetAndReset();
  testInvalidInput();

  std::printf("\n%d checks, %d failures\n", g_checks, g_failures);
  if (g_failures == 0) std::printf("ALL NATIVE TESTS PASSED\n");
  return g_failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
