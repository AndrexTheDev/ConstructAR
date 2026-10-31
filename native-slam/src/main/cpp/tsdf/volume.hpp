/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// Sparse-block Truncated Signed Distance Field (TSDF) volume.
//
// Design notes (why it looks the way it does):
//  * **Sparse blocks, not a dense grid.** A 6×6×3 m room at 2 cm voxels would be a 2.7 M
//    voxel dense volume (~65 MB) of which >95 % is free space. 8³-voxel blocks are allocated
//    on demand along the sampled depth rays, so a full room scan costs ~10-30 MB.
//  * **Ray sampling instead of voxel projection.** For each (sub-sampled) depth pixel we walk
//    only the ±truncation band around the measured surface: ~9 voxel updates per ray instead
//    of projecting every voxel in the frustum. That is what makes real-time integration
//    possible on a phone CPU.
//  * **Sharded locking.** The block map is split into 64 shards (own mutex + map + deque).
//    Worker threads mostly touch different shards, and a block's address never moves, so
//    pointers stay valid while other threads insert.
//  * **Fixed 8³ blocks.** One block is exactly 512 voxels = 2 KB SDF + 1 KB weight, so the
//    memory cost of a scan is predictable and the mesher's neighbourhood gather is regular.
#pragma once

#include <atomic>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "../core/types.hpp"

namespace constructar {

/** Pinhole intrinsics for the *depth* image (already scaled from the colour image). */
struct CameraIntrinsics {
  float fx = 0.f;
  float fy = 0.f;
  float cx = 0.f;
  float cy = 0.f;

  bool isValid() const { return fx > 0.f && fy > 0.f; }
};

/**
 * One ARCore depth frame in the layout the depth image actually uses:
 * 16-bit millimetres, possibly with a row stride larger than the width.
 */
struct DepthFrameView {
  const uint16_t* depth = nullptr;
  int32_t width = 0;
  int32_t height = 0;
  /** Row stride in *uint16 elements* (`Image.Plane.getRowStride() / 2`). */
  int32_t rowStrideElements = 0;

  /** Optional ARCore raw-depth confidence image (0 = unusable … 255 = certain). */
  const uint8_t* confidence = nullptr;
  int32_t confidenceWidth = 0;
  int32_t confidenceHeight = 0;
  int32_t confidenceRowStride = 0;

  CameraIntrinsics intrinsics;
  /** Camera → world rigid transform for this frame. */
  Mat4 cameraToWorld;

  int64_t timestampNs = 0;

  /**
   * ARCore reports raw depth as the perpendicular distance along the optical axis
   * (z-depth). Kept as a flag because some depth sources hand out Euclidean range instead,
   * and because the Kotlin side can flip it if a device ever disagrees.
   */
  bool depthIsOpticalAxis = true;

  bool isValid() const {
    return depth != nullptr && width > 0 && height > 0 && rowStrideElements >= width &&
           intrinsics.isValid() && cameraToWorld.isFinite();
  }
};

/** Integration / memory tuning. Defaults are tuned for a 2 cm voxel mid-range phone. */
struct TsdfConfig {
  /** Voxel edge length in metres. 0.02 m resolves cables and door reveals. */
  float voxelSize = 0.02f;
  /** Truncation distance expressed in voxels (4 × 2 cm = 8 cm). */
  float truncationVoxels = 4.f;
  /** Depth values outside this range are ignored (metres). */
  float minDepth = 0.15f;
  float maxDepth = 6.0f;
  /** Raw-depth confidence below this is discarded (ARCore: 0 = worst, 255 = best). */
  uint8_t minConfidence = 96;
  /** Per-voxel weight cap; keeps a static surface from freezing out new observations. */
  float maxWeight = 64.f;
  /**
   * Also mark observed free space (camera → truncation band) as "outside". Removes ghosts
   * of furniture that has been moved, at a steep cost: measured on a 640×480 frame it takes
   * integration from ~21 ms to ~133 ms (desktop, 3 threads). Off by default; the UI exposes
   * it as an explicit "ghost removal (slower)" switch for a final clean-up pass.
   */
  bool carveFreeSpace = false;
  /** Coarse stride (in voxels) used while carving free space. */
  int freeSpaceStrideVoxels = 4;
  /** Upper bound on allocated blocks — a safety valve, not a working limit. */
  size_t maxBlocks = 160000;
  /** Integration worker threads; 0 ⇒ hardware_concurrency − 1 (at least 1). */
  int workerThreads = 0;
};

/** What one integration call did — surfaced to the UI as a scan-quality read-out. */
struct IntegrationStats {
  int raysProcessed = 0;
  int voxelUpdates = 0;
  int blocksAllocated = 0;
  int blocksTotal = 0;
  double milliseconds = 0.0;
  bool hitBlockBudget = false;
};

/** Extracted triangle mesh, ready to upload to Filament or write to PLY/GLB. */
struct MeshBuffers {
  std::vector<float> positions;   // xyz per vertex, metres, world space
  std::vector<float> normals;     // xyz per vertex, unit length
  std::vector<uint32_t> indices;  // triangles

