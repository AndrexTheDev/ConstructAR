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
//       native-slam/src/main/cpp/geometry/manhattan_world.cpp
//       native-slam/src/main/cpp/calibration/tag_scale.cpp
//       native-slam/src/main/cpp/geometry/plane_fit.cpp
//       native-slam/src/main/cpp/slam/rtabmap_bridge.cpp
//       native-slam/src/main/cpp/tests/host_tests.cpp -o /tmp/constructar_host_tests
//   /tmp/constructar_host_tests
// (tools/verify-native.sh runs exactly this, for every compiler and optimisation it finds)
//
// Or via CMake: -DCONSTRUCTAR_BUILD_HOST_TESTS=ON then run the `constructar_host_tests`
// target. CI does both (`native-tests.yml`).
//
// ./tools/verify-native.sh --tsan runs the same suite under ThreadSanitizer. That is a gate,
// not an experiment: the integration fuses voxels from several worker threads, and a lost
// observation is invisible to the assertions - the weights only have to add up to the number of
// updates, and a race that drops one still does (finding 181).
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "../calibration/tag_scale.hpp"
#include "../core/types.hpp"
#include "../geometry/manhattan_world.hpp"
#include "../geometry/plane_fit.hpp"
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

/**
 * The depth convention, pinned by a surface that would bend if it were wrong.
 *
 * ARCore's depth image is a distance along the camera's optical axis (z-depth), and the
 * integrator has to place the hit where that number says: `(px·z, py·z, −z)` with the signed
 * distance measured *along the axis*. A fronto-parallel wall cannot tell the two conventions
 * apart — every pixel has the same z and the same range — which is why this case uses a plane
 * tilted 30° about Y, reconstructed from an analytic depth map. Reading the same numbers as
 * Euclidean range instead bows such a surface by up to 8.8 cm and loses most of it (finding 108).
 */
void testTiltedPlane() {
  section("TSDF + Surface Nets: 30°-tilted plane (depth convention)");
  SyntheticCamera camera;
  const Vec3 normal(0.5f, 0.f, 0.8660254f);  // unit, points back at the camera
  const float offset = -2.0f;                 // normal · (point on the plane)

  std::vector<uint16_t> depth(static_cast<size_t>(camera.width * camera.height), 0);
  for (int v = 0; v < camera.height; ++v) {
    for (int u = 0; u < camera.width; ++u) {
      const float px = (static_cast<float>(u) + 0.5f - camera.cx) / camera.fx;
      const float py = (static_cast<float>(v) + 0.5f - camera.cy) / camera.fy;
      const float denominator = normal.x * px + normal.y * py - normal.z;
      if (std::fabs(denominator) < 1e-6f) continue;
      const float z = offset / denominator;
      if (z < 0.2f || z > 5.5f) continue;
      depth[static_cast<size_t>(v * camera.width + u)] = static_cast<uint16_t>(z * 1000.f);
    }
  }

  TsdfConfig config;
  config.voxelSize = 0.02f;
  config.minConfidence = 0;
  config.workerThreads = 1;
  TsdfVolume volume(config);
  volume.integrate(makeFrame(camera, depth, Mat4::identity()), 1);
  const auto mesh = volume.extractMesh();

  check(mesh.triangleCount() > 10000,
        "tilted plane triangles (" + std::to_string(mesh.triangleCount()) + ")");

  double maxPlaneError = 0.0;
  int alignedNormals = 0;
  bool reachesLeft = false;
  bool reachesRight = false;
  for (size_t i = 0; i < mesh.vertexCount(); ++i) {
    const Vec3 p(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2]);
    const Vec3 n(mesh.normals[i * 3], mesh.normals[i * 3 + 1], mesh.normals[i * 3 + 2]);
    maxPlaneError = std::max(maxPlaneError,
                             std::fabs(static_cast<double>(normal.dot(p) - offset)));
    if (std::fabs(n.dot(normal)) > 0.9f) ++alignedNormals;
    if (p.x < -1.0f) reachesLeft = true;
    if (p.x > 1.0f) reachesRight = true;
  }
  checkNear(maxPlaneError, 0.0, 0.02, "tilted plane: max distance from the true plane (m)");
  check(alignedNormals > static_cast<int>(mesh.vertexCount()) * 9 / 10,
        "tilted plane: normals follow the plane (" + std::to_string(alignedNormals) + "/" +
            std::to_string(mesh.vertexCount()) + ")");
  // The far edge of a 30° plane is 2.31 m away and the near edge 1.79 m, so a surface that
  // spans the frame is what proves the whole tilted patch was reconstructed, not one band.
  check(reachesLeft && reachesRight, "tilted plane spans the frame from x < -1 m to x > 1 m");
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

/**
 * Free-space carving: a ghost has to go, and the surface that is really there has to stay.
 *
 * `TsdfConfig::carveFreeSpace` marks everything between the camera and the truncation band as
 * outside, which is what removes furniture that has been scanned and then moved. It was
 * documented as a switch "the UI exposes" - no screen offers it, nothing calls it, no test
 * covered it - and when it was finally exercised it made ghosts *worse*: it sampled free space
 * every fourth voxel, so the voxels between two samples kept their old inside value and a 1 m
 * plane seen once and then cleared produced 9 218 vertices within 5 cm of itself, against 2 961
 * for not carving at all (finding 111).
 *
 * This is the case that decides it: scan a plane at 1 m once, look at empty space at 2 m six
 * times more, then count what is left within 5 cm of the 1 m plane.
 */
