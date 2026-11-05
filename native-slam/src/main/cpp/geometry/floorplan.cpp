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

ManhattanHint hintFromAxes(const Vec3& axisA, const Vec3& axisB, const Vec3& axisC) {
  ManhattanHint hint;
  const Vec3 axes[3] = {axisA, axisB, axisC};
  // The two horizontal axes: the pair with the smallest vertical component. A wall's normal is
  // horizontal; the floor's is not.
  int first = -1;
  int second = -1;
  for (int i = 0; i < 3; ++i) {
    if (!std::isfinite(axes[i].x) || !std::isfinite(axes[i].y) || !std::isfinite(axes[i].z)) {
      return hint;
    }
    if (std::fabs(axes[i].y) > 0.30f) continue;
    if (first < 0) {
      first = i;
    } else if (second < 0) {
      second = i;
    }
  }
  if (first < 0 || second < 0) return hint;
  // Both must be directions, not near-zero vectors: `atan2(0, 0)` is an angle of zero, which would
  // silently mean "the room is already square".
  const Vec3& chosen = axes[first];
  const float length = std::sqrt(chosen.x * chosen.x + chosen.z * chosen.z);
  if (!(length > 1e-3f)) return hint;
  hint.hasAngle = true;
  hint.angleRadians = std::atan2(chosen.z, chosen.x);
  return hint;
}

