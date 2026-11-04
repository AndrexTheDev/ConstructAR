/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// Occupancy -> boundary edges -> loops -> RDP. See the header for why each step exists.
//
// The one implementation detail worth stating: vertex keys are integer grid corners, not floats.
// Every comparison in the chainer is therefore exact, so a loop either closes or it does not -
// there is no epsilon to tune, and two runs of the same scan produce byte-identical outlines
// (which the host suite asserts).
#include "floorplan.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <unordered_map>
#include <utility>

namespace constructar {

float WallSegment::length() const {
  const float dx = end.x - start.x;
  const float dz = end.z - start.z;
  return std::sqrt(dx * dx + dz * dz);
}

float polygonArea(const std::vector<SlicePoint>& polygon) {
  double twice = 0.0;
  for (size_t i = 0; i < polygon.size(); ++i) {
    const SlicePoint& a = polygon[i];
    const SlicePoint& b = polygon[(i + 1) % polygon.size()];
    twice += static_cast<double>(a.x) * static_cast<double>(b.z) -
             static_cast<double>(b.x) * static_cast<double>(a.z);
  }
  return static_cast<float>(0.5 * twice);
}

namespace {

float perpendicularDistance(const SlicePoint& point, const SlicePoint& lineStart,
                            const SlicePoint& lineEnd) {
  const float dx = lineEnd.x - lineStart.x;
  const float dz = lineEnd.z - lineStart.z;
  const float lengthSquared = dx * dx + dz * dz;
  if (!(lengthSquared > 0.f)) {
    const float px = point.x - lineStart.x;
    const float pz = point.z - lineStart.z;
    return std::sqrt(px * px + pz * pz);
  }
  const float cross = (point.x - lineStart.x) * dz - (point.z - lineStart.z) * dx;
  return std::fabs(cross) / std::sqrt(lengthSquared);
}

/** RDP over `[first, last]` inclusive, keeping indices. */
void rdpRecursive(const std::vector<SlicePoint>& chain, size_t first, size_t last, float epsilon,
                  std::vector<char>& keep) {
  if (last <= first + 1) return;
  float worst = -1.f;
  size_t worstIndex = first;
  for (size_t i = first + 1; i < last; ++i) {
    const float distance = perpendicularDistance(chain[i], chain[first], chain[last]);
    if (distance > worst) {
      worst = distance;
      worstIndex = i;
    }
  }
  if (worst > epsilon) {
    keep[worstIndex] = 1;
    rdpRecursive(chain, first, worstIndex, epsilon, keep);
    rdpRecursive(chain, worstIndex, last, epsilon, keep);
  }
}

// --- the grid ---------------------------------------------------------------------------------

struct Grid {
  int columns = 0;
  int rows = 0;
  float cell = 0.05f;
  float originX = 0.f;
  float originZ = 0.f;
  std::vector<char> occupied;