void testCarveFreeSpace() {
  section("Free-space carving: ghost removal, surface intact");
  SyntheticCamera camera;

  auto countNearPlane = [](const TsdfVolume& volume, float planeZ) {
    const auto mesh = volume.extractMesh();
    int near = 0;
    for (size_t i = 0; i < mesh.vertexCount(); ++i) {
      if (std::fabs(mesh.positions[i * 3 + 2] - planeZ) < 0.05f) ++near;
    }
    return near;
  };

  const std::vector<uint16_t> near = std::vector<uint16_t>(
      static_cast<size_t>(camera.width * camera.height), static_cast<uint16_t>(1000));
  const std::vector<uint16_t> far = std::vector<uint16_t>(
      static_cast<size_t>(camera.width * camera.height), static_cast<uint16_t>(2000));

  TsdfConfig off;
  off.voxelSize = 0.02f;
  off.minConfidence = 0;
  off.workerThreads = 1;
  TsdfVolume plain(off);
  plain.integrate(makeFrame(camera, near, Mat4::identity()), 2);
  for (int i = 0; i < 6; ++i) plain.integrate(makeFrame(camera, far, Mat4::identity()), 2);
  const int ghostWithoutCarving = countNearPlane(plain, -1.0f);
  const int wallWithoutCarving = countNearPlane(plain, -2.0f);

  TsdfConfig on = off;
  on.carveFreeSpace = true;
  TsdfVolume carving(on);
  carving.integrate(makeFrame(camera, near, Mat4::identity()), 2);
  for (int i = 0; i < 6; ++i) carving.integrate(makeFrame(camera, far, Mat4::identity()), 2);
  const int ghostWithCarving = countNearPlane(carving, -1.0f);
  const int wallWithCarving = countNearPlane(carving, -2.0f);

  // Without carving the ghost is plainly there - otherwise the rest of the test proves nothing.
  check(ghostWithoutCarving > 2000,
        "the ghost survives when carving is off (" + std::to_string(ghostWithoutCarving) + ")");
  check(ghostWithCarving < 20,
        "carving removes the ghost (" + std::to_string(ghostWithCarving) + " vertices left)");
  // The real surface is not sacrificed: same count, within the quantisation of one scan.
  check(wallWithoutCarving > 10000, "the real wall is there for both volumes");
  checkNear(static_cast<double>(wallWithCarving), static_cast<double>(wallWithoutCarving),
            static_cast<double>(wallWithoutCarving) * 0.01, "carving does not eat the real surface");
  // Carving must not allocate: only blocks that already hold observations can hold a ghost.
  check(carving.blockCount() <= plain.blockCount(),
        "carving allocates no new block (" + std::to_string(carving.blockCount()) + " vs " +
            std::to_string(plain.blockCount()) + ")");
}

void testBlockBudget() {
  section("Block budget: the volume stops growing and says so");
  SyntheticCamera camera;
  const auto depth = renderSphereDepth(camera, Vec3(0.f, 0.f, -2.f), 0.5f);
  TsdfConfig config;
  config.voxelSize = 0.01f;
  config.minConfidence = 0;
  config.maxBlocks = 4;
  TsdfVolume volume(config);

  const auto stats = volume.integrate(makeFrame(camera, depth, Mat4::identity()), 2);
  // A sphere at 2 m needs far more than four blocks, so the budget is hit on the first frame.
  check(stats.hitBlockBudget, "the budget is reported");
  check(volume.blockCount() <= 4, "no block is allocated past the budget (" +
                                      std::to_string(volume.blockCount()) + ")");

  // A second frame must keep the flag - the scan is still full - and must not grow the volume.
  const auto again = volume.integrate(makeFrame(camera, depth, Mat4::identity()), 2);
  check(again.hitBlockBudget, "the flag survives the next frame");
  check(volume.blockCount() <= 4, "the volume stays at the budget");

  // Reset clears it, so a fresh scan can report honestly again.
  volume.reset();
  const auto afterReset = volume.integrate(makeFrame(camera, depth, Mat4::identity()), 2);
  check(afterReset.hitBlockBudget, "after a reset the budget is hit again, not remembered");
}

/**
 * The block budget under the threading it actually runs with.
 *
 * A budget is a memory guard: `maxBlocks` is what keeps a ten-minute scan from filling the phone.
 * It is spent inside `findOrCreateBlockLocked`, which runs on every worker thread, and the
 * shards have independent mutexes - so the check and the increment have to be one atomic step. The older code
 * read the count, compared it with the budget and incremented afterwards, which let two threads
 * both take the last slot: five blocks where the budget said four, intermittently, depending on how
 * many cores the machine has. This case runs twelve volumes with eight workers and a step of one
 * pixel, which is the widest window the race gets (finding 159).
 */
void testBlockBudgetUnderConcurrency() {
  section("Block budget: exact under eight workers");
  SyntheticCamera camera;
  const auto depth = renderSphereDepth(camera, Vec3(0.f, 0.f, -2.f), 0.5f);
  constexpr size_t kBudget = 6;
  size_t worst = 0;
  bool reported = true;
  for (int round = 0; round < 12; ++round) {
    TsdfConfig config;
    config.voxelSize = 0.01f;
    config.minConfidence = 0;
    config.maxBlocks = kBudget;
    config.workerThreads = 8;
    TsdfVolume volume(config);
    const auto stats = volume.integrate(makeFrame(camera, depth, Mat4::identity()), 1);
    reported = reported && stats.hitBlockBudget;
    worst = std::max(worst, volume.blockCount());
  }
  check(reported, "every round reports the budget being hit");
  check(worst <= kBudget, "no round allocates past the budget (" + std::to_string(worst) + " of " +
                              std::to_string(kBudget) + ")");
}

/**
 * What a frame reports about the blocks it allocated.
 *
 * `IntegrationStats::blocksAllocated` is published to the app through the JNI (`IntegrationResult`)
 * and it used to be wrong twice over: a block created during the frame was *never* counted, because
 * the code that created it set `lastFrame` to the current frame first, so the caller's "is this new
 * to me?" test said no - one frame into an empty volume reported hundreds of blocks in the volume
 * and zero allocated; and a block reached by two worker threads in the same frame was counted
 * twice, because the touch was a plain read-modify-write outside the shard lock (finding 160).
 */
void testBlocksAllocatedPerFrame() {
  section("Block allocation accounting");
  SyntheticCamera camera;
  const auto depth = renderSphereDepth(camera, Vec3(0.f, 0.f, -2.f), 0.5f);
  TsdfConfig config;
  config.voxelSize = 0.01f;
  config.minConfidence = 0;
  config.workerThreads = 8;
  TsdfVolume volume(config);

  const auto first = volume.integrate(makeFrame(camera, depth, Mat4::identity()), 2);
  check(first.blocksTotal > 0, "the first frame allocates blocks (" +
                                  std::to_string(first.blocksTotal) + ")");
  check(first.blocksAllocated == first.blocksTotal,
        "every block of the first frame is reported (" + std::to_string(first.blocksAllocated) +
            " of " + std::to_string(first.blocksTotal) + ")");

  const auto second = volume.integrate(makeFrame(camera, depth, Mat4::identity()), 2);
  check(second.blocksAllocated > 0, "the second frame reports the blocks it reached (" +
                                        std::to_string(second.blocksAllocated) + ")");
  check(second.blocksAllocated <= second.blocksTotal,
        "no block is counted twice in one frame (" + std::to_string(second.blocksAllocated) +
            " of " + std::to_string(second.blocksTotal) + ")");
  check(second.blocksTotal == first.blocksTotal, "the same view does not grow the volume");

  // Eight workers, twelve volumes, a step of one pixel: the window for two threads to land in one
  // block is as wide as this test can make it, and every frame still has to add up.
  bool bounded = true;
  for (int round = 0; round < 12; ++round) {
    TsdfVolume concurrent(config);
    concurrent.integrate(makeFrame(camera, depth, Mat4::identity()), 1);
    const auto stats = concurrent.integrate(makeFrame(camera, depth, Mat4::identity()), 1);
    bounded = bounded && stats.blocksAllocated <= stats.blocksTotal && stats.blocksAllocated > 0;
  }
  check(bounded, "twelve eight-worker frames counted within the volume");
}

