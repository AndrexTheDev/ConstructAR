/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

#include "volume.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <thread>

namespace constructar {
namespace {

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

/** Packs three signed 21-bit block coordinates into one key (±335 km at 16 cm blocks). */
inline int64_t packKey(int32_t bx, int32_t by, int32_t bz) {
  const uint64_t ux = static_cast<uint64_t>(static_cast<uint32_t>(bx) & 0x1FFFFFu);
  const uint64_t uy = static_cast<uint64_t>(static_cast<uint32_t>(by) & 0x1FFFFFu);
  const uint64_t uz = static_cast<uint64_t>(static_cast<uint32_t>(bz) & 0x1FFFFFu);
  return static_cast<int64_t>((ux << 42) | (uy << 21) | uz);
}

inline int32_t floorDiv(int32_t a, int32_t b) {
  const int32_t q = a / b;
  const int32_t r = a % b;
  return (r != 0 && ((r < 0) != (b < 0))) ? q - 1 : q;
}

inline int defaultThreadCount() {
  const unsigned hw = std::thread::hardware_concurrency();
  return hw <= 2 ? 1 : static_cast<int>(hw) - 1;
}

}  // namespace

TsdfVolume::TsdfVolume(const TsdfConfig& config) : config_(config) {
  config_.voxelSize = std::max(config_.voxelSize, 0.005f);
  config_.truncationVoxels = std::max(config_.truncationVoxels, 1.f);
  config_.maxDepth = std::max(config_.maxDepth, config_.minDepth + 0.1f);
  shards_.resize(kShardCount);
  for (auto& shard : shards_) shard = std::make_unique<Shard>();
}

int64_t TsdfVolume::blockKey(int32_t bx, int32_t by, int32_t bz) { return packKey(bx, by, bz); }

int TsdfVolume::shardFor(int64_t key) {
  // 64 shards; Knuth-style mixing so neighbouring blocks land in different shards.
  const uint64_t mixed = static_cast<uint64_t>(key) * 0x9E3779B97F4A7C15ull;
  return static_cast<int>((mixed >> 58) & (kShardCount - 1));
}

TsdfVolume::Touch TsdfVolume::findOrCreateBlockLocked(int32_t bx, int32_t by, int32_t bz, Shard& shard,
                                                      int64_t key) {
  auto it = shard.index.find(key);
  if (it != shard.index.end()) {
    Block* existing = &shard.blocks[it->second];
    // The touch belongs under this lock, next to the header it writes. It used to be the caller's
    // `if (block->lastFrame != frameCounter_) { …; ++allocated; }` - an unsynchronised read and
    // write of a field another thread may own at the same moment (finding 160).
    if (existing->lastFrame == frameCounter_) return Touch{existing, false};
    existing->lastFrame = frameCounter_;
    return Touch{existing, true};
  }
  // The slot is claimed **before** the memory exists, and the claim is atomic.
  //
  // This used to be a relaxed load, a comparison and a `fetch_add` after the block was built.
  // `integrate` splits a frame across worker threads and the shards have independent mutexes, so
  // two threads could both read "3 of 4" and both allocate: five blocks against a budget of four,
  // intermittently, depending on the core count of the machine. That budget is the guard that
  // keeps a long scan from filling the phone's memory, and it is also what the native test asserted
  // - which is why the suite failed on a CI runner with more cores than the machine it was written
  // on (finding 159).
  size_t claimed = blockCount_.load(std::memory_order_relaxed);
  for (;;) {
    if (claimed >= config_.maxBlocks) {
      blockBudgetReported_.store(true, std::memory_order_relaxed);
      return Touch{nullptr, false};
    }
    if (blockCount_.compare_exchange_weak(claimed, claimed + 1, std::memory_order_relaxed,
                                          std::memory_order_relaxed)) {
      break;
    }
  }

  // The memory, after the claim. There is nothing to unwind to if this fails: the app's native
  // build compiles with `-fno-exceptions`, so a refused allocation ends the process rather than
  // leaving a slot to give back.
  shard.blocks.emplace_back();
  Block& block = shard.blocks.back();
  std::memset(block.sdf, 0, sizeof(block.sdf));
  std::memset(block.weight, 0, sizeof(block.weight));
  block.bx = bx;
  block.by = by;
  block.bz = bz;
  block.lastFrame = frameCounter_;
  shard.index.emplace(key, static_cast<uint32_t>(shard.blocks.size() - 1));
  // A block that did not exist a moment ago is this frame's allocation, and it used to be counted
  // as *nothing*: `lastFrame` was pre-set here, so the caller's "is this block new to me?" test
  // always said no. One frame into an empty volume reported 572 blocks in the volume and 0
  // allocated (finding 160).
  return Touch{&block, true};
}

size_t TsdfVolume::totalVoxelWeight() const {
  size_t total = 0;
  for (const auto& shard : shards_) {
    std::lock_guard<std::mutex> lock(shard->mutex);
    for (const auto& block : shard->blocks) {
      for (int i = 0; i < kVoxelsPerBlock; ++i) total += block.weight[i];
    }
  }
  return total;
}

const TsdfVolume::Block* TsdfVolume::findBlock(int32_t bx, int32_t by, int32_t bz) const {
  const int64_t key = blockKey(bx, by, bz);
  const Shard& shard = *shards_[shardFor(key)];
  std::lock_guard<std::mutex> lock(shard.mutex);
  auto it = shard.index.find(key);
  if (it == shard.index.end()) return nullptr;
  return &shard.blocks[it->second];
}

TsdfVolume::Fusion TsdfVolume::fuseVoxel(int32_t ix, int32_t iy, int32_t iz, float normalizedSdf,
                                         bool existingOnly) {
  const int32_t bx = floorDiv(ix, kResolution);
  const int32_t by = floorDiv(iy, kResolution);
  const int32_t bz = floorDiv(iz, kResolution);
  const int64_t key = blockKey(bx, by, bz);
  Shard& shard = *shards_[shardFor(key)];

  Fusion result;
  // One lock for the lookup, the allocation and the fusion. The observation is a read-modify-write
  // of `sdf` and `weight`; doing it after the lock was released is the race this exists to close
  // (finding 181).
  std::lock_guard<std::mutex> lock(shard.mutex);
  Block* block = nullptr;
  if (existingOnly) {
    auto it = shard.index.find(key);
    if (it == shard.index.end()) return result;
    block = &shard.blocks[it->second];
  } else {
    const Touch touch = findOrCreateBlockLocked(bx, by, bz, shard, key);
    block = touch.block;
    result.allocated = touch.firstInFrame;
    if (block == nullptr) return result;
  }

  const int idx = ((iz - bz * kResolution) * kResolution + (iy - by * kResolution)) * kResolution +
                  (ix - bx * kResolution);
  const float oldWeight = static_cast<float>(block->weight[idx]);
  // A ghost only lives where something was observed once.
  if (existingOnly && oldWeight <= 0.f) return result;
  const float newWeight = std::min(oldWeight + 1.f, config_.maxWeight);
  block->sdf[idx] = oldWeight > 0.f ? (block->sdf[idx] * oldWeight + normalizedSdf) / newWeight : normalizedSdf;
  block->weight[idx] = static_cast<uint16_t>(std::min(newWeight, 65535.f));
  result.written = true;
  return result;
}

void TsdfVolume::integrateRows(const DepthFrameView& frame, int pixelStep, int rowBegin, int rowEnd,
                               IntegrationStats& stats) {
  const float vs = config_.voxelSize;
  const float trunc = truncationMeters();
  const float minDepth = config_.minDepth;
  const float maxDepth = config_.maxDepth;
  const float invFx = 1.f / frame.intrinsics.fx;
  const float invFy = 1.f / frame.intrinsics.fy;
  const Mat4& cameraToWorld = frame.cameraToWorld;
  const float confScaleX = frame.confidence
                               ? static_cast<float>(frame.confidenceWidth) / static_cast<float>(frame.width)
                               : 0.f;
  const float confScaleY = frame.confidence
                               ? static_cast<float>(frame.confidenceHeight) / static_cast<float>(frame.height)
                               : 0.f;

  int rays = 0;
  int updates = 0;
  int allocated = 0;

  for (int v = rowBegin; v < rowEnd; v += pixelStep) {
    const uint16_t* row = frame.depth + static_cast<int64_t>(v) * frame.rowStrideElements;
    const uint8_t* confRow = nullptr;
    if (frame.confidence) {
      const int confY = std::min(frame.confidenceHeight - 1, static_cast<int>(static_cast<float>(v) * confScaleY));
      confRow = frame.confidence + static_cast<int64_t>(confY) * frame.confidenceRowStride;
    }
    for (int u = 0; u < frame.width; u += pixelStep) {
      const uint16_t rawDepth = row[u];
      if (rawDepth == 0) continue;
      const float d = static_cast<float>(rawDepth) * 0.001f;
      if (d < minDepth || d > maxDepth) continue;
      if (confRow) {
        const int confX = std::min(frame.confidenceWidth - 1, static_cast<int>(static_cast<float>(u) * confScaleX));
        if (confRow[confX] < config_.minConfidence) continue;
      }
      ++rays;

      // Camera-space ray through the pixel centre. ARCore's camera looks down −Z and raw
      // depth is the perpendicular distance along that axis, so the point at depth z is
      // (vx·z, vy·z, −z) with vx/vy the normalised pinhole offsets.
      const float px = (static_cast<float>(u) + 0.5f - frame.intrinsics.cx) * invFx;
      const float py = (static_cast<float>(v) + 0.5f - frame.intrinsics.cy) * invFy;

      const float zStart = std::max(minDepth, d - trunc);
      const float zEnd = d + trunc;

      // Free-space pass: everything between the camera and the truncation band is known to be
      // empty. Sampled one voxel at a time - a coarser stride writes a *lattice* of free voxels
      // and leaves the voxels between two samples with their old (inside) value, which turns a
      // ghost into a comb of extra iso-surfaces instead of removing it (finding 111: with a
      // four-voxel stride a 1 m plane seen once and then cleared produced 9 218 vertices within
      // 5 cm of it, three times the 2 961 of not carving at all).
      if (config_.carveFreeSpace) {
        for (float zFree = minDepth; zFree < zStart; zFree += vs) {
          const Vec3 cam(px * zFree, py * zFree, -zFree);
          const Vec3 world = cameraToWorld.transformPoint(cam);
          const int32_t ix = static_cast<int32_t>(std::floor(world.x / vs));
          const int32_t iy = static_cast<int32_t>(std::floor(world.y / vs));
          const int32_t iz = static_cast<int32_t>(std::floor(world.z / vs));
          // Only *existing* blocks can hold a ghost, and only a voxel that was ever observed
          // (weight > 0) can be a surface. Allocating a block here instead - the earlier
          // behaviour - spent 40 % of the voxels in the volume on empty air: 444 blocks for a
          // single wall became 1 196 and every later gather walked them (finding 111).
          // Free space is fully outside: sdf = +1 in normalised units.
          if (fuseVoxel(ix, iy, iz, 1.f, /*existingOnly=*/true).written) ++updates;
        }
      }
      const int steps = std::max(2, static_cast<int>(std::ceil((zEnd - zStart) / vs)));
      const float zStep = (zEnd - zStart) / static_cast<float>(steps);

      for (int s = 0; s <= steps; ++s) {
        const float z = zStart + zStep * static_cast<float>(s);
        if (z <= 0.f) continue;
        const Vec3 cam(px * z, py * z, -z);
        const Vec3 world = cameraToWorld.transformPoint(cam);

        const int32_t ix = static_cast<int32_t>(std::floor(world.x / vs));
        const int32_t iy = static_cast<int32_t>(std::floor(world.y / vs));
        const int32_t iz = static_cast<int32_t>(std::floor(world.z / vs));

        // Signed distance along the optical axis; positive in front of the surface.
        //
        // `DepthFrameView` used to carry a `depthIsOpticalAxis` flag with a second branch here
        // (`d - cam.length()`) for sources that hand out Euclidean range. Nothing ever set it -
        // not the JNI, not the Kotlin pipeline - and it could not have worked if something had:
        // the ray point above is built at *optical-axis* depth, so at the frame corner a 2 m
        // range reading was compared against a 2.24 m point. Measured on a synthetic 30°-tilted
        // plane, forcing it put the surface up to 8.8 cm off the true plane and threw away 87 %
        // of it (finding 108). One convention, documented and tested: ARCore depth is z-depth.
        const float sdf = d - z;
        const float clamped = clampf(sdf / trunc, -1.f, 1.f);

        // Lookup, allocation and fusion in one locked step (finding 181).
        const Fusion fused = fuseVoxel(ix, iy, iz, clamped);
        if (fused.allocated) ++allocated;
        if (fused.written) ++updates;
      }
    }
  }

  stats.raysProcessed += rays;
  stats.voxelUpdates += updates;
  stats.blocksAllocated += allocated;
}

IntegrationStats TsdfVolume::integrate(const DepthFrameView& frame, int pixelStep) {
  IntegrationStats stats;
  if (!frame.isValid()) return stats;

  const auto started = std::chrono::steady_clock::now();
  pixelStep = std::max(1, pixelStep);

  std::lock_guard<std::mutex> lock(integrationMutex_);
  ++frameCounter_;

  const int threads = std::max(1, config_.workerThreads > 0 ? config_.workerThreads : defaultThreadCount());

  if (threads == 1) {
    integrateRows(frame, pixelStep, 0, frame.height, stats);
  } else {
    std::vector<std::thread> workers;
    workers.reserve(static_cast<size_t>(threads));
    std::vector<IntegrationStats> partial(static_cast<size_t>(threads));
    const int rowsPerThread = (frame.height + threads - 1) / threads;
    for (int t = 0; t < threads; ++t) {
      const int begin = t * rowsPerThread;
      const int end = std::min(frame.height, begin + rowsPerThread);
      if (begin >= end) continue;
      workers.emplace_back([this, &frame, pixelStep, begin, end, &partial, t]() {
        integrateRows(frame, pixelStep, begin, end, partial[static_cast<size_t>(t)]);
      });
    }
    for (auto& worker : workers) worker.join();
    for (const auto& p : partial) {
      stats.raysProcessed += p.raysProcessed;
      stats.voxelUpdates += p.voxelUpdates;
      stats.blocksAllocated += p.blocksAllocated;
    }
  }

  stats.blocksTotal = static_cast<int>(blockCount());
  stats.hitBlockBudget = blockBudgetReported_.load(std::memory_order_relaxed);
  const auto finished = std::chrono::steady_clock::now();
  stats.milliseconds =
      std::chrono::duration_cast<std::chrono::microseconds>(finished - started).count() / 1000.0;
  return stats;
}

void TsdfVolume::gatherBlockGrid(int32_t bx, int32_t by, int32_t bz, float* out) const {
  const int size = kResolution + 1;
  const int gridVoxels = size * size * size;
  // NaN marks "never observed". The mesher must distinguish that from a voxel that is
  // genuinely on the surface (sdf == 0), otherwise it invents geometry at the edge of the
  // scanned volume and a perfectly flat wall can end up with a zero-crossing hole.
  const float unknown = std::numeric_limits<float>::quiet_NaN();
  for (int i = 0; i < gridVoxels; ++i) out[i] = unknown;

  // The (res+1)³ grid straddles at most 8 blocks: a grid coordinate equal to `res` on an
  // axis crosses into the neighbouring block on that axis. Resolve those 8 blocks once
  // instead of hashing per voxel.
  const Block* blocks[8];
  for (int i = 0; i < 8; ++i) {
    const int crossX = (i >> 0) & 1;
    const int crossY = (i >> 1) & 1;
    const int crossZ = (i >> 2) & 1;
    blocks[i] = findBlock(bx + crossX, by + crossY, bz + crossZ);
  }

  for (int z = 0; z < size; ++z) {
    const int crossZ = (z == kResolution) ? 1 : 0;
    for (int y = 0; y < size; ++y) {
      const int crossY = (y == kResolution) ? 1 : 0;
      for (int x = 0; x < size; ++x) {
        const int crossX = (x == kResolution) ? 1 : 0;
        const Block* block = blocks[crossX | (crossY << 1) | (crossZ << 2)];
        if (!block) continue;
        const int lx = x - crossX * kResolution;
        const int ly = y - crossY * kResolution;
        const int lz = z - crossZ * kResolution;
        const int idx = (lz * kResolution + ly) * kResolution + lx;
        if (block->weight[idx] > 0) out[(z * size + y) * size + x] = block->sdf[idx];
      }
    }
  }
}

void TsdfVolume::collectSurfaceBlocks(std::vector<int32_t>& outCoords) const {
  outCoords.clear();
  outCoords.reserve(blockCount() * 3);
  for (const auto& shard : shards_) {
    std::lock_guard<std::mutex> lock(shard->mutex);
    for (const auto& [key, slot] : shard->index) {
      (void)key;
      const Block& block = shard->blocks[slot];
      outCoords.push_back(block.bx);
      outCoords.push_back(block.by);
      outCoords.push_back(block.bz);
    }
  }
}

void TsdfVolume::reset() {
  std::lock_guard<std::mutex> lock(integrationMutex_);
  for (auto& shard : shards_) {
    std::lock_guard<std::mutex> shardLock(shard->mutex);
    shard->index.clear();
    shard->blocks.clear();
  }
  blockCount_.store(0, std::memory_order_relaxed);
  frameCounter_ = 0;
  blockBudgetReported_.store(false, std::memory_order_relaxed);
}

}  // namespace constructar