  bool inside(int i, int j) const { return i >= 0 && j >= 0 && i < columns && j < rows; }
  bool at(int i, int j) const { return inside(i, j) && occupied[static_cast<size_t>(j) * columns + i]; }
  SlicePoint corner(int i, int j) const {
    return SlicePoint(originX + static_cast<float>(i) * cell, originZ + static_cast<float>(j) * cell);
  }
};

Grid buildGrid(const std::vector<SlicePoint>& points, const FloorplanOptions& options) {
  Grid grid;
  grid.cell = std::max(options.cellMetres, 0.005f);
  if (points.empty()) return grid;

  float minX = points[0].x, maxX = points[0].x, minZ = points[0].z, maxZ = points[0].z;
  for (const SlicePoint& point : points) {
    if (!std::isfinite(point.x) || !std::isfinite(point.z)) continue;
    minX = std::min(minX, point.x);
    maxX = std::max(maxX, point.x);
    minZ = std::min(minZ, point.z);
    maxZ = std::max(maxZ, point.z);
  }
  const int padding = std::max(options.paddingCells, 1);
  grid.originX = minX - static_cast<float>(padding) * grid.cell;
  grid.originZ = minZ - static_cast<float>(padding) * grid.cell;
  grid.columns = static_cast<int>(std::ceil((maxX - minX) / grid.cell)) + 2 * padding + 1;
  grid.rows = static_cast<int>(std::ceil((maxZ - minZ) / grid.cell)) + 2 * padding + 1;
  if (grid.columns <= 0 || grid.rows <= 0) return grid;

  grid.occupied.assign(static_cast<size_t>(grid.columns) * static_cast<size_t>(grid.rows), 0);
  for (const SlicePoint& point : points) {
    if (!std::isfinite(point.x) || !std::isfinite(point.z)) continue;
    const int i = static_cast<int>(std::floor((point.x - grid.originX) / grid.cell));
    const int j = static_cast<int>(std::floor((point.z - grid.originZ) / grid.cell));
    if (grid.inside(i, j)) grid.occupied[static_cast<size_t>(j) * grid.columns + i] = 1;
  }
  return grid;
}

/**
 * Fills one cell of every diagonal pinch, so a band whose cells touch only at a corner becomes
 * solid. A wall at 45 degrees is exactly such a band: its boundary would otherwise break into
 * dozens of four-point bubbles instead of closing around the room, and the room would be reported
 * as unclosed. Which of the two cells is filled is fixed by raster order, so two runs of the same
 * scan still produce byte-identical outlines.
 */
void resolveDiagonalPinches(Grid& grid) {
  for (int j = 0; j + 1 < grid.rows; ++j) {
    for (int i = 0; i + 1 < grid.columns; ++i) {
      const size_t a = static_cast<size_t>(j) * grid.columns + i;
      const size_t b = a + 1;
      const size_t c = a + grid.columns;
      const size_t d = c + 1;
      if (grid.occupied[a] && grid.occupied[d] && !grid.occupied[b] && !grid.occupied[c]) {
        grid.occupied[b] = 1;
      } else if (grid.occupied[b] && grid.occupied[c] && !grid.occupied[a] && !grid.occupied[d]) {
        grid.occupied[a] = 1;
      }
    }
  }
}

struct DirectedEdge {
  int fromI = 0, fromJ = 0;
  int toI = 0, toJ = 0;
  bool used = false;
};

std::int64_t vertexKey(int i, int j) {
  return (static_cast<std::int64_t>(i) << 32) ^ static_cast<std::int64_t>(static_cast<std::uint32_t>(j));
}

/**
 * Every occupied cell's empty-sided edges, walked clockwise around the cell in the (x, z) plane.
 * The result has the region's interior on the left, so an outer boundary comes out
 * counter-clockwise and a hole comes out clockwise; the caller fixes the winding by area.
 */
std::vector<DirectedEdge> collectBoundaryEdges(const Grid& grid) {
  std::vector<DirectedEdge> edges;
  for (int j = 0; j < grid.rows; ++j) {
    for (int i = 0; i < grid.columns; ++i) {
      if (!grid.at(i, j)) continue;
      if (!grid.at(i, j - 1)) edges.push_back({i, j, i + 1, j, false});          // south side
      if (!grid.at(i + 1, j)) edges.push_back({i + 1, j, i + 1, j + 1, false});  // east side
      if (!grid.at(i, j + 1)) edges.push_back({i + 1, j + 1, i, j + 1, false});  // north side
      if (!grid.at(i - 1, j)) edges.push_back({i, j + 1, i, j, false});          // west side
    }
  }
  return edges;
}

struct TracedLoop {
  std::vector<SlicePoint> points;
  bool closed = false;
};

/** Chains the edges into loops, taking the straightest available turn at a corner. */
std::vector<TracedLoop> chainLoops(const Grid& grid, std::vector<DirectedEdge>& edges) {
  std::unordered_map<std::int64_t, std::vector<size_t>> outgoing;
  for (size_t index = 0; index < edges.size(); ++index) {
    outgoing[vertexKey(edges[index].fromI, edges[index].fromJ)].push_back(index);
  }

  std::vector<TracedLoop> loops;
  for (size_t startIndex = 0; startIndex < edges.size(); ++startIndex) {
    if (edges[startIndex].used) continue;
    std::vector<SlicePoint> loop;
    bool closed = false;
    size_t current = startIndex;
    const std::int64_t loopStartKey = vertexKey(edges[startIndex].fromI, edges[startIndex].fromJ);
    while (true) {
      DirectedEdge& edge = edges[current];
      if (edge.used) break;
      edge.used = true;
      loop.push_back(grid.corner(edge.fromI, edge.fromJ));

      const std::int64_t endKey = vertexKey(edge.toI, edge.toJ);
      if (endKey == loopStartKey) {
        closed = true;
        break;
      }
      auto candidates = outgoing.find(endKey);
      if (candidates == outgoing.end()) break;

      // Left-hand rule.
      const int inI = edge.toI - edge.fromI;
      const int inJ = edge.toJ - edge.fromJ;
      size_t best = edges.size();
      float bestScore = -2.f;
      for (size_t candidate : candidates->second) {
        if (edges[candidate].used) continue;
        const int outI = edges[candidate].toI - edges[candidate].fromI;
        const int outJ = edges[candidate].toJ - edges[candidate].fromJ;
        const float score = static_cast<float>(inI * outJ - inJ * outI);  // >0 = left turn
        if (score > bestScore) {
          bestScore = score;
          best = candidate;
        }
      }
      if (best == edges.size()) break;
      current = best;
    }
    if (loop.size() >= 3) loops.push_back(TracedLoop{std::move(loop), closed});
  }
  return loops;
}

std::vector<SlicePoint> simplifyLoop(const std::vector<SlicePoint>& loop, float epsilon) {
  if (loop.size() <= 3) return loop;

  // Split the closed loop at the vertex farthest from the first one: RDP on two open chains, then
  // join them. Simplifying a loop "as an open chain" would pin two arbitrary vertices.
  size_t split = 1;
  float bestDistance = 0.f;
  for (size_t i = 1; i < loop.size(); ++i) {
    const float dx = loop[i].x - loop[0].x;
    const float dz = loop[i].z - loop[0].z;
    const float distance = dx * dx + dz * dz;
    if (distance > bestDistance) {
      bestDistance = distance;
      split = i;
    }
  }
  if (split == 0 || split + 1 >= loop.size()) return loop;

  std::vector<SlicePoint> first(loop.begin(), loop.begin() + static_cast<long>(split) + 1);
  std::vector<SlicePoint> second(loop.begin() + static_cast<long>(split), loop.end());
  second.push_back(loop.front());

  std::vector<SlicePoint> simplified = simplifyRdp(first, epsilon);
  const std::vector<SlicePoint> tail = simplifyRdp(second, epsilon);
  // The last point of `simplified` and the first of `tail` are the same vertex.
  simplified.insert(simplified.end(), tail.begin() + 1, tail.end() - 1);
  return simplified;
}

}  // namespace

std::vector<SlicePoint> simplifyRdp(const std::vector<SlicePoint>& chain, float epsilonMetres) {
  if (chain.size() <= 2) return chain;
  std::vector<char> keep(chain.size(), 0);
  keep.front() = 1;
  keep.back() = 1;
  rdpRecursive(chain, 0, chain.size() - 1, std::max(epsilonMetres, 0.f), keep);
  std::vector<SlicePoint> simplified;
  simplified.reserve(chain.size());
  for (size_t i = 0; i < chain.size(); ++i) {
    if (keep[i]) simplified.push_back(chain[i]);
  }
  return simplified;
}

std::vector<SlicePoint> projectToFloor(const std::vector<Vec3>& slicePoints) {
  std::vector<SlicePoint> points;
  points.reserve(slicePoints.size());
  for (const Vec3& point : slicePoints) {
    if (!point.isFinite()) continue;
    points.push_back(SlicePoint(point.x, point.z));
  }
  return points;
}

Floorplan vectoriseFloorplan(const std::vector<Vec3>& slicePoints,
                             const FloorplanOptions& options) {
  return vectoriseFloorplan(projectToFloor(slicePoints), options);
}

Floorplan vectoriseFloorplan(const std::vector<SlicePoint>& points,
                             const FloorplanOptions& options) {
  Floorplan plan;
  Grid grid = buildGrid(points, options);
  if (grid.occupied.empty()) return plan;
  resolveDiagonalPinches(grid);

  int occupied = 0;
  for (char cell : grid.occupied) occupied += cell ? 1 : 0;
  plan.occupiedCells = occupied;
  if (occupied < 4) return plan;

  std::vector<DirectedEdge> edges = collectBoundaryEdges(grid);
  if (edges.empty()) return plan;

  // The slice is a band around each measured surface, one cell wide, so a wall appears as two
  // parallel runs five centimetres apart joined by caps at its ends. The larger closed loop is the
  // band's outer face - the room seen from outside the wall - and it is the one reported, so a wall
  // is one segment rather than two.
  int minI = grid.rows, maxI = -1, minJ = grid.columns, maxJ = -1;
  for (int i = 0; i < grid.rows; ++i) {
    for (int j = 0; j < grid.columns; ++j) {
      if (!grid.occupied[static_cast<size_t>(i) * grid.columns + j]) continue;
      minI = std::min(minI, i);
      maxI = std::max(maxI, i);
      minJ = std::min(minJ, j);
      maxJ = std::max(maxJ, j);
    }
  }
  const float boundingArea = static_cast<float>((maxI - minI + 1) * (maxJ - minJ + 1)) *
                             options.cellMetres * options.cellMetres;
  const float minimumArea = options.minEnclosedFraction * boundingArea;

  float bestArea = 0.f;
  std::vector<SlicePoint> bestLoop;
  for (const TracedLoop& loop : chainLoops(grid, edges)) {
    if (!loop.closed) continue;
    const std::vector<SlicePoint> simplified = simplifyLoop(loop.points, options.rdpEpsilonMetres);
    if (simplified.size() < 3) continue;
    const float area = std::fabs(polygonArea(simplified));
    if (area < minimumArea || area <= bestArea) continue;
    bestArea = area;
    bestLoop = simplified;
  }
  if (bestLoop.size() < 3) return plan;

  plan.outline = bestLoop;
  plan.loopLengths.push_back(static_cast<int>(bestLoop.size()));
  plan.loopClosed.push_back(1);
  for (size_t i = 0; i < bestLoop.size(); ++i) {
    const WallSegment segment{bestLoop[i], bestLoop[(i + 1) % bestLoop.size()]};
    if (segment.length() >= options.minSegmentMetres) plan.segments.push_back(segment);
  }
  plan.closed = true;
  plan.areaSquareMetres = bestArea;
  float perimeter = 0.f;
  for (size_t i = 0; i < bestLoop.size(); ++i) {
    perimeter += WallSegment{bestLoop[i], bestLoop[(i + 1) % bestLoop.size()]}.length();
  }
  plan.perimeterMetres = perimeter;
  plan.valid = !plan.segments.empty();
  return plan;
}

}  // namespace constructar