/**
 * Every counted voxel write has to be in the volume.
 *
 * The fusion is a read-modify-write of two arrays - `sdf` and `weight` - and it used to run
 * *outside* the shard lock: `findOrCreateBlock` locked, handed out the block pointer, released,
 * and the caller then did `oldWeight = block->weight[idx]; block->sdf[idx] = …;
 * block->weight[idx] = …`. It now happens inside `fuseVoxel`, under the same lock.
 * Two worker threads whose rays cross the same voxel - which is the normal case, the truncation
 * band is four voxels thick and the rows are split across threads - wrote each other's work away:
 * ThreadSanitizer reports it as three races on those three lines, and what it loses is an
 * observation in the running average (finding 181).
 *
 * The identity this asserts is exact rather than statistical: a voxel's weight goes up by one for
 * every observation the volume accepted, so the sum over all voxels must equal the sum of
 * `voxelUpdates` over all frames. One lost update anywhere in the volume breaks it.
 *
 * It is not, on its own, the guard for the race: losing an update needs the two threads inside the
 * same three instructions, and eight workers over eight rounds did not manage that on this
 * machine - the case passed against the racy volume, with all 75 checks green. The guard is
 * `tools/verify-native.sh --tsan`, which reports the three races and fails, and which CI runs.
 * What this case is for is the accounting on either side of the fix: a weight that is capped but
 * not written, an observation dropped by the carve pass, a reset that forgets a block.
 */
void testVoxelFusionUnderConcurrency() {
  section("Voxel fusion: every counted observation is in the volume");
  SyntheticCamera camera;
  const auto depth = renderSphereDepth(camera, Vec3(0.f, 0.f, -2.f), 0.5f);
  constexpr int kRounds = 8;
  constexpr int kFrames = 4;
  // One assertion for the whole sweep, not one per round: `verify_sources.py` holds the README's
  // native count to the number of `check` call sites, which is only honest as long as every call
  // site runs exactly once (finding 99).
  size_t worstShortfall = 0;
  size_t countedTotal = 0;
  size_t storedTotal = 0;
  int worstRound = -1;
  for (int round = 0; round < kRounds; ++round) {
    TsdfConfig config;
    config.voxelSize = 0.01f;
    config.minConfidence = 0;
    config.workerThreads = 8;
    TsdfVolume volume(config);
    size_t counted = 0;
    for (int frame = 0; frame < kFrames; ++frame) {
      counted += static_cast<size_t>(volume.integrate(makeFrame(camera, depth, Mat4::identity()), 1).voxelUpdates);
    }
    const size_t stored = volume.totalVoxelWeight();
    countedTotal += counted;
    storedTotal += stored;
    if (stored < counted) {
      worstShortfall = std::max(worstShortfall, counted - stored);
      worstRound = round;
    }
  }
  check(worstShortfall == 0,
        "every counted observation is in a voxel: " + std::to_string(storedTotal) + " stored of " +
            std::to_string(countedTotal) + " counted over " + std::to_string(kRounds) + " rounds" +
            (worstRound < 0 ? std::string()
                            : ", round " + std::to_string(worstRound) + " lost " +
                                  std::to_string(worstShortfall)));
  check(storedTotal > countedTotal / 2,
        "the sweep really exercised the volume: " + std::to_string(storedTotal) + " voxel observations");
}

/**
 * The RTAB-Map bridge in a build without RTAB-Map.
 *
 * The app calls this class unconditionally, so the stub branch promises four things: the back-end
 * is reported unavailable, a keyframe is accepted and ignored, no closure is invented, and the
 * graph is empty - before and after a reset. It used to increment a node counter on every ignored
 * keyframe, so `graphSize()` grew for a back-end that is not in the binary at all, while its KDoc
 * promises "number of nodes RTAB-Map currently holds" (finding 183).
 *
 * The `CONSTRUCTAR_WITH_RTABMAP` branch cannot be exercised here - it needs the real library to
 * link - which is why its shape is checked by compiling it against the upstream-shaped stubs in
 * `tests/rtabmap-stubs` (see `native-tests.yml`).
 */

/**
 * The room every Manhattan test is about: 4.00 × 3.00 m, 2.50 m high, with one wall 8° off square
 * (drift, must be snapped) and one bay window 35° off (a real feature, must be left alone).
 * `turnDegrees` rotates the whole room about the vertical, so the solver cannot accidentally
 * privilege the world axes.
 */
std::vector<constructar::PlaneObservation> makeRoom(float turnDegrees) {
  const float turn = turnDegrees * constructar::kPi / 180.f;
  const float c = std::cos(turn);
  const float s = std::sin(turn);
  const auto rotY = [c, s](constructar::Vec3 n) {
    return constructar::Vec3(n.x * c + n.z * s, n.y, -n.x * s + n.z * c);
  };
  const auto rotPoint = [c, s](constructar::Vec3 p) {
    return constructar::Vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c);
  };
  const auto plane = [&](constructar::Vec3 n, constructar::Vec3 centroid, int inliers) {
    const constructar::Vec3 rn = rotY(n);
    const constructar::Vec3 rc = rotPoint(centroid);
    return constructar::PlaneObservation(rn, rn.dot(rc), inliers, rc);
  };
  const float tilt = 8.f * constructar::kPi / 180.f;
  const float bay = 35.f * constructar::kPi / 180.f;
  std::vector<constructar::PlaneObservation> planes;
  planes.push_back(plane({0.f, 1.f, 0.f}, {2.f, 0.f, 1.5f}, 40000));           // floor
  planes.push_back(plane({0.f, -1.f, 0.f}, {2.f, 2.5f, 1.5f}, 36000));         // ceiling
  planes.push_back(plane({1.f, 0.f, 0.f}, {0.f, 1.25f, 1.5f}, 26000));         // x = 0
  planes.push_back(plane({-1.f, 0.f, 0.f}, {4.f, 1.25f, 1.5f}, 24000));        // x = 4
  planes.push_back(plane({0.f, 0.f, 1.f}, {2.f, 1.25f, 0.f}, 22000));          // z = 0
  planes.push_back(plane({0.f, 0.f, -1.f}, {2.f, 1.25f, 3.f}, 20000));         // z = 3
  planes.push_back(plane({std::cos(tilt), 0.f, std::sin(tilt)}, {4.f, 1.25f, 1.5f}, 3000));
  planes.push_back(plane({std::cos(bay), 0.f, std::sin(bay)}, {4.f, 1.25f, 0.5f}, 8000));
  return planes;
}

