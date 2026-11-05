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
// **Manhattan enforcement is the fifth step, and it is here** (`snapFloorplanToManhattan`). A room
// is built orthogonal; the RDP runs are not, because the slice they came from carries the depth
// noise of every wall it crossed. The run directions *are* the evidence: a closed loop's four runs
// are two directions, and the length-weighted mean of their doubled angles is the frame the room
// is trying to be. Each run within a tolerance of that frame is moved onto it, and the corners are
// then the intersections of the straightened runs - so the loop stays closed and the same corner
// is still one corner. A run further off than the tolerance (a bay, a chamfer, a party wall at 35
// degrees) is left exactly as measured: forcing it square would be a room the fitter did not see.
//
// The corners move, so the guard has to be geometric: if squaring changed the enclosed area by
// more than a quarter, the fit was not a fit and the loop is returned untouched. And nothing is
// snapped at all when the runs do not determine a frame (fewer than three usable runs, or a
// tolerance of zero) - `ManhattanFit::applied` is what a caller reads instead of guessing.
//
// What is **not** here: door/window classification (a gap of 0.7-1.2 m in a wall line). That needs
// the run list this file produces plus a rule about which gaps are openings and which are the end
// of the scan; it is the next step and it is not claimed.
//
// **Exposed over JNI and on a screen.** `nativeFloorplan` in `jni/slam_jni.cpp` slices the volume,
// masks the horizontal surfaces, vectorises and squares the loop, and returns the plan as a packed
// `float[]`; the Kotlin side parses it into `FloorplanPayload`, `TsdfScanPipeline` asks for it on
// the scan worker when a run is committed, and the result becomes the room's card picture *and* the
// outline the wall screen measures its walls from. What the Manhattan step did is reported
// separately by `nativeLastManhattan` (four floats), because the payload's layout is a pinned
// contract and appending statistics to it is how a mismatched library reads walls as numbers.
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

/**
 * What Manhattan enforcement did to a loop.
 *
 * `applied` is false when nothing was aligned - too few runs, a tolerance of zero, or a squared
 * loop whose area moved by more than `kMaxAreaDrift`. A caller that prints "squared to the room's
 * own axes" reads this first; the alternative is a sentence that is true of a different scan.
 */
struct ManhattanFit {
  bool applied = false;
  /** How far the room's dominant direction sat off the axes it was squared onto, in degrees. */
  float residualDegrees = 0.f;
  int snappedSegments = 0;
  int movedVertices = 0;
};

/** A squared loop may not change the enclosed area by more than this share (25 %). */
constexpr float kMaxAreaDrift = 0.25f;

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
  /**
   * How far a wall run may sit off the room's own axes and still be squared onto them, in
   * degrees. 0 disables enforcement; 10 is drift plus the slice's own noise.
   */
  float manhattanToleranceDegrees = 10.f;
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
  /** What the Manhattan step did, so a caller can say it rather than assume it. */
  ManhattanFit manhattan;
  bool valid = false;
};

/** Signed area of a closed polygon (positive counter-clockwise in the x/z plane). */
float polygonArea(const std::vector<SlicePoint>& polygon);

/** Ramer-Douglas-Peucker on an open chain; the first and last points are always kept. */
std::vector<SlicePoint> simplifyRdp(const std::vector<SlicePoint>& chain, float epsilonMetres);

/** Convenience: drops the height of a TSDF slice, keeping (x, z). */
std::vector<SlicePoint> projectToFloor(const std::vector<Vec3>& slicePoints);

/**
 * Squares a closed loop onto the two axes its own runs determine.
 *
 * Runs within `toleranceDegrees` of the frame are moved onto it, the loop's vertices become the
 * intersections of consecutive straightened runs, and a run that is not stamped keeps its own
 * endpoints. A vertex between two un-snapped runs does not move at all; one between a snapped and
 * an unsnapped run is the intersection of the snapped line with the run's *own* line, so the
 * chamfer stays where it was and its neighbour meets it where they now cross.
 *
 * `kMaxAreaDrift` is the guard: a fit that changes the room's area by more than a quarter was not
 * a fit, and the loop is returned exactly as it came in with `fit.applied == false`.
 */
void snapFloorplanToManhattan(std::vector<SlicePoint>& loop, float toleranceDegrees,
                              ManhattanFit& fit);

/** The vectorised floorplan of a waist-height slice. */
Floorplan vectoriseFloorplan(const std::vector<Vec3>& slicePoints,
                             const FloorplanOptions& options = {});
Floorplan vectoriseFloorplan(const std::vector<SlicePoint>& points,
                             const FloorplanOptions& options = {});

/**
 * Floats before the variable-length part of the JNI payload.
 *
 * The number is a **contract with Kotlin** (`FloorplanPayload.HEADER` in
 * `nativebridge/FloorplanPayload.kt`): JNI returns one `float[]`, and if the two sides disagree
 * about where the header ends, every segment reads a loop length and the plan on screen is a room
 * that does not exist. Neither compiler can see the other side, so the host suite pins the layout
 * here and `scripts/verify_sources.py` compares the two constants.
 */
constexpr int kFloorplanPayloadHeader = 8;

/**
 * Packs a [Floorplan] into the JNI payload.
 *
 * Layout (all floats, counts included - a count above 2^24 would lose precision, and a floorplan
 * with sixteen million corners is not a room):
 *
 * ```
 * [0] valid (0/1)        [1] closed (0/1)
 * [2] area m2            [3] perimeter m
 * [4] segmentCount       [5] loopCount
 * [6] pointCount         [7] occupiedCells
 * [8 ..]                 segmentCount x (startX, startZ, endX, endZ)
 * [..]                   loopCount x loopLength, loopCount x loopClosed (0/1)
 * [..]                   pointCount x (x, z)  - the loops concatenated, in order
 * ```
 *
 * A `loopClosed` shorter than `loopLengths` is padded with 0 (open) rather than read past the end:
 * a truncated array here once meant a doorway drawn as a wall, and the padding at least keeps the
 * geometry of the loops that do exist.
 */
std::vector<float> packFloorplanPayload(const Floorplan& plan);

}  // namespace constructar
