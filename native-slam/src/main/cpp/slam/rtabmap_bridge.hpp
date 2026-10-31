// RTAB-Map bridge (BSD-3-Clause, no OpenCV non-free modules).
//
// RTAB-Map is the production loop-closure / global-graph back-end. It is *optional at build
// time* on purpose:
//
//   * CMake option `CONSTRUCTAR_WITH_RTABMAP=ON` + `RTABMAP_SOURCE_DIR=...` compiles the
//     real integration (see docs/SETUP.md §3 for the BSD-only build flags: `-DWITH_SURF=OFF
//     -DWITH_OPENCV_NONFREE=OFF`, ORB/SIFT features only).
//   * Without it, `isAvailable()` returns false and the app falls back to
//     [ProximityLoopClosureDetector] + the pose graph, which still removes drift but cannot
//     verify an appearance-based loop closure.
//
// Either way the Kotlin side talks to the same interface, so the AR pipeline never needs a
// compile-time fork.
#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "../core/types.hpp"

namespace constructar {

/** Result of feeding one keyframe. */
struct SlamUpdate {
  bool available = false;        // false when compiled without RTAB-Map
  bool loopClosureDetected = false;
  int matchedNodeId = -1;
  /** Optimised keyframe poses in world space (camera → world). */
  std::vector<Mat4> optimizedPoses;
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

  /** True when this build has RTAB-Map compiled in and it initialised successfully. */
  bool isAvailable() const;

  /** Feeds one keyframe; returns the (possibly unchanged) optimised pose list. */
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