void snapFloorplanToManhattan(std::vector<SlicePoint>& loop, float toleranceDegrees,
                              ManhattanFit& fit, const ManhattanHint& hint) {
  fit.applied = false;
  fit.usedPlaneFrame = false;
  fit.residualDegrees = 0.f;
  fit.snappedSegments = 0;
  fit.movedVertices = 0;

  const size_t count = loop.size();
  if (count < 3) return;
  if (!(toleranceDegrees > 0.f) || !std::isfinite(toleranceDegrees)) return;

  const double kEpsilon = 1e-7;
  // kPi is not standard C++ and is missing under -std=c++20 on some libcs; one constant is
  // cheaper than a platform guard.
  constexpr double kPi = 3.14159265358979323846;

  // 1. The frame the room is trying to be: the length-weighted mean of the runs' **quadrupled**
  //    angles. Doubling is not enough and the failure is not subtle - an orthogonal room has two
  //    families 90 degrees apart, so 2*theta differs by 180 degrees between them and the two cancel
  //    exactly. Quadrupling maps both families onto the same direction (a frame is only defined
  //    modulo 90 degrees), which is what a squared room is. Two runs of one wall family still count
  //    twice what one short return does.
  double cosine = 0.0;
  double sine = 0.0;
  int usableRuns = 0;
  std::vector<float> length(count, 0.f);
  std::vector<float> heading(count, 0.f);
  for (size_t i = 0; i < count; ++i) {
    const SlicePoint& from = loop[i];
    const SlicePoint& to = loop[(i + 1) % count];
    const float dx = to.x - from.x;
    const float dz = to.z - from.z;
    const float runLength = std::sqrt(dx * dx + dz * dz);
    length[i] = runLength;
    if (!(runLength > 0.f)) continue;
    const double phi = std::atan2(static_cast<double>(dz), static_cast<double>(dx));
    heading[i] = static_cast<float>(phi);
    cosine += static_cast<double>(runLength) * std::cos(4.0 * phi);
    sine += static_cast<double>(runLength) * std::sin(4.0 * phi);
    ++usableRuns;
  }
  if (usableRuns < 3) return;
  if (!(cosine * cosine + sine * sine > kEpsilon)) return;
  const double runFrame = 0.25 * std::atan2(sine, cosine);

  // The planes' frame, when the caller has one, is the better evidence - and the runs are what it
  // has to be checked against, because a frame fitted from the wrong cloud would square a room
  // that is already right. Anything further than 30 degrees from what the runs say is refused.
  double normalisedHint = 0.0;
  if (std::isfinite(hint.angleRadians) &&
      std::fabs(std::remainder(static_cast<double>(hint.angleRadians) - runFrame, kPi / 2.0)) <=
          kPi / 6.0) {
    const double quarter = kPi / 2.0;
    normalisedHint = hint.angleRadians - std::floor(static_cast<double>(hint.angleRadians) /
                                                    quarter) * quarter;
    fit.usedPlaneFrame = hint.hasAngle;
  }
  const double theta0 = fit.usedPlaneFrame ? normalisedHint : runFrame;

  // 2. Each run either snaps to theta0 + k * 90 degrees or keeps its measured line. The snapped
  //    line keeps the run's midpoint, so a wall stays where the room put it and only its angle
  //    changes.
  const double toleranceRadians = static_cast<double>(toleranceDegrees) * kPi / 180.0;
  struct Line {
    SlicePoint point;
    SlicePoint direction;
    bool snapped = false;
  };
  std::vector<Line> lines(count);
  for (size_t i = 0; i < count; ++i) {
    if (!(length[i] > 0.f)) {
      lines[i].point = loop[i];
      lines[i].direction = SlicePoint(1.f, 0.f);
      continue;
    }
    const double target = theta0 + std::round((static_cast<double>(heading[i]) - theta0) /
                                              (kPi / 2.0)) * (kPi / 2.0);
    const double offBy = static_cast<double>(heading[i]) - target;
    lines[i].point = SlicePoint((loop[i].x + loop[(i + 1) % count].x) * 0.5f,
                                (loop[i].z + loop[(i + 1) % count].z) * 0.5f);
    if (std::fabs(offBy) <= toleranceRadians) {
      lines[i].snapped = true;
      lines[i].direction = SlicePoint(static_cast<float>(std::cos(target)),
                                      static_cast<float>(std::sin(target)));
      fit.snappedSegments += 1;
    } else {
      lines[i].direction = SlicePoint(static_cast<float>(std::cos(heading[i])),
                                      static_cast<float>(std::sin(heading[i])));
    }
  }
  if (fit.snappedSegments < 2) {
    fit.snappedSegments = 0;
    return;
  }

  // 3. Vertex i sits between run i-1 and run i, so it becomes the crossing of the two (possibly
  //    straightened) lines. Two runs that are nearly parallel have no usable crossing; that vertex
  //    stays where the scan put it.
  std::vector<SlicePoint> squared = loop;
  int moved = 0;
  for (size_t i = 0; i < count; ++i) {
    const Line& previous = lines[(i + count - 1) % count];
    const Line& next = lines[i];
    const float cross = previous.direction.x * next.direction.z -
                        previous.direction.z * next.direction.x;
    if (std::fabs(cross) < 0.5f) continue;  // closer than 30 degrees: no honest corner
    const float dx = next.point.x - previous.point.x;
    const float dz = next.point.z - previous.point.z;
    const float t = (dx * next.direction.z - dz * next.direction.x) / cross;
    const SlicePoint corner(previous.point.x + t * previous.direction.x,
                            previous.point.z + t * previous.direction.z);
    if (!std::isfinite(corner.x) || !std::isfinite(corner.z)) continue;
    squared[i] = corner;
    ++moved;
  }
  if (moved == 0) {
    fit.snappedSegments = 0;
    return;
  }

  // 4. The guard. Corners that move change the area; a fit that changes it by more than
  //    kMaxAreaDrift was not a fit, and the measured loop is what gets reported.
  const float before = std::fabs(polygonArea(loop));
  const float after = std::fabs(polygonArea(squared));
  if (!(before > 0.f) || std::fabs(after - before) > kMaxAreaDrift * before) {
    fit.snappedSegments = 0;
    fit.movedVertices = 0;
    return;
  }

  // 5. How far the room actually sat off square, length-weighted: the number a report quotes, and
  //    zero only when the scan was already orthogonal.
  double weightedOffBy = 0.0;
  double weight = 0.0;
  for (size_t i = 0; i < count; ++i) {
    if (!lines[i].snapped || !(length[i] > 0.f)) continue;
    const double target = theta0 + std::round((static_cast<double>(heading[i]) - theta0) /
                                              (kPi / 2.0)) * (kPi / 2.0);
    weightedOffBy += std::fabs(static_cast<double>(heading[i]) - target) *
                     static_cast<double>(length[i]);
    weight += static_cast<double>(length[i]);
  }

  loop = squared;
  fit.applied = true;
  fit.movedVertices = moved;
  fit.residualDegrees =
      weight > 0.0 ? static_cast<float>(weightedOffBy / weight * 180.0 / kPi) : 0.f;
}

