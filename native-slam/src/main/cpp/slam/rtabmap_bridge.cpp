/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

#include "rtabmap_bridge.hpp"

#include <algorithm>
#include <chrono>
#include <cstring>

// ─────────────────────────────────────────────────────────────────────────────────────────
// RTAB-Map enabled build.
//
// Licensing: RTAB-Map core is BSD-3-Clause. The build MUST NOT pull OpenCV's non-free
// modules (SURF/SIFT patented variants) — see CMakeLists.txt, which passes
// `-DWITH_OPENCV_NONFREE=OFF -DWITH_SURF=OFF` and restricts the detector to ORB/SIFT.
//
// The includes live *outside* `namespace constructar`: RTAB-Map's headers pull in <string>,
// <vector> and OpenCV, and a standard header included inside a namespace is not the standard
// library any more — it becomes `constructar::std::…`, which fails to compile in hundreds of
// lines of template error (finding 107). This block used to sit below `namespace constructar {`,
// so a `CONSTRUCTAR_WITH_RTABMAP=ON` build could never have compiled. The `native-tests` job
// compiles this translation unit in that configuration against the stub headers in
// `tests/rtabmap-stubs`, so the branch cannot rot again.
// ─────────────────────────────────────────────────────────────────────────────────────────
#if defined(CONSTRUCTAR_WITH_RTABMAP)
#include <rtabmap/core/CameraModel.h>
#include <rtabmap/core/Parameters.h>
#include <rtabmap/core/Rtabmap.h>
#include <rtabmap/core/SensorData.h>
#include <rtabmap/core/util3d.h>
#endif

namespace constructar {

#if defined(CONSTRUCTAR_WITH_RTABMAP)

struct RtabMapBridge::Impl {
  rtabmap::Rtabmap rtabmap;
  rtabmap::CameraModel cameraModel;
  int nodeId = 0;
  bool initialized = false;