/** Index of the frame axis closest to `expected`, or -1 when the frame does not have it. */
int axisFor(const constructar::ManhattanFrame& frame, constructar::Vec3 expected) {
  const constructar::Vec3 unit = expected.normalized();
  int best = -1;
  float bestAlignment = 0.f;
  for (int i = 0; i < 3; ++i) {
    const float alignment = std::fabs(frame.axes[i].direction.dot(unit));
    if (alignment > bestAlignment) {
      bestAlignment = alignment;
      best = i;
    }
  }
  return bestAlignment > 0.999f ? best : -1;
}

void testManhattanFrame() {
  section("Manhattan-World frame: the room the planes are trying to be");
  const auto planes = makeRoom(0.f);
  const auto frame = constructar::solveManhattanFrame(planes, {});
  check(frame.valid, "a room with a floor and four walls determines a frame");
  check(axisFor(frame, {0.f, 1.f, 0.f}) >= 0, "the vertical axis is recovered");
  check(axisFor(frame, {1.f, 0.f, 0.f}) >= 0, "the first horizontal axis is recovered");
  check(axisFor(frame, {0.f, 0.f, 1.f}) >= 0, "the second horizontal axis is recovered");

  const constructar::Vec3 a0 = frame.axes[0].direction;
  const constructar::Vec3 a1 = frame.axes[1].direction;
  const constructar::Vec3 a2 = frame.axes[2].direction;
  checkNear(a0.length(), 1.0, 1e-5, "axes are unit length");
  checkNear(a1.length(), 1.0, 1e-5, "second axis is unit length");
  checkNear(a2.length(), 1.0, 1e-5, "third axis is unit length");
  check(std::fabs(a0.dot(a1)) < 1e-5 && std::fabs(a0.dot(a2)) < 1e-5 &&
            std::fabs(a1.dot(a2)) < 1e-5,
        "the three axes are exactly orthogonal, not nearly so");
  check(a0.cross(a1).dot(a2) > 0.999f, "the frame is right-handed");
  check(frame.residualDegrees < 2.f,
        "the residual is the crooked wall's share of the evidence, not the whole room's");

  const auto snapped = constructar::snapPlanes(planes, frame, {});
  check(snapped.size() == planes.size(), "one result per plane, so callers can index by plane");
  check(snapped[0].snapped && snapped[0].axis == axisFor(frame, {0.f, 1.f, 0.f}),
        "the floor snaps to the vertical axis");
  checkNear(snapped[0].offset, 0.0, 1e-6, "an aligned plane keeps its offset exactly");
  // The frame itself is 0.2° off square, because the crooked wall has a vote (that is what
  // least squares means) - so a square wall 4 m out lands 5 mm from 4.000 m, not on it. The
  // exact identity is checked below on a room with no crooked plane in it.
  checkNear(snapped[3].offset, -4.0, 0.02,
            "a square wall keeps the distance it was measured at, to the frame's own tilt");
  check(snapped[2].normal.dot(constructar::Vec3(1.f, 0.f, 0.f)) > 0.999f,
        "a normal keeps the sign it was measured with");
  check(snapped[5].normal.dot(constructar::Vec3(0.f, 0.f, -1.f)) > 0.999f,
        "a wall facing -z is not flipped into the room by the snap");
  check(snapped[0].roomId == 0 && snapped[6].roomId == 0, "the room id is carried through");

  // The 8° wall: drift, so it is squared — about its own points, which is what keeps the wall
  // at x = 4.00 m instead of at 4.00·cos(8°).
  check(snapped[6].snapped, "a wall 8° off square is treated as drift");
  checkNear(snapped[6].angleDegrees, 8.0, 0.6, "the reported angle is the angle it was off by");
  checkNear(snapped[6].offset, 4.0, 0.02,
            "the squared wall stays where its own inliers are, not at their cosine");

  // The 35° bay: a real feature. Forcing it square would invent a room that does not exist.
  check(!snapped[7].snapped && snapped[7].axis == -1, "a 35° bay window is left alone");
  check(snapped[7].normal.x == planes[7].normal.x && snapped[7].offset == planes[7].offset,
        "an unsnapped plane is copied bit-for-bit");

  // The same room with every plane square: now the frame is exact, and so is the snap - the
  // plane passes through its own centroid and nowhere else.
  std::vector<constructar::PlaneObservation> square;
  for (int i = 0; i < 6; ++i) square.push_back(planes[i]);
  const auto squareFrame = constructar::solveManhattanFrame(square, {});
  const auto squareSnapped = constructar::snapPlanes(square, squareFrame, {});
  check(std::fabs(squareFrame.axes[1].direction.dot(constructar::Vec3(1.f, 0.f, 0.f))) > 0.999999f ||
            std::fabs(squareFrame.axes[2].direction.dot(constructar::Vec3(1.f, 0.f, 0.f))) >
                0.999999f,
        "a room with no crooked plane gives a frame on the world axes");
  checkNear(squareSnapped[3].offset, -4.0, 1e-3,
            "a square wall is where it was measured, to the millimetre");
  checkNear(squareSnapped[1].offset, -2.5, 1e-3, "the ceiling keeps its height to the millimetre");

  // Without a centroid the plane can only be turned about its closest point to the origin.
  std::vector<constructar::PlaneObservation> noCentroid = planes;
  for (auto& plane : noCentroid) plane.hasCentroid = false;
  const auto footPoint = constructar::snapPlanes(noCentroid,
                                                 constructar::solveManhattanFrame(noCentroid, {}),
                                                 {});
  check(footPoint[6].snapped, "the fallback still snaps the plane");
  checkNear(footPoint[6].offset,
            static_cast<double>(planes[6].offset) * std::cos(snapped[6].angleDegrees *
                                                            constructar::kPi / 180.f),
            1e-3, "without a centroid the offset is the foot-point rotation d·cos(angle)");

  // Turning the room must turn the frame: the solver may not assume the world is square.
  const float turn = 30.f * constructar::kPi / 180.f;
  const auto turned = makeRoom(30.f);
  const auto turnedFrame = constructar::solveManhattanFrame(turned, {});
  check(turnedFrame.valid, "a room turned 30° about the vertical still determines a frame");
  check(axisFor(turnedFrame, {std::cos(turn), 0.f, -std::sin(turn)}) >= 0,
        "the frame turns with the room instead of staying on the world axes");
  const auto turnedSnapped = constructar::snapPlanes(turned, turnedFrame, {});
  check(turnedSnapped[6].snapped && !turnedSnapped[7].snapped,
        "the same wall is drift and the same bay is a feature, whatever the heading");
  checkNear(turnedSnapped[6].offset, 4.0, 0.02,
            "the squared wall is still 4 m from the room's own axis after the turn");

  // A room whose floor and walls carry equal weight. The two horizontal families then couple
  // through the crooked wall, their rotated pair out-eigenvalues the vertical, and a solver that
  // trusts the top eigenvector puts the "vertical" 35 degrees off the floor (finding 189). The
  // frame is a set of three axes: which one is vertical is what the room decides.
  section("Manhattan-World frame: the vertical is not the top eigenvector");
  std::vector<constructar::PlaneObservation> evenRoom{
      {{0.f, 1.f, 0.f}, 0.f, 1000, constructar::Vec3(2.f, 0.f, 1.5f)},
      {{0.f, -1.f, 0.f}, -2.5f, 1000, constructar::Vec3(2.f, 2.5f, 1.5f)},
      {{1.f, 0.f, 0.f}, 0.f, 1000, constructar::Vec3(0.f, 1.25f, 1.5f)},
      {{-1.f, 0.f, 0.f}, -4.f, 1000, constructar::Vec3(4.f, 1.25f, 1.5f)},
      {{0.f, 0.f, 1.f}, 0.f, 1000, constructar::Vec3(2.f, 1.25f, 0.f)},
      {{0.f, 0.f, -1.f}, -3.f, 1000, constructar::Vec3(2.f, 1.25f, 3.f)},
  };
  const auto evenFrame = constructar::solveManhattanFrame(evenRoom, {});
  check(evenFrame.valid, "a room with equal surfaces still determines a frame");
  check(axisFor(evenFrame, {0.f, 1.f, 0.f}) >= 0,
        "and the vertical is an axis of it, not a 35-degree diagonal");
  check(axisFor(evenFrame, {1.f, 0.f, 0.f}) >= 0 && axisFor(evenFrame, {0.f, 0.f, 1.f}) >= 0,
        "with the two wall families on the other two axes");
  // The floor and ceiling alone are still one direction and not a frame.
  const auto floorsOnly = constructar::solveManhattanFrame(
      {evenRoom[0], evenRoom[1]}, {});
  check(!floorsOnly.valid, "floor and ceiling alone is still one direction, not a frame");

  section("Manhattan-World frame: what it refuses to invent");
  const std::vector<constructar::PlaneObservation> parallel{planes[0], planes[1]};
  const auto flat = constructar::solveManhattanFrame(parallel, {});
  check(!flat.valid, "a floor and a ceiling are one direction, not a frame");
  const auto untouched = constructar::snapPlanes(parallel, flat, {});
  check(!untouched[0].snapped && untouched[1].snapped == false,
        "an invalid frame changes nothing");
  check(untouched[0].normal.y == 1.f && untouched[1].offset == -2.5f,
        "the planes come back exactly as measured");

  const auto empty = constructar::solveManhattanFrame({}, {});
  check(!empty.valid, "no planes, no frame");
  check(constructar::snapPlanes({}, empty, {}).empty(), "no planes, no results");

  std::vector<constructar::PlaneObservation> noisy = planes;
  noisy[0].inliers = 2;  // below minInliers: not evidence
  const auto thin = constructar::solveManhattanFrame(noisy, {});
  check(thin.valid, "dropping one plane does not invalidate the room");
  check(thin.axes[axisFor(thin, {0.f, 1.f, 0.f})].supportingPlanes == 1,
        "a plane below the inlier floor is not counted as support");

  std::vector<constructar::PlaneObservation> broken = planes;
  broken.push_back(constructar::PlaneObservation(
      {std::nanf(""), 0.f, 0.f}, 1.f, 50000, constructar::Vec3(0.f, 0.f, 0.f)));
  broken.push_back(constructar::PlaneObservation({0.f, 0.f, 0.f}, 1.f, 50000));
  const auto guarded = constructar::solveManhattanFrame(broken, {});
  check(guarded.valid, "a NaN normal and a zero normal do not poison the frame");
  const auto guardedSnap = constructar::snapPlanes(broken, guarded, {});
  check(guardedSnap.size() == broken.size() && !guardedSnap[8].snapped &&
            !guardedSnap[9].snapped,
        "unusable normals are copied through, not replaced by an axis");

  // Determinism: the same input twice is the same frame, bit for bit. A solver whose answer
  // depends on iteration order cannot be regression-tested, and the app caches by input.
  const auto again = constructar::solveManhattanFrame(planes, {});
  check(std::memcmp(&frame.axes[0].direction, &again.axes[0].direction, sizeof(float) * 3) == 0 &&
            std::memcmp(&frame.axes[2].direction, &again.axes[2].direction,
                        sizeof(float) * 3) == 0,
        "solving the same planes twice gives the same axes");
}