Floorplan vectoriseFloorplan(const std::vector<Vec3>& slicePoints,
                             const FloorplanOptions& options) {
  return vectoriseFloorplan(projectToFloor(slicePoints), options);
}

Floorplan vectoriseFloorplan(const std::vector<SlicePoint>& points,
                             const FloorplanOptions& options) {
  return vectoriseFloorplan(points, options, ManhattanHint{});
}

Floorplan vectoriseFloorplan(const std::vector<SlicePoint>& points,
                             const FloorplanOptions& options,
                             const ManhattanHint& hint) {
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

  // The last simplification is the room's own axes: the runs decide the frame, the frame moves the
  // runs, and the corners follow. `plan.manhattan` says what it did, including "nothing".
  ManhattanFit fit;
  snapFloorplanToManhattan(bestLoop, options.manhattanToleranceDegrees, fit, hint);
  plan.manhattan = fit;

  plan.outline = bestLoop;
  plan.loopLengths.push_back(static_cast<int>(bestLoop.size()));
  plan.loopClosed.push_back(1);
  for (size_t i = 0; i < bestLoop.size(); ++i) {
    const WallSegment segment{bestLoop[i], bestLoop[(i + 1) % bestLoop.size()]};
    if (segment.length() >= options.minSegmentMetres) plan.segments.push_back(segment);
  }
  plan.closed = true;
  // The area is the loop's own, *after* the Manhattan step: the pre-snap figure is what chose this
  // loop among the candidates, and reporting it here printed a room 1.7 % larger than the one the
  // segments and the perimeter describe (the snap had already moved every corner).
  plan.areaSquareMetres = std::fabs(polygonArea(bestLoop));
  float perimeter = 0.f;
  for (size_t i = 0; i < bestLoop.size(); ++i) {
    perimeter += WallSegment{bestLoop[i], bestLoop[(i + 1) % bestLoop.size()]}.length();
  }
  plan.perimeterMetres = perimeter;
  plan.valid = !plan.segments.empty();
  return plan;
}

std::vector<float> packFloorplanPayload(const Floorplan& plan) {
  const size_t segments = plan.segments.size();
  const size_t loops = plan.loopLengths.size();
  const size_t points = plan.outline.size();

  std::vector<float> payload;
  payload.reserve(static_cast<size_t>(kFloorplanPayloadHeader) + segments * 4 + loops * 2 + points * 2);

  payload.push_back(plan.valid ? 1.f : 0.f);
  payload.push_back(plan.closed ? 1.f : 0.f);
  payload.push_back(plan.areaSquareMetres);
  payload.push_back(plan.perimeterMetres);
  payload.push_back(static_cast<float>(segments));
  payload.push_back(static_cast<float>(loops));
  payload.push_back(static_cast<float>(points));
  payload.push_back(static_cast<float>(plan.occupiedCells));

  for (const WallSegment& segment : plan.segments) {
    payload.push_back(segment.start.x);
    payload.push_back(segment.start.z);
    payload.push_back(segment.end.x);
    payload.push_back(segment.end.z);
  }
  for (const int length : plan.loopLengths) payload.push_back(static_cast<float>(length));
  for (size_t i = 0; i < loops; ++i) {
    const bool closed = i < plan.loopClosed.size() && plan.loopClosed[i] != 0;
    payload.push_back(closed ? 1.f : 0.f);
  }
  for (const SlicePoint& point : plan.outline) {
    payload.push_back(point.x);
    payload.push_back(point.z);
  }
  return payload;
}

}  // namespace constructar
