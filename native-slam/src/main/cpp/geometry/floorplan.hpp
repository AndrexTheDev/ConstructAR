/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// 2D floorplan vectorisation — section 4.A of the master prompt.
//
// The input is the waist-height slice of the TSDF (`TsdfVolume::sliceSurfaceAtHeight`): a ring of
// surface voxels at y = 1.0 m, which is above worktops and below door frames, so it cuts the walls
// and nothing else. Turning that ring into a floorplan is four steps, each of which has a reason
// to exist and a way to be wrong quietly:
//
//   1. **occupancy grid.** Points become cells. The grid, not the point list, is what the rest of
//      the pipeline works on, because a scan is a *cloud* with density that varies by an order of
//      magnitude with distance, and a simplification that depends on density is a simplification
//      that depends on how far the operator stood.
//   2. **boundary edges.** Each occupied cell with an empty neighbour emits the edge it shares
//      with that neighbour, oriented so the interior stays on the left. The set of those edges is
//      the region's outline, holes included, and it needs no convexity assumption - a U-shaped
//      room traces as a U.
//   3. **chaining.** Edges sharing a corner are joined into loops, choosing the sharpest turn at a
//      corner that has more than one candidate (a diagonal pinch), which is what keeps two rooms
//      that touch at a corner from becoming one figure-eight.
//   4. **RDP.** The grid outline is a staircase with a step every cell. Ramer-Douglas-Peucker
//      replaces each staircase run with the straight line it is trying to be, within `epsilon`.
//      This is the step that turns "wall" back into a measurement, and `epsilon` is therefore in
//      metres: below the cell size it does nothing, far above it a door jamb disappears.
//
// **An open ring stays open.** A doorway leaves the waist-height ring unclosed, and joining its last
// point back to its first would draw a wall across the doorway and report the area of a smaller
// room than the one that exists. Loops are therefore reported with the fact of their closure, the
// area and perimeter come only from loops that closed, and a scan with a door in it still reports
// every wall run.
//
// What is **not** here yet, and is not claimed: Manhattan angle enforcement (snapping a segment to
// exactly 0/45/90 degrees) and door/window classification (a gap of 0.7-1.2 m in a wall line).
// Both are the next step and both need this output first - a segment list and a loop - which is
// what this file produces.
//
// **Not wired.** No JNI symbol exposes this and no screen calls it: the app has no floorplan
// thumbnail yet (section 4.B), and `docs/CALIBRATION.md`-style honesty applies here too - the
// engine is host-tested, the product does not use it.
#pragma once

#include <vector>

#include "../core/types.hpp"

namespace constructar {

/** A point on the floor plane: metres east (+x) and metres north (+z). */
struct SlicePoint {
  float x = 0.f;
  float z = 0.f;

  SlicePoint() = default;
  SlicePoint(float x_, float z_) : x(x_), z(z_) {}
};

/** A wall run between two corners, in metres. */
struct WallSegment {
  SlicePoint start;
  SlicePoint end;
  float length() const;
};

struct FloorplanOptions {
  /** Occupancy cell. 5 cm is one TSDF voxel plus slack: the slice is a 2 cm lattice. */
  float cellMetres = 0.05f;
  /** RDP tolerance. Must exceed the cell size or the staircase survives simplification. */
  float rdpEpsilonMetres = 0.08f;
  /** Shorter runs are stairsteps or noise, not walls. */
  float minSegmentMetres = 0.25f;
  /**
   * A closed loop only counts as the room when it encloses at least this share of the slice's
   * bounding rectangle. A waist-height scan of a room with an open doorway is an *open* ring, and
   * tracing it yields small closed loops wherever the ring's two faces meet - 0.2 m2 slivers that
   * would be reported as "the room". Anything below this share is such a sliver, and is rejected
   * so that a scan with a door in it reports no floorplan instead of a wrong one.
   */
  float minEnclosedFraction = 0.25f;
  /** Empty cells kept around the cloud so its boundary is always a real boundary. */
  int paddingCells = 2;
};

struct Floorplan {
  /** Wall runs from every traced loop, in metres. */
  std::vector<WallSegment> segments;
  /** The simplified loops, concatenated; `loopLengths[i]` is how many points `loop i` used. */
  std::vector<SlicePoint> outline;
  std::vector<int> loopLengths;
  /** `loopClosed[i]` is 1 when loop `i` returned to its own first vertex. */
  std::vector<char> loopClosed;
  /**
   * Area of the largest **closed** loop, square metres, and its perimeter.
   *
   * Both are zero when nothing closed, and that is the point: a waist-height slice of a room with
   * an open doorway is an open ring, and closing it by joining the last point to the first wall
   * run invents a wall across the doorway - a number that looks like an area and is not one. An
   * open plan still reports its segments (so the walls are drawn and the gap is visible); it does
   * not report an area it cannot compute.
   */
  float areaSquareMetres = 0.f;
  float perimeterMetres = 0.f;
  /** True when a loop closed and enclosed enough of the slice to be the room. */
  bool closed = false;
  int occupiedCells = 0;
  bool valid = false;
};

/** Signed area of a closed polygon (positive counter-clockwise in the x/z plane). */
float polygonArea(const std::vector<SlicePoint>& polygon);

/** Ramer-Douglas-Peucker on an open chain; the first and last points are always kept. */
std::vector<SlicePoint> simplifyRdp(const std::vector<SlicePoint>& chain, float epsilonMetres);

/** Convenience: drops the height of a TSDF slice, keeping (x, z). */
std::vector<SlicePoint> projectToFloor(const std::vector<Vec3>& slicePoints);

/** The vectorised floorplan of a waist-height slice. */
Floorplan vectoriseFloorplan(const std::vector<Vec3>& slicePoints,
                             const FloorplanOptions& options = {});
Floorplan vectoriseFloorplan(const std::vector<SlicePoint>& points,
                             const FloorplanOptions& options = {});

}  // namespace constructar
