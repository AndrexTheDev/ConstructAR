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

/** Exact 21-bits-per-axis voxel key, for neighbour lookups (|index| < 2^20). */
std::int64_t voxelKey(int32_t x, int32_t y, int32_t z) {
  return (static_cast<std::int64_t>(x & 0x1FFFFF) << 42) |
         (static_cast<std::int64_t>(y & 0x1FFFFF) << 21) |
         static_cast<std::int64_t>(z & 0x1FFFFF);
}

/** Axis 0 = x, 1 = y, 2 = z (the shift voxelKey put it at). */
int32_t unpackAxis(std::int64_t key, int axis) {
  const std::int64_t raw = (key >> (21 * axis)) & 0x1FFFFF;
  return raw >= (static_cast<std::int64_t>(1) << 20) ? static_cast<int32_t>(raw - (static_cast<std::int64_t>(1) << 21))
                                                     : static_cast<int32_t>(raw);
}

}  // namespace
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
  config_.minIncidenceWeight = std::isfinite(config_.minIncidenceWeight)
                                   ? clampf(config_.minIncidenceWeight, 0.f, 1.f)
                                   : 0.2f;
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
  return static_cast<size_t>(totalVoxelWeightExact() + 0.5);
}

double TsdfVolume::totalVoxelWeightExact() const {
  // The stored value is 16-bit fixed point in units of 1/256 of an observation, so the sum is
  // accumulated in double and divided once - summing 1/256ths as integers would overflow a 32-bit
  // counter on a large volume, and rounding each voxel first would lose every distant observation.
  double total = 0.0;
  for (const auto& shard : shards_) {
    std::lock_guard<std::mutex> lock(shard->mutex);
    for (const auto& block : shard->blocks) {
      for (int i = 0; i < kVoxelsPerBlock; ++i) total += static_cast<double>(block.weight[i]);
    }
  }
  return total / static_cast<double>(kWeightScale);
}

float TsdfVolume::observationWeight(float depthMetres) {
  if (!std::isfinite(depthMetres) || !(depthMetres > 0.f)) return 0.f;
  const float weight = 1.f / (depthMetres * depthMetres);
  return std::min(std::max(weight, 1.f / 36.f), 4.f);
}

float TsdfVolume::confidenceWeight(uint8_t confidence) {
  return static_cast<float>(confidence) / 255.f;
}

float TsdfVolume::incidenceWeight(float cosine, float floor) {
  // Unknown geometry must not turn into either certain or rejected evidence.
  if (!std::isfinite(cosine)) return 1.f;
  const float safeFloor = std::isfinite(floor) ? clampf(floor, 0.f, 1.f) : 0.2f;
  return std::max(clampf(std::fabs(cosine), 0.f, 1.f), safeFloor);
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
                                         float weight, bool existingOnly) {
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
  const float oldWeight = static_cast<float>(block->weight[idx]) / kWeightScale;
  // A ghost only lives where something was observed once.
  if (existingOnly && oldWeight <= 0.f) return result;
  if (!(weight > 0.f)) return result;
  // `maxWeight` caps how much evidence one voxel may hold; the 16-bit array caps it again at
  // 65535/256 = 256 observations. Both refusals are subtracted from what is reported, so the
  // volume's total weight and the sum of `IntegrationStats::voxelWeight` stay the same number.
  const float rawWeight = std::min(oldWeight + weight, config_.maxWeight) * kWeightScale;
  const float storedWeight = std::floor(std::min(rawWeight, 65535.f)) / kWeightScale;
  const float applied = storedWeight - oldWeight;
  if (!(applied > 0.f)) return result;  // finer than the array can resolve: nothing was stored
  block->sdf[idx] = oldWeight > 0.f
                        ? (block->sdf[idx] * oldWeight + normalizedSdf * applied) / storedWeight
                        : normalizedSdf;
  block->weight[idx] = static_cast<uint16_t>(storedWeight * kWeightScale);
  result.written = true;
  result.weightApplied = applied;
  return result;
}

