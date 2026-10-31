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

TsdfVolume::Block* TsdfVolume::findOrCreateBlock(int32_t bx, int32_t by, int32_t bz) {
  const int64_t key = blockKey(bx, by, bz);
  Shard& shard = *shards_[shardFor(key)];
  std::lock_guard<std::mutex> lock(shard.mutex);
  auto it = shard.index.find(key);
  if (it != shard.index.end()) return &shard.blocks[it->second];
  if (blockCount_.load(std::memory_order_relaxed) >= config_.maxBlocks) {
    blockBudgetReported_ = true;
    return nullptr;
  }
  shard.blocks.emplace_back();
  Block& block = shard.blocks.back();
  std::memset(block.sdf, 0, sizeof(block.sdf));
  std::memset(block.weight, 0, sizeof(block.weight));
  block.bx = bx;
  block.by = by;
  block.bz = bz;
  block.lastFrame = frameCounter_;
  shard.index.emplace(key, static_cast<uint32_t>(shard.blocks.size() - 1));
  blockCount_.fetch_add(1, std::memory_order_relaxed);
  return &block;
}

const TsdfVolume::Block* TsdfVolume::findBlock(int32_t bx, int32_t by, int32_t bz) const {
  const int64_t key = blockKey(bx, by, bz);
  const Shard& shard = *shards_[shardFor(key)];
  std::lock_guard<std::mutex> lock(shard.mutex);
  auto it = shard.index.find(key);
  if (it == shard.index.end()) return nullptr;
  return &shard.blocks[it->second];
}

void TsdfVolume::integrateRows(const DepthFrameView& frame, int pixelStep, int rowBegin, int rowEnd,
                               IntegrationStats& stats) {
  const float vs = config_.voxelSize;
  const float trunc = truncationMeters();
  const float minDepth = config_.minDepth;
  const float maxDepth = config_.maxDepth;
  const float maxWeight = config_.maxWeight;
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

      // Coarse free-space pass: everything between the camera and the truncation band is
      // known to be empty. Two voxels of stride keeps this cheaper than the surface band.
      if (config_.carveFreeSpace) {
        const float stride = vs * static_cast<float>(std::max(1, config_.freeSpaceStrideVoxels));
        for (float zFree = minDepth; zFree < zStart; zFree += stride) {
          const Vec3 cam(px * zFree, py * zFree, -zFree);
          const Vec3 world = cameraToWorld.transformPoint(cam);
          const int32_t ix = static_cast<int32_t>(std::floor(world.x / vs));
          const int32_t iy = static_cast<int32_t>(std::floor(world.y / vs));
          const int32_t iz = static_cast<int32_t>(std::floor(world.z / vs));
          const int32_t bx = floorDiv(ix, kResolution);
          const int32_t by = floorDiv(iy, kResolution);
          const int32_t bz = floorDiv(iz, kResolution);
          Block* block = findOrCreateBlock(bx, by, bz);
          if (!block) continue;
          if (block->lastFrame != frameCounter_) {
            block->lastFrame = frameCounter_;
            ++allocated;
          }
          const int idx = ((iz - bz * kResolution) * kResolution + (iy - by * kResolution)) * kResolution +
                          (ix - bx * kResolution);
          const float oldWeight = static_cast<float>(block->weight[idx]);
          const float newWeight = std::min(oldWeight + 1.f, maxWeight);
          // Free space is fully outside: sdf = +1 in normalised units.
          block->sdf[idx] = oldWeight > 0.f ? (block->sdf[idx] * oldWeight + 1.f) / newWeight : 1.f;
          block->weight[idx] = static_cast<uint16_t>(std::min(newWeight, 65535.f));
          ++updates;
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

        const int32_t bx = floorDiv(ix, kResolution);
        const int32_t by = floorDiv(iy, kResolution);
        const int32_t bz = floorDiv(iz, kResolution);
        Block* block = findOrCreateBlock(bx, by, bz);
        if (!block) continue;
        if (block->lastFrame != frameCounter_) {
          block->lastFrame = frameCounter_;
          ++allocated;
        }

        const int lx = ix - bx * kResolution;
        const int ly = iy - by * kResolution;
        const int lz = iz - bz * kResolution;
        const int idx = (lz * kResolution + ly) * kResolution + lx;

        // Signed distance along the optical axis; positive in front of the surface.
        const float sdf = frame.depthIsOpticalAxis ? (d - z) : (d - cam.length());
        const float clamped = clampf(sdf / trunc, -1.f, 1.f);

        const float oldWeight = static_cast<float>(block->weight[idx]);
        const float newWeight = std::min(oldWeight + 1.f, maxWeight);
        block->sdf[idx] = oldWeight > 0.f ? (block->sdf[idx] * oldWeight + clamped) / newWeight : clamped;
        block->weight[idx] = static_cast<uint16_t>(std::min(newWeight, 65535.f));
        ++updates;
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
  stats.hitBlockBudget = blockBudgetReported_;
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
  blockBudgetReported_ = false;
}

}  // namespace constructar
