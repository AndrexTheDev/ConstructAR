/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// RTAB-Map bridge (BSD-3-Clause, no OpenCV non-free modules).
//
// RTAB-Map is the production loop-closure / global-graph back-end. It is *optional at build
// time* on purpose:
//
//   * CMake option `CONSTRUCTAR_WITH_RTABMAP=ON` + `RTABMAP_SOURCE_DIR=...` compiles the
//     real integration (see docs/SETUP.md §5.1 for the BSD-only recipe: OpenCV built with
//     `-DOPENCV_ENABLE_NONFREE=OFF`, so only SIFT/ORB/BRISK are available).
//   * Without it, `isAvailable()` returns false, so the Kotlin side simply sees no back-end.
//
// Neither branch is driven yet (finding 98): nothing in `:app`/`:ar-engine` feeds a keyframe or
// runs the optimiser, so the app measures on ARCore's own tracking and no closure is optimised.
// What is below is the engine a wiring will use, not what the app runs today — see
// docs/SETUP.md §5.2.
//
// Either way the Kotlin side talks to the same interface, so the AR pipeline never needs a
// compile-time fork.
#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "../core/types.hpp"

namespace constructar {

/**
 * Result of feeding one keyframe.
 *
 * There used to be a `std::vector<Mat4> optimizedPoses` here, documented as "optimised keyframe
 * poses in world space". Neither branch ever wrote it and nothing ever read it: the pose list a
 * caller actually wants comes from `PoseGraph::optimize`, which the JNI exposes on its own
 * (finding 107). A field that no line fills is a promise in a header.
 */
struct SlamUpdate {
  bool available = false;        // false when compiled without RTAB-Map
  bool loopClosureDetected = false;
  int matchedNodeId = -1;
  double milliseconds = 0.0;
};

/**
 * Thin, allocation-free-per-call wrapper around `rtabmap::Rtabmap`.
 *
 * The pimpl keeps RTAB-Map's heavy headers (and its OpenCV dependency) out of every
 * translation unit that only needs the interface.
 */
class RtabMapBridge {
 public:
  RtabMapBridge();
  ~RtabMapBridge();

  RtabMapBridge(const RtabMapBridge&) = delete;
  RtabMapBridge& operator=(const RtabMapBridge&) = delete;

  /**
   * True when this build was compiled with RTAB-Map.
   *
   * It used to answer "…and `configure()` has run", which is a question only `processKeyframe`
   * can make true - so in a build with the back-end compiled in, the answer was still false until
   * a keyframe arrived, and nothing feeds keyframes to this class yet. The Kotlin caller's doc has
   * always said "true when this build was compiled with the RTAB-Map back-end", and this is that
   * sentence implemented (finding 107).
   */
  bool isAvailable() const;

  /** Feeds one keyframe (RGB + the ARCore odometry pose) and returns what RTAB-Map made of it. */
  SlamUpdate processKeyframe(const uint8_t* rgb, int rgbStrideBytes, const uint16_t* depthMm,
                             int depthStrideElements, int width, int height, float fx, float fy,
                             float cx, float cy, const Mat4& odometryCameraToWorld,
                             int64_t timestampNs);

  /** Forces a global graph optimisation (called on "finish scan"). */
  SlamUpdate optimizeGraph();

  /** Number of nodes RTAB-Map currently holds. */
  int graphSize() const;

  void reset();

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace constructar