float TsdfVolume::incidenceCosine(const DepthFrameView& frame, int u, int v, float centreDepth) const {
  // Four immediate neighbours are enough for a local tangent basis and bound the extra work per
  // accepted ray. Opposite pairs average sensor noise; a one-sided pair keeps plain image borders
  // usable. A depth discontinuity is not a tangent, so those neighbours are rejected rather than
  // manufacturing a steep normal at a silhouette.
  Vec3 neighbour[4];  // left, right, up, down
  bool valid[4] = {false, false, false, false};
  const int x[4] = {u - 1, u + 1, u, u};
  const int y[4] = {v, v, v - 1, v + 1};
  const float maxDepthJump = std::max(2.f * truncationMeters(), 0.05f * centreDepth);
  const float confScaleX = frame.confidence
                               ? static_cast<float>(frame.confidenceWidth) / static_cast<float>(frame.width)
                               : 0.f;
  const float confScaleY = frame.confidence
                               ? static_cast<float>(frame.confidenceHeight) / static_cast<float>(frame.height)
                               : 0.f;

  for (int i = 0; i < 4; ++i) {
    if (x[i] < 0 || x[i] >= frame.width || y[i] < 0 || y[i] >= frame.height) continue;
    const uint16_t raw = frame.depth[static_cast<int64_t>(y[i]) * frame.rowStrideElements + x[i]];
    if (raw == 0) continue;
    const float depth = static_cast<float>(raw) * 0.001f;
    if (depth < config_.minDepth || depth > config_.maxDepth ||
        std::fabs(depth - centreDepth) > maxDepthJump) {
      continue;
    }
    if (frame.confidence) {
      const int confX = std::min(frame.confidenceWidth - 1,
                                 static_cast<int>(static_cast<float>(x[i]) * confScaleX));
      const int confY = std::min(frame.confidenceHeight - 1,
                                 static_cast<int>(static_cast<float>(y[i]) * confScaleY));
      const uint8_t confidence =
          frame.confidence[static_cast<int64_t>(confY) * frame.confidenceRowStride + confX];
      if (confidence < config_.minConfidence) continue;
    }
    const float px = (static_cast<float>(x[i]) + 0.5f - frame.intrinsics.cx) / frame.intrinsics.fx;
    const float py = (static_cast<float>(y[i]) + 0.5f - frame.intrinsics.cy) / frame.intrinsics.fy;
    neighbour[i] = Vec3(px * depth, py * depth, -depth);
    valid[i] = true;
  }

  const float centrePx = (static_cast<float>(u) + 0.5f - frame.intrinsics.cx) / frame.intrinsics.fx;
  const float centrePy = (static_cast<float>(v) + 0.5f - frame.intrinsics.cy) / frame.intrinsics.fy;
  const Vec3 centre(centrePx * centreDepth, centrePy * centreDepth, -centreDepth);

  Vec3 tangentX;
  if (valid[0] && valid[1]) {
    tangentX = (neighbour[1] - neighbour[0]) * 0.5f;
  } else if (valid[0]) {
    tangentX = centre - neighbour[0];
  } else if (valid[1]) {
    tangentX = neighbour[1] - centre;
  } else {
    return 1.f;
  }

  Vec3 tangentY;
  if (valid[2] && valid[3]) {
    tangentY = (neighbour[3] - neighbour[2]) * 0.5f;
  } else if (valid[2]) {
    tangentY = centre - neighbour[2];
  } else if (valid[3]) {
    tangentY = neighbour[3] - centre;
  } else {
    return 1.f;
  }

  const Vec3 normal = tangentX.cross(tangentY);
  const float normalLength = normal.length();
  const float rayLength = centre.length();
  if (!normal.isFinite() || !(normalLength > 1e-12f) || !(rayLength > 1e-8f)) return 1.f;
  const float cosine = std::fabs(normal.dot(centre)) / (normalLength * rayLength);
  return std::isfinite(cosine) ? clampf(cosine, 0.f, 1.f) : 1.f;
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
  double weightApplied = 0.0;

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
      float confidenceScale = 1.f;
      if (confRow) {
        const int confX = std::min(frame.confidenceWidth - 1, static_cast<int>(static_cast<float>(u) * confScaleX));
        const uint8_t confidence = confRow[confX];
        if (confidence < config_.minConfidence) continue;
        confidenceScale = confidenceWeight(confidence);
      }
      const float incidenceScale = config_.weightByIncidence
                                       ? incidenceWeight(incidenceCosine(frame, u, v, d),
                                                         config_.minIncidenceWeight)
                                       : 1.f;
      const float weight = observationWeight(d) * confidenceScale * incidenceScale;
      if (!(weight > 0.f)) continue;
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
          if (fuseVoxel(ix, iy, iz, 1.f, weight, /*existingOnly=*/true).written) ++updates;
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

        // Lookup, allocation and fusion in one locked step (finding 181). Range, source confidence
        // and local incidence scale the observation: a pixel at 4 m is worth a sixteenth of one at
        // 1 m, an AI fill at the 96/255 floor carries less evidence than a certain ARCore hit, and
        // a locally measured graze is discounted without becoming a hard rejection. Missing
        // confidence or an unknowable normal retains its neutral unit multiplier.
        const Fusion fused = fuseVoxel(ix, iy, iz, clamped, weight);
        if (fused.allocated) ++allocated;
        if (fused.written) {
          ++updates;
          weightApplied += fused.weightApplied;
        }
      }
    }
  }

  stats.raysProcessed += rays;
  stats.voxelUpdates += updates;
  stats.blocksAllocated += allocated;
  stats.voxelWeight += weightApplied;
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
      stats.voxelWeight += p.voxelWeight;
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