/**
 * The AprilTag contract: an interface whose only implementation says "no decoder", and the
 * marker-to-scale arithmetic that a decoder will feed. No decoder exists, so nothing here decodes
 * a pixel - what is tested is the arithmetic, the outlier filter and the honesty of the absence.
 */
std::vector<constructar::TagLayoutEntry> standardLayout() {
  return {
      {0, {0.f, 0.f, 0.f}},
      {1, {1.f, 0.f, 0.f}},
      {2, {0.f, 0.f, 1.f}},
      {3, {1.f, 0.f, 1.f}},
      {4, {0.5f, 0.f, 0.5f}},
  };
}

/** The layout as the scan would report it if the scan's metre were `stretch` times long. */
std::vector<constructar::TagSighting> stretchedSightings(float stretch) {
  std::vector<constructar::TagSighting> sightings;
  for (const constructar::TagLayoutEntry& entry : standardLayout()) {
    sightings.push_back({entry.id, entry.nominal * stretch, 0.9f, 0});
  }
  return sightings;
}

void testTagScaleContract() {
  section("AprilTag contract: the absence is explicit");
  const auto detector = constructar::makeTagDetector();
  check(detector != nullptr, "the build always offers a detector object, even with no decoder");
  check(!detector->available(), "a build with no decoder says it is unavailable");
  const char* reason = detector->unavailableReason();
  check(reason != nullptr && std::strlen(reason) > 0,
        "an unavailable detector says why, so a diagnostic screen can print it");
  check(reason != nullptr && std::strstr(reason, "contract") != nullptr,
        "the reason names the contract rather than blaming the frame");

  std::vector<uint8_t> pixels(static_cast<size_t>(8 * 8), 128);
  constructar::GrayFrameView view{pixels.data(), 8, 8, 8, 0.0};
  check(view.isUsable(), "a well-formed luminance view is usable");
  check(detector->detect(view).empty(),
        "with no decoder a frame yields no detections - which is why `available()` exists");
  constructar::GrayFrameView broken{nullptr, 8, 8, 8, 0.0};
  check(!broken.isUsable(), "a view with no buffer is refused before a decoder reads it");
  constructar::GrayFrameView narrow{pixels.data(), 8, 8, 4, 0.0};
  check(!narrow.isUsable(), "a row stride narrower than the row is refused");
  check(detector->detect(broken).empty(), "a broken view is not a crash");

  section("AprilTag contract: marker to scale, and what it refuses");
  const auto layout = standardLayout();

  // Five tags stretched 5 %: ten ratios that must agree.
  const auto stretched = stretchedSightings(1.05f);
  const auto estimate = constructar::estimateScaleFromTagSightings(stretched, layout, {});
  check(estimate.valid, "five tags at a known spacing determine a factor");
  checkNear(estimate.factor, 1.0 / 1.05, 1e-4,
            "the factor is nominal over measured, the same direction as the app's calibration");
  check(estimate.pairs == 10, "every pair of trusted tags contributes a ratio");
  check(estimate.rejectedPairs == 0, "ten agreeing pairs reject nothing");
  checkNear(estimate.spread, 0.0, 1e-6, "pairs that agree exactly have zero spread");
  checkNear(estimate.baselineMetres, 1.4142135, 1e-3,
            "the published baseline is the longest one the factor rests on");

  // Two tags are one pair, and one pair is not a factor.
  std::vector<constructar::TagSighting> two{stretched[0], stretched[1]};
  std::vector<constructar::TagLayoutEntry> twoLayout{layout[0], layout[1]};
  const auto single = constructar::estimateScaleFromTagSightings(two, twoLayout, {});
  check(!single.valid, "one pair is not enough evidence to publish a factor");
  check(single.pairs == 1, "the count still says one pair was seen - 'not enough' is not 'nothing'");
  check(single.factor == 1.f, "a refused estimate leaves the factor at one, never at a guess");
  constructar::TagScaleOptions onePair;
  onePair.minPairs = 1;
  const auto allowed = constructar::estimateScaleFromTagSightings(two, twoLayout, onePair);
  check(allowed.valid, "a caller that accepts one pair can have one pair");
  checkNear(allowed.factor, 1.0 / 1.05, 1e-5, "and the factor is that pair's ratio exactly");

  // A minority of bad evidence: one tag stuck a metre from where it belongs, so its four pairs
  // are wrong and the other six are right. The median has a majority to find; a robust estimator
  // has nothing to say at 50 % contamination, which is why this test does not ask it to.
  std::vector<constructar::TagSighting> withOutlier = stretched;
  withOutlier[4].centroid = {1.4f, 0.f, 1.4f};
  const auto filtered = constructar::estimateScaleFromTagSightings(withOutlier, layout, {});
  check(filtered.valid, "an estimate survives one tag being wrong");
  check(filtered.pairs == 6 && filtered.rejectedPairs == 4,
        "the four pairs the bad tag touches are rejected and the six that do not agree are kept");
  checkNear(filtered.factor, 1.0 / 1.05, 1e-4, "the outlier does not move the factor");

  // Confidence and error correction: a tag read through a corrected bit is not a ruler.
  std::vector<constructar::TagSighting> blurred = stretched;
  blurred[1].decisionMargin = 0.3f;
  const auto lowMargin = constructar::estimateScaleFromTagSightings(blurred, layout, {});
  check(!lowMargin.valid || lowMargin.pairs == 6,
        "a tag below the decision margin contributes no pair");
  check(lowMargin.valid && lowMargin.pairs == 6,
        "the other four tags still agree: six pairs, not ten");
  std::vector<constructar::TagSighting> corrected = stretched;
  corrected[1].hamming = 1;
  const auto guessed = constructar::estimateScaleFromTagSightings(corrected, layout, {});
  check(guessed.valid && guessed.pairs == 6, "a tag that needed a corrected bit is not used");

  // One tag, two sightings: the more confident one is the tag.
  std::vector<constructar::TagSighting> duplicated = stretched;
  duplicated.push_back({0, {0.f, 0.f, 0.4f}, 0.7f, 0});
  const auto deduped = constructar::estimateScaleFromTagSightings(duplicated, layout, {});
  check(deduped.valid && deduped.pairs == 10,
        "a second sighting of the same tag replaces nothing - one tag is one tag");
  checkNear(deduped.factor, 1.0 / 1.05, 1e-4,
            "the confident sighting wins, so a ghost detection cannot move the factor");

  // An id the layout does not know contributes nothing, and says so by counting no pairs.
  std::vector<constructar::TagSighting> unknown = stretched;
  unknown.push_back({99, {0.5f, 0.5f, 0.5f}, 0.99f, 0});
  const auto withUnknown = constructar::estimateScaleFromTagSightings(unknown, layout, {});
  check(withUnknown.valid && withUnknown.pairs == 10,
        "an id the printed layout does not contain is ignored");

  // Weighting: a 3 m baseline is much better evidence than a 0.3 m one. With the deviation filter
  // opened up so both families are kept, the factor must sit near the long pairs' ratio rather
  // than at the unweighted mean of the three.
  std::vector<constructar::TagLayoutEntry> weightedLayout{
      {0, {0.f, 0.f, 0.f}}, {1, {3.f, 0.f, 0.f}}, {2, {0.f, 0.f, 0.3f}}};
  std::vector<constructar::TagSighting> weightedSightings{
      {0, {0.f, 0.f, 0.f}, 0.9f, 0},
      {1, {3.3f, 0.f, 0.f}, 0.9f, 0},   // the scan's metre is 10 % long along the long baseline
      {2, {0.f, 0.f, 0.3f}, 0.9f, 0},   // and right along a baseline too short to notice
  };
  constructar::TagScaleOptions generous;
  generous.maxRelativeDeviation = 0.2f;
  const auto weighted =
      constructar::estimateScaleFromTagSightings(weightedSightings, weightedLayout, generous);
  check(weighted.valid, "both families are kept when the filter allows it");
  check(std::fabs(static_cast<double>(weighted.factor) - 1.0 / 1.10) < 0.01,
        "the factor follows the 3 m baseline, not the 0.3 m one");
  check(std::fabs(static_cast<double>(weighted.factor) - (1.0 / 1.10 + 1.0 + 1.0 / 1.1) / 3.0) >
            0.02,
        "and it is not the unweighted mean of the three ratios");

  // Two tags a micron apart: the ratio would be 10^6 rather than 0.95, and a factor of a million
  // is the kind of number that ends up in a protocol. The baseline floor is what stops it.
  std::vector<constructar::TagSighting> collapsed{stretched[0], stretched[1]};
  collapsed[1].centroid = collapsed[0].centroid + constructar::Vec3(1e-6f, 0.f, 0.f);
  const auto degenerate = constructar::estimateScaleFromTagSightings(collapsed, twoLayout, {});
  check(!degenerate.valid && degenerate.factor == 1.f,
        "a micrometre baseline is not divided by: no factor rather than a factor of 10^6");
  // With the pair count relaxed to one, the only thing standing between that division and a
  // published factor is the baseline floor - so this is the case that pins it.
  const auto degenerateAllowed =
      constructar::estimateScaleFromTagSightings(collapsed, twoLayout, onePair);
  check(!degenerateAllowed.valid && degenerateAllowed.pairs == 0,
        "even a caller that accepts one pair gets nothing from a micrometre baseline");

  // Nothing at all.
  const auto empty = constructar::estimateScaleFromTagSightings({}, layout, {});
  check(!empty.valid && empty.pairs == 0 && empty.factor == 1.f,
        "no sightings, no factor, no invented number");

  // Determinism: the same evidence twice is the same factor, bit for bit.
  const auto again = constructar::estimateScaleFromTagSightings(stretched, layout, {});
  check(std::memcmp(&estimate.factor, &again.factor, sizeof(float)) == 0,
        "estimating twice gives the same factor");
}