  void configure(int width, int height, float fx, float fy, float cx, float cy) {
    // `Parameters::kX` are the *keys*; a parameter is a key/value pair. They used to be written
    // as `params.insert(Parameters::kX("value"))`, i.e. called like functions - which cannot
    // compile, and had never been compiled (finding 107).
    rtabmap::ParametersMap params;
    // Global graph + loop closure, in-memory only (offline-first: no database, no cloud).
    params.insert({rtabmap::Parameters::kMemIncrementalMemory, "true"});
    params.insert({rtabmap::Parameters::kMemInitWMWithAllNodes, "true"});
    params.insert({rtabmap::Parameters::kRGBDEnabled, "true"});
    params.insert({rtabmap::Parameters::kRGBDCreateOccupancyGrid, "false"});
    // ORB features only — never SURF/OpenCV-nonfree.
    params.insert({rtabmap::Parameters::kKpDetectorStrategy,
                   std::to_string(static_cast<int>(rtabmap::Feature2D::Type::kFeatureORB))});
    params.insert({rtabmap::Parameters::kKpMaxFeatures, "700"});
    params.insert({rtabmap::Parameters::kVisMinInliers, "20"});
    params.insert({rtabmap::Parameters::kOptimizerStrategy, "1"});  // g2o
    params.insert({rtabmap::Parameters::kOptimizerIterations, "20"});
    rtabmap.setParameters(params);
    cameraModel = rtabmap::CameraModel(fx, fy, cx, cy, 0, 0, width, height, rtabmap::Transform::getIdentity());
    initialized = true;
  }
};

RtabMapBridge::RtabMapBridge() : impl_(std::make_unique<Impl>()) {}

RtabMapBridge::~RtabMapBridge() = default;

bool RtabMapBridge::isAvailable() const {
  // "Is the back-end in this build?" - not "has a keyframe been fed yet?". The second question
  // could only ever be answered false, because nothing calls processKeyframe (finding 107).
  return true;
}

SlamUpdate RtabMapBridge::processKeyframe(const uint8_t* rgb, int rgbStrideBytes,
                                          const uint16_t* depthMm, int depthStrideElements,
                                          int width, int height, float fx, float fy, float cx,
                                          float cy, const Mat4& odometryCameraToWorld,
                                          int64_t timestampNs) {
  SlamUpdate update;
  const auto started = std::chrono::steady_clock::now();
  if (rgb == nullptr || depthMm == nullptr || width <= 0 || height <= 0) return update;
  if (!impl_->initialized) impl_->configure(width, height, fx, fy, cx, cy);

  cv::Mat rgbMat(height, width, CV_8UC3, const_cast<uint8_t*>(rgb), rgbStrideBytes);
  cv::Mat depthMat(height, width, CV_16UC1, const_cast<uint16_t*>(depthMm),
                   depthStrideElements * static_cast<int>(sizeof(uint16_t)));
  cv::Mat depthMeters;
  depthMat.convertTo(depthMeters, CV_32F, 0.001);

  // Odometry comes from ARCore (already metric and gravity-aligned); RTAB-Map only has to
  // detect the loop closure and optimise the graph.
  // ARCore hands us a COLUMN-major 4×4 (`Mat4` documents that); RTAB-Map's 12-float constructor
  // reads a ROW-major 3×4 - (r11, r12, r13, o14, r21, …) per its own header. Passing m[0..11]
  // straight through therefore transposed the rotation and set the translation to zero, because
  // m[3], m[7] and m[11] are the bottom row of the rotation, not the translation (finding 107).
  const Mat4& t = odometryCameraToWorld;
  const Mat3 r = t.rotation();
  const Vec3 translation = t.translation();
  const rtabmap::Transform odom(r.m[0], r.m[3], r.m[6], translation.x,
                                r.m[1], r.m[4], r.m[7], translation.y,
                                r.m[2], r.m[5], r.m[8], translation.z);

  // The ARCore pose becomes the keyframe's odometry pose. It used to be built here and then
  // discarded with `(void)odom;`, so RTAB-Map received an identity pose for every keyframe and
  // every node would have sat at the origin of its own graph (finding 107).
  rtabmap::SensorData data(rgbMat, depthMeters, impl_->cameraModel, impl_->nodeId++,
                           static_cast<double>(timestampNs) / 1e9, odom);
  impl_->rtabmap.process(data);

  update.available = true;
  update.loopClosureDetected = impl_->rtabmap.getLoopClosureId() > 0;
  update.matchedNodeId = impl_->rtabmap.getLoopClosureId();
  const auto finished = std::chrono::steady_clock::now();
  update.milliseconds =
      std::chrono::duration_cast<std::chrono::microseconds>(finished - started).count() / 1000.0;
  return update;
}

SlamUpdate RtabMapBridge::optimizeGraph() {
  SlamUpdate update;
  if (!impl_->initialized) return update;
  impl_->rtabmap.setOptimized();
  update.available = true;
  return update;
}

int RtabMapBridge::graphSize() const { return impl_->nodeId; }

void RtabMapBridge::reset() {
  impl_->rtabmap.close();
  impl_->nodeId = 0;
  impl_->initialized = false;
}

#else  // ── No RTAB-Map: compile a stub that reports unavailability ─────────────────────────

struct RtabMapBridge::Impl {
  int nodeId = 0;
};

RtabMapBridge::RtabMapBridge() : impl_(std::make_unique<Impl>()) {}
RtabMapBridge::~RtabMapBridge() = default;
bool RtabMapBridge::isAvailable() const { return false; }

SlamUpdate RtabMapBridge::processKeyframe(const uint8_t*, int, const uint16_t*, int, int, int,
                                          float, float, float, float, const Mat4&, int64_t) {
  // The app calls this unconditionally; a cheap no-op keeps the Kotlin pipeline branch-free.
  ++impl_->nodeId;
  SlamUpdate update;
  update.available = false;
  return update;
}

SlamUpdate RtabMapBridge::optimizeGraph() { return SlamUpdate{}; }
int RtabMapBridge::graphSize() const { return impl_->nodeId; }
void RtabMapBridge::reset() { impl_->nodeId = 0; }

#endif

}  // namespace constructar
