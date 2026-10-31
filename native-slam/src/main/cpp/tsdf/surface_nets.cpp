// Surface Nets meshing of the sparse TSDF.
//
// Why Surface Nets and not Marching Cubes:
//  * one vertex per sign-changing cell instead of up to 5 → ~40 % fewer triangles for the
//    same surface, which matters when the mesh is streamed into a phone GPU;
//  * the vertex sits at the average of the cell's edge crossings, which is naturally
//    smoothed — walls come out as walls instead of staircases;
//  * the quad-per-edge formulation is branch-light and trivially correct (no 256-case table
//    to get wrong).
//
// Two passes over the block list:
//   1. vertex placement (one vertex per sign-changing cell, keyed by global cell coords);
//   2. quad emission — each sign-changing voxel edge emits the quad joining the four cells
//      that share that edge (the dual of the voxel grid).
// Both passes gather a (res+1)³ neighbourhood per block, so the seams between blocks are
// closed without any special casing.
#include "volume.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <unordered_map>
#include <vector>

namespace constructar {
namespace {

inline int64_t packCell(int32_t cx, int32_t cy, int32_t cz) {
  const uint64_t ux = static_cast<uint64_t>(static_cast<uint32_t>(cx) & 0x1FFFFFu);
  const uint64_t uy = static_cast<uint64_t>(static_cast<uint32_t>(cy) & 0x1FFFFFu);
  const uint64_t uz = static_cast<uint64_t>(static_cast<uint32_t>(cz) & 0x1FFFFFu);
  return static_cast<int64_t>((ux << 42) | (uy << 21) | uz);
}

/** The 12 cube edges as pairs of corner indices (corner index = x + 2y + 4z). */
constexpr int kEdgeCorners[12][2] = {
    {0, 1}, {2, 3}, {4, 5}, {6, 7},  // along x
    {0, 2}, {1, 3}, {4, 6}, {5, 7},  // along y
    {0, 4}, {1, 5}, {2, 6}, {3, 7},  // along z
};

inline Vec3 cornerOffset(int corner) {
  return Vec3(static_cast<float>(corner & 1), static_cast<float>((corner >> 1) & 1),
              static_cast<float>((corner >> 2) & 1));
}

}  // namespace

MeshBuffers TsdfVolume::extractMesh(float isoLevel) const {
  MeshBuffers mesh;
  std::lock_guard<std::mutex> lock(integrationMutex_);

  std::vector<int32_t> blocks;
  collectSurfaceBlocks(blocks);
  if (blocks.empty()) return mesh;

  const int res = kResolution;
  const int size = res + 1;
  const int gridVoxels = size * size * size;
  const float vs = config_.voxelSize;
  std::vector<float> grid(static_cast<size_t>(gridVoxels));

  std::unordered_map<int64_t, uint32_t> cellVertices;
  cellVertices.reserve(static_cast<size_t>(blocks.size() / 3) * 512 + 1024);

  mesh.positions.reserve(static_cast<size_t>(blocks.size() / 3) * 256);

  // ── Pass 1: one vertex per sign-changing cell ─────────────────────────────────────────
  for (size_t b = 0; b + 2 < blocks.size(); b += 3) {
    const int32_t bx = blocks[b];
    const int32_t by = blocks[b + 1];
    const int32_t bz = blocks[b + 2];
    gatherBlockGrid(bx, by, bz, grid.data());

    for (int lz = 0; lz < res; ++lz) {
      for (int ly = 0; ly < res; ++ly) {
        for (int lx = 0; lx < res; ++lx) {
          float corner[8];
          bool hasBelow = false;
          bool hasAbove = false;
          bool complete = true;
          for (int c = 0; c < 8; ++c) {
            const int ox = c & 1;
            const int oy = (c >> 1) & 1;
            const int oz = (c >> 2) & 1;
            const float value = grid[((lz + oz) * size + (ly + oy)) * size + (lx + ox)];
            corner[c] = value;
            if (!std::isfinite(value)) {
              complete = false;
              break;
            }
            if (value < isoLevel) {
              hasBelow = true;
            } else {
              hasAbove = true;
            }
          }
          // A cell touching unobserved space carries no reliable surface: skip it rather
          // than emit a phantom wall at the edge of the scan.
          if (!complete || !(hasBelow && hasAbove)) continue;

          // Average of the edge/surface crossings — the smoothed vertex of Surface Nets.
          Vec3 sum(0.f, 0.f, 0.f);
          int crossings = 0;
          for (const auto& edge : kEdgeCorners) {
            const float a = corner[edge[0]];
            const float bValue = corner[edge[1]];
            if ((a < isoLevel) == (bValue < isoLevel)) continue;
            const float denom = bValue - a;
            const float t = std::fabs(denom) > 1e-9f ? (isoLevel - a) / denom : 0.5f;
            const Vec3 p = cornerOffset(edge[0]) + (cornerOffset(edge[1]) - cornerOffset(edge[0])) * t;
            sum += p;
            ++crossings;
          }
          if (crossings == 0) continue;
          const Vec3 local = sum / static_cast<float>(crossings);

          const int32_t cx = bx * res + lx;
          const int32_t cy = by * res + ly;
          const int32_t cz = bz * res + lz;
          const int64_t key = packCell(cx, cy, cz);
          if (cellVertices.find(key) != cellVertices.end()) continue;

          // Voxel grid coordinate g maps to world ((g + 0.5) * voxelSize) — the voxel centre.
          const Vec3 world((static_cast<float>(cx) + local.x + 0.5f) * vs,
                           (static_cast<float>(cy) + local.y + 0.5f) * vs,
                           (static_cast<float>(cz) + local.z + 0.5f) * vs);

          // Normal = ∇sdf, evaluated from the two opposing x/y/z corner planes.
          const float dx = (corner[1] + corner[3] + corner[5] + corner[7]) -
                           (corner[0] + corner[2] + corner[4] + corner[6]);
          const float dy = (corner[2] + corner[3] + corner[6] + corner[7]) -
                           (corner[0] + corner[1] + corner[4] + corner[5]);
          const float dz = (corner[4] + corner[5] + corner[6] + corner[7]) -
                           (corner[0] + corner[1] + corner[2] + corner[3]);
          Vec3 normal(dx, dy, dz);
          if (normal.lengthSquared() < 1e-12f) normal = Vec3(0.f, 1.f, 0.f);
          normal = normal.normalized();

          const uint32_t index = static_cast<uint32_t>(mesh.positions.size() / 3);
          cellVertices.emplace(key, index);
          mesh.positions.push_back(world.x);
          mesh.positions.push_back(world.y);
          mesh.positions.push_back(world.z);
          mesh.normals.push_back(normal.x);
          mesh.normals.push_back(normal.y);
          mesh.normals.push_back(normal.z);
        }
      }
    }
  }

  // ── Pass 2: dual quads, one per sign-changing voxel edge ──────────────────────────────
  const size_t maxTriangles = triangleBudget();
  bool budgetHit = false;

  auto vertexIndex = [&cellVertices](int32_t cx, int32_t cy, int32_t cz, uint32_t& out) {
    const auto it = cellVertices.find(packCell(cx, cy, cz));
    if (it == cellVertices.end()) return false;
    out = it->second;
    return true;
  };

  for (size_t b = 0; b + 2 < blocks.size() && !budgetHit; b += 3) {
    const int32_t bx = blocks[b];
    const int32_t by = blocks[b + 1];
    const int32_t bz = blocks[b + 2];
    gatherBlockGrid(bx, by, bz, grid.data());

    for (int lz = 0; lz < res; ++lz) {
      for (int ly = 0; ly < res; ++ly) {
        for (int lx = 0; lx < res; ++lx) {
          const int base = (lz * size + ly) * size + lx;
          const float v0 = grid[base];
          const int32_t gx = bx * res + lx;
          const int32_t gy = by * res + ly;
          const int32_t gz = bz * res + lz;

          if (!std::isfinite(v0)) continue;

          // ---- edge along +X ----------------------------------------------------------
          if (lx + 1 < size) {
            const float v1 = grid[base + 1];
            if (std::isfinite(v1) && (v0 < isoLevel) != (v1 < isoLevel)) {
              uint32_t a, bq, c, d;
              if (vertexIndex(gx, gy - 1, gz - 1, a) && vertexIndex(gx, gy, gz - 1, bq) &&
                  vertexIndex(gx, gy, gz, c) && vertexIndex(gx, gy - 1, gz, d)) {
                if (v0 < isoLevel) {
                  mesh.indices.insert(mesh.indices.end(), {a, bq, c, a, c, d});
                } else {
                  mesh.indices.insert(mesh.indices.end(), {a, d, c, a, c, bq});
                }
                if (mesh.indices.size() / 3 >= maxTriangles) budgetHit = true;
              }
            }
          }

          // ---- edge along +Y ----------------------------------------------------------
          if (ly + 1 < size) {
            const float v1 = grid[base + size];
            if (std::isfinite(v1) && (v0 < isoLevel) != (v1 < isoLevel)) {
              uint32_t a, bq, c, d;
              if (vertexIndex(gx - 1, gy, gz - 1, a) && vertexIndex(gx, gy, gz - 1, bq) &&
                  vertexIndex(gx, gy, gz, c) && vertexIndex(gx - 1, gy, gz, d)) {
                // (a,b,c,d) winds towards −Y, which is the outward direction when the
                // lower voxel is *outside* the surface.
                if (v0 < isoLevel) {
                  mesh.indices.insert(mesh.indices.end(), {a, d, c, a, c, bq});
                } else {
                  mesh.indices.insert(mesh.indices.end(), {a, bq, c, a, c, d});
                }
                if (mesh.indices.size() / 3 >= maxTriangles) budgetHit = true;
              }
            }
          }

          // ---- edge along +Z ----------------------------------------------------------
          if (lz + 1 < size) {
            const float v1 = grid[base + size * size];
            if (std::isfinite(v1) && (v0 < isoLevel) != (v1 < isoLevel)) {
              uint32_t a, bq, c, d;
              if (vertexIndex(gx - 1, gy - 1, gz, a) && vertexIndex(gx, gy - 1, gz, bq) &&
                  vertexIndex(gx, gy, gz, c) && vertexIndex(gx - 1, gy, gz, d)) {
                if (v0 < isoLevel) {
                  mesh.indices.insert(mesh.indices.end(), {a, bq, c, a, c, d});
                } else {
                  mesh.indices.insert(mesh.indices.end(), {a, d, c, a, c, bq});
                }
                if (mesh.indices.size() / 3 >= maxTriangles) budgetHit = true;
              }
            }
          }

          if (budgetHit) break;
        }
        if (budgetHit) break;
      }
      if (budgetHit) break;
    }
  }

  return mesh;
}

}  // namespace constructar