/** Deterministic pseudo-noise for the synthetic clouds: no <random>, no clock, same on any host. */
struct SyntheticNoise {
  std::uint32_t state = 0x1BADB002u;
  float unit() {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return static_cast<float>(state & 0xFFFFu) / 65535.f;
  }
  float symmetric(float amplitude) { return (unit() * 2.f - 1.f) * amplitude; }
};

/**
 * A 4 x 3 m room, 2.5 m high, 800 points per surface, 3 mm of noise - and one wall deliberately
 * 8 degrees off square about its own centre, so its distance from the room is 4.00 m and only its
 * angle is wrong. That is the case the whole chain exists for.
 */
std::vector<constructar::Vec3> makeRoomCloud() {
  SyntheticNoise noise;
  std::vector<constructar::Vec3> cloud;
  const float amplitude = 0.003f;
  const int perSurface = 800;

  // A patch on the plane through `centre` spanned by `u` and `v`.
  const auto surface = [&cloud, &noise, amplitude, perSurface](
                           constructar::Vec3 centre, constructar::Vec3 u, constructar::Vec3 v,
                           float halfU, float halfV) {
    for (int i = 0; i < perSurface; ++i) {
      const float a = (noise.unit() * 2.f - 1.f) * halfU;
      const float b = (noise.unit() * 2.f - 1.f) * halfV;
      const constructar::Vec3 point = centre + u * a + v * b;
      cloud.push_back({point.x + noise.symmetric(amplitude),
                       point.y + noise.symmetric(amplitude),
                       point.z + noise.symmetric(amplitude)});
    }
  };

  const constructar::Vec3 x{1.f, 0.f, 0.f};
  const constructar::Vec3 y{0.f, 1.f, 0.f};
  const constructar::Vec3 z{0.f, 0.f, 1.f};
  surface({2.f, 0.f, 1.5f}, x, z, 2.f, 1.5f);      // floor
  surface({2.f, 2.5f, 1.5f}, x, z, 2.f, 1.5f);     // ceiling
  surface({0.f, 1.25f, 1.5f}, z, y, 1.5f, 1.25f);  // x = 0
  surface({2.f, 1.25f, 0.f}, x, y, 2.f, 1.25f);    // z = 0
  surface({2.f, 1.25f, 3.f}, x, y, 2.f, 1.25f);    // z = 3

  // The crooked wall: the plane x = 4 turned 8 degrees about the vertical through its centre.
  const float turn = 8.f * constructar::kPi / 180.f;
  const constructar::Vec3 turned{std::sin(turn), 0.f, std::cos(turn)};
  surface({4.f, 1.25f, 1.5f}, turned, y, 1.5f, 1.25f);
  return cloud;
}