std::vector<Vec3> TsdfVolume::sliceSurfaceAtHeight(float heightMetres, float bandMetres) const {
  std::vector<Vec3> slice;
  if (!std::isfinite(heightMetres) || !(bandMetres >= 0.f)) return slice;

  std::lock_guard<std::mutex> lock(integrationMutex_);
  const float voxelSize = config_.voxelSize;
  for (const auto& shard : shards_) {
    std::lock_guard<std::mutex> shardLock(shard->mutex);
    for (const auto& [key, slot] : shard->index) {
      (void)key;
      const Block& block = shard->blocks[slot];
      for (int local = 0; local < kVoxelsPerBlock; ++local) {
        if (block.weight[local] == 0) continue;              // never observed
        if (std::fabs(block.sdf[local]) > 0.5f) continue;     // not a surface voxel
        const int ix = local & (kResolution - 1);
        const int iy = (local >> 3) & (kResolution - 1);
        const int iz = (local >> 6) & (kResolution - 1);
        const float y = (static_cast<float>(block.by * kResolution + iy) + 0.5f) * voxelSize;
        if (std::fabs(y - heightMetres) > bandMetres) continue;
        slice.push_back(Vec3((static_cast<float>(block.bx * kResolution + ix) + 0.5f) * voxelSize,
                             y,
                             (static_cast<float>(block.bz * kResolution + iz) + 0.5f) * voxelSize));
      }
    }
  }
  return slice;
}

std::vector<OrientedPoint> TsdfVolume::sliceSurfacesAtHeight(float heightMetres,
                                                           float bandMetres) const {
  std::vector<OrientedPoint> slice;
  if (!std::isfinite(heightMetres) || !(bandMetres >= 0.f)) return slice;

  std::lock_guard<std::mutex> lock(integrationMutex_);
  const float vs = config_.voxelSize;
  // One voxel of margin either side of the band, because the gradient needs a neighbour to look at.
  const int32_t yLow = static_cast<int32_t>(std::floor((heightMetres - bandMetres) / vs)) - 1;
  const int32_t yHigh = static_cast<int32_t>(std::floor((heightMetres + bandMetres) / vs)) + 1;

  // Voxel key -> SDF, for every observed voxel in the band plus one voxel either side. Exact
  // packing (21 bits per axis, ±1 048 575 voxels) so neighbours can be looked up without a hash
  // collision deciding a normal.
  std::unordered_map<std::int64_t, float> observed;
  std::vector<std::int64_t> keys;
  for (const auto& shard : shards_) {
    std::lock_guard<std::mutex> shardLock(shard->mutex);
    for (const auto& block : shard->blocks) {
      for (int local = 0; local < kVoxelsPerBlock; ++local) {
        if (block.weight[local] == 0) continue;  // never observed
        const int32_t gx = block.bx * kResolution + (local & (kResolution - 1));
        const int32_t gy = block.by * kResolution + ((local >> 3) & (kResolution - 1));
        const int32_t gz = block.bz * kResolution + ((local >> 6) & (kResolution - 1));
        if (gy < yLow || gy > yHigh) continue;
        const std::int64_t key = voxelKey(gx, gy, gz);
        if (observed.emplace(key, block.sdf[local]).second) keys.push_back(key);
      }
    }
  }
  std::sort(keys.begin(), keys.end());

  const auto sdfAt = [&observed](std::int64_t key, float& out) {
    const auto it = observed.find(key);
    if (it == observed.end()) return false;
    out = it->second;
    return true;
  };
  const auto gradientAlong = [&sdfAt](std::int64_t centre, int axis, float& out) {
    const std::int64_t stride = static_cast<std::int64_t>(1) << (21 * (2 - axis));
    float centreValue = 0.f, plus = 0.f, minus = 0.f;
    if (!sdfAt(centre, centreValue)) return false;
    const bool hasPlus = sdfAt(centre + stride, plus);
    const bool hasMinus = sdfAt(centre - stride, minus);
    if (hasPlus && hasMinus) {
      out = (plus - minus) * 0.5f;
      return true;
    }
    if (hasPlus) {
      out = plus - centreValue;
      return true;
    }
    if (hasMinus) {
      out = centreValue - minus;
      return true;
    }
    return false;
  };

  for (const std::int64_t key : keys) {
    const float value = observed[key];
    if (std::fabs(value) > 0.5f) continue;  // not a surface voxel
    const int32_t gx = unpackAxis(key, 2);
    const int32_t gy = unpackAxis(key, 1);
    const int32_t gz = unpackAxis(key, 0);
    const float y = (static_cast<float>(gy) + 0.5f) * vs;
    if (std::fabs(y - heightMetres) > bandMetres) continue;

    Vec3 gradient;
    bool any = false;
    for (int axis = 0; axis < 3; ++axis) {
      float component = 0.f;
      if (gradientAlong(key, axis, component)) {
        gradient[axis] = component;
        any = true;
      }
    }
    // A voxel whose six neighbours were all unobserved has no measurable direction. It is emitted
    // with a zero normal rather than a made-up one, and the mask counts those separately.
    const float length = gradient.length();
    const Vec3 normal = (any && length > 1e-6f) ? gradient / length : Vec3(0.f, 0.f, 0.f);
    slice.push_back(OrientedPoint{voxelCenter(gx, gy, gz), normal});
  }
  return slice;
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