  size_t vertexCount() const { return positions.size() / 3; }
  size_t triangleCount() const { return indices.size() / 3; }
  bool empty() const { return indices.empty(); }
};

class TsdfVolume {
 public:
  /** Block edge in voxels. Fixed: the mesher's gather pattern depends on it. */
  static constexpr int kResolution = 8;
  static constexpr int kVoxelsPerBlock = kResolution * kResolution * kResolution;
  static constexpr int kShardCount = 64;

  explicit TsdfVolume(const TsdfConfig& config);

  TsdfVolume(const TsdfVolume&) = delete;
  TsdfVolume& operator=(const TsdfVolume&) = delete;

  /** Integrates one depth frame. Serialised against extractMesh and other integrates. */
  IntegrationStats integrate(const DepthFrameView& frame, int pixelStep = 2);

  /**
   * Runs Surface Nets over the current volume. Called from the throttled extraction
   * coroutine (≤ 3.3 Hz), never per frame. Serialised against integrate().
   */
  MeshBuffers extractMesh(float isoLevel = 0.f) const;

  void reset();

  size_t blockCount() const { return blockCount_.load(std::memory_order_relaxed); }
  size_t voxelCount() const { return blockCount() * static_cast<size_t>(kVoxelsPerBlock); }
  const TsdfConfig& config() const { return config_; }

  int resolution() const { return kResolution; }
  int voxelsPerBlock() const { return kVoxelsPerBlock; }
  float truncationMeters() const { return config_.truncationVoxels * config_.voxelSize; }

  /** World-space centre of voxel (vx, vy, vz). */
  Vec3 voxelCenter(int32_t vx, int32_t vy, int32_t vz) const {
    const float vs = config_.voxelSize;
    return Vec3((static_cast<float>(vx) + 0.5f) * vs,
                (static_cast<float>(vy) + 0.5f) * vs,
                (static_cast<float>(vz) + 0.5f) * vs);
  }

  /** Copies the (res+1)³ voxel grid covering block (bx,by,bz); 0 where unobserved. */
  void gatherBlockGrid(int32_t bx, int32_t by, int32_t bz, float* out) const;

  /** Appends (bx,by,bz) triples for every allocated block. */
  void collectSurfaceBlocks(std::vector<int32_t>& outCoords) const;

  size_t triangleBudget() const { return triangleBudget_; }
  void setTriangleBudget(size_t triangles) { triangleBudget_ = triangles; }

  /** Serialises extraction against integration (exposed so the JNI layer can hold it). */
  std::mutex& integrationMutex() const { return integrationMutex_; }

 private:
  struct alignas(16) Block {
    float sdf[kVoxelsPerBlock];
    uint16_t weight[kVoxelsPerBlock];
    int32_t bx = 0, by = 0, bz = 0;
    uint32_t lastFrame = 0;
  };

  struct Shard {
    mutable std::mutex mutex;
    std::unordered_map<int64_t, uint32_t> index;  // blockKey -> slot in [blocks]
    std::deque<Block> blocks;                     // stable addresses
  };

  static int64_t blockKey(int32_t bx, int32_t by, int32_t bz);
  static int shardFor(int64_t key);

  Block* findOrCreateBlock(int32_t bx, int32_t by, int32_t bz);
  const Block* findBlock(int32_t bx, int32_t by, int32_t bz) const;

  void integrateRows(const DepthFrameView& frame, int pixelStep, int rowBegin, int rowEnd,
                     IntegrationStats& stats);

  TsdfConfig config_;
  size_t triangleBudget_ = 900000;

  std::vector<std::unique_ptr<Shard>> shards_;
  mutable std::mutex integrationMutex_;
  std::atomic<size_t> blockCount_{0};
  uint32_t frameCounter_ = 0;
  bool blockBudgetReported_ = false;
};

}  // namespace constructar