void testPlaneFit() {
  section("Plane fitting: six surfaces, one of them crooked");
  const auto cloud = makeRoomCloud();
  constructar::PlaneFitOptions options;
  const auto planes = constructar::fitPlanes(cloud, options);
  check(planes.size() == 6, "a six-surface room yields six planes (got " +
                                std::to_string(planes.size()) + ")");

  bool everyPlaneBigEnough = true;
  bool everyPlaneFlat = true;
  bool everyNormalPointsIn = true;
  const constructar::Vec3 roomCentre{2.f, 1.25f, 1.5f};
  for (const auto& plane : planes) {
    if (plane.inliers < 700) everyPlaneBigEnough = false;
    if (plane.planarity < 0.999f) everyPlaneFlat = false;
    if (plane.normal.dot(roomCentre - plane.centroid) <= 0.f) everyNormalPointsIn = false;
  }
  check(everyPlaneBigEnough, "each plane explains most of the points on its surface");
  check(everyPlaneFlat, "each fitted plane is flat to four nines: the noise is 3 mm");
  check(everyNormalPointsIn,
        "every normal points into the room, so two fits of one room agree on the sign");

  // No surface may arrive in two pieces. This is what a RANSAC budget that is too small looks
  // like from the outside - not a failure, a wall split in half - so it is asserted directly.
  bool noSurfaceSplit = true;
  for (size_t i = 0; i < planes.size(); ++i) {
    for (size_t j = i + 1; j < planes.size(); ++j) {
      if (std::fabs(planes[i].normal.dot(planes[j].normal)) > 0.999f &&
          std::fabs(planes[i].offset - planes[j].offset) < 0.05f) {
        noSurfaceSplit = false;
      }
    }
  }
  check(noSurfaceSplit, "no surface is reported twice, which is how a small RANSAC budget fails");

  const auto axisPlane = [&planes](constructar::Vec3 axis) -> const constructar::FittedPlane* {
    for (const auto& plane : planes) {
      if (std::fabs(plane.normal.dot(axis)) > 0.999f) return &plane;
    }
    return nullptr;
  };
  check(axisPlane({0.f, 1.f, 0.f}) != nullptr, "the floor and ceiling are found on the vertical");
  check(axisPlane({0.f, 0.f, 1.f}) != nullptr, "the two square walls are found");

  // The crooked wall is the one whose normal is close to -x but not on it: that is what 8 degrees
  // off square looks like after a least-squares fit, and it is the whole point of the chain.
  const constructar::FittedPlane* crooked = nullptr;
  for (const auto& plane : planes) {
    const float alongX = std::fabs(plane.normal.dot(constructar::Vec3(1.f, 0.f, 0.f)));
    if (alongX > 0.95f && alongX < 0.999f) crooked = &plane;
  }
  check(crooked != nullptr, "the crooked wall is fitted, and fitted crooked");
  check(crooked != nullptr &&
            std::fabs(std::acos(std::fabs(crooked->normal.x)) * 180.f / constructar::kPi - 8.f) <
                1.f,
        "the fit reports it 8 degrees off the room's axis");

  const constructar::FittedPlane* floor = axisPlane({0.f, 1.f, 0.f});
  check(floor != nullptr && std::fabs(floor->offset) < 0.005f,
        "the floor's offset is where the floor is");
  check(floor != nullptr && floor->normal.y > 0.f, "the floor's normal points up, into the room");

  // Determinism: same input, same seed, same planes - down to the bytes.
  const auto again = constructar::fitPlanes(cloud, options);
  bool identical = again.size() == planes.size();
  if (identical) {
    for (size_t i = 0; i < planes.size(); ++i) {
      if (std::memcmp(&planes[i].normal, &again[i].normal, sizeof(constructar::Vec3)) != 0 ||
          planes[i].offset != again[i].offset || planes[i].inliers != again[i].inliers) {
        identical = false;
        break;
      }
    }
  }
  check(identical, "the same cloud and seed give the same planes, bit for bit");
  constructar::PlaneFitOptions otherSeed;
  otherSeed.seed = 0xC0FFEE01u;
  const auto reseeded = constructar::fitPlanes(cloud, otherSeed);
  check(reseeded.size() == 6,
        "a different seed finds the same six surfaces: the seed changes the sampling, not the room");

  // The endpoint of the chain: cloud -> planes -> frame -> snapped planes.
  section("Plane fitting: the frame the room is trying to be");
  const auto observations = constructar::asPlaneObservations(planes, 3);
  check(observations.size() == planes.size() && observations[0].hasCentroid,
        "the solver is handed centroids, so it can square a plane through its own points");
  check(observations[0].roomId == 3 && observations[0].inliers > 0,
        "the room id and the inlier count travel with the plane");

  const auto frame = constructar::solveManhattanFrame(observations, {});
  check(frame.valid, "six fitted planes determine a frame");
  int verticalAxis = -1;
  for (int i = 0; i < 3; ++i) {
    if (std::fabs(frame.axes[i].direction.dot(constructar::Vec3(0.f, 1.f, 0.f))) > 0.999f) {
      verticalAxis = i;
    }
  }
  check(verticalAxis >= 0, "one of the frame's axes is the room's vertical");
  check(axisFor(frame, {1.f, 0.f, 0.f}) >= 0 && axisFor(frame, {0.f, 0.f, 1.f}) >= 0,
        "and the other two are the room's horizontal axes, not the crooked wall's");
  check(frame.residualDegrees > 2.f && frame.residualDegrees < 12.f,
        "the residual is the crooked wall's share of the evidence, and nothing else");

  const auto snapped = constructar::snapPlanes(observations, frame, {});
  size_t snappedCount = 0;
  for (const auto& plane : snapped) {
    if (plane.snapped) ++snappedCount;
  }
  check(snappedCount == 6, "every surface in a normal room is close enough to snap");

  size_t crookedIndex = 0;
  for (size_t i = 0; i < planes.size(); ++i) {
    if (&planes[i] == crooked) crookedIndex = i;
  }
  check(std::fabs(snapped[crookedIndex].angleDegrees - 8.f) < 1.5f,
        "the crooked wall is reported as 8 degrees off square");
  check(snapped[crookedIndex].snapped &&
            std::fabs(std::fabs(snapped[crookedIndex].normal.x) - 1.f) < 0.001f,
        "squaring it makes its normal the room's axis");
  check(std::fabs(std::fabs(snapped[crookedIndex].offset) - 4.f) < 0.02f,
        "and leaves it at the 4 m the operator measured, not at 4 m times a cosine");

  section("Plane fitting: what it refuses");
  constructar::PlaneFitOptions threeOnly;
  threeOnly.maxPlanes = 3;
  check(constructar::fitPlanes(cloud, threeOnly).size() == 3, "maxPlanes is respected");
  constructar::PlaneFitOptions greedy;
  greedy.minInliers = 5000;
  check(constructar::fitPlanes(cloud, greedy).empty(),
        "a room's surfaces are each ~800 points: a 5000-point floor finds nothing");
  check(constructar::fitPlanes({}, options).empty(), "no points, no planes");

  std::vector<constructar::Vec3> line;
  for (int i = 0; i < 200; ++i) line.push_back({static_cast<float>(i) * 0.01f, 0.f, 0.f});
  check(constructar::fitPlanes(line, options).empty(),
        "a line fits infinitely many planes and reports none of them");

  std::vector<constructar::Vec3> withNaN = cloud;
  withNaN.push_back({std::nanf(""), 0.f, 0.f});
  withNaN.push_back({0.f, std::nanf(""), 0.f});
  check(constructar::fitPlanes(withNaN, options).size() == 6,
        "a non-finite point is skipped, not fitted");
}

void testRtabMapBridgeWithoutTheBackEnd() {
  section("RTAB-Map bridge: what a build without the back-end promises");
  constructar::RtabMapBridge bridge;
  check(!bridge.isAvailable(), "the back-end is reported as not compiled in");
  check(bridge.graphSize() == 0, "an empty bridge holds no nodes");

  std::vector<uint8_t> rgb(static_cast<size_t>(8 * 8 * 3), 0);
  std::vector<uint16_t> depth(static_cast<size_t>(8 * 8), 0);
  const auto update = bridge.processKeyframe(rgb.data(), 8 * 3, depth.data(), 8, 8, 8, 200.f, 200.f,
                                             4.f, 4.f, Mat4::identity(), 0);
  check(!update.available, "a keyframe is not claimed to have been processed");
  check(!update.stored && !update.loopClosureDetected && update.matchedNodeId == -1,
        "the no-op reports neither a store nor a closure");
  check(bridge.graphSize() == 0, "an ignored keyframe is not a node in the graph");
  check(!bridge.optimizeGraph().stored, "there is no global pass to run");
  bridge.reset();
  check(!bridge.isAvailable() && bridge.graphSize() == 0, "a reset leaves it unavailable and empty");
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
  testTiltedPlane();
  testCarveFreeSpace();
  testPoseGraphLoopClosure();
  testProximityDetector();
  testTriangleBudgetAndReset();
  testBlockBudget();
  testBlockBudgetUnderConcurrency();
  testBlocksAllocatedPerFrame();
  testVoxelFusionUnderConcurrency();
  testManhattanFrame();
  testTagScaleContract();
  testPlaneFit();
  testRtabMapBridgeWithoutTheBackEnd();
  testInvalidInput();

  std::printf("\n%d checks, %d failures\n", g_checks, g_failures);
  if (g_failures == 0) std::printf("ALL NATIVE TESTS PASSED\n");
  return g_failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
