/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import kotlin.math.sqrt

/**
 * A measured wall, and what a real one has had cut out of it.
 *
 * The drywall estimate is the first number in this app that is a *purchase*: m² of board, metres of
 * CW and UW profile, a box of screws. Every one of those is derived from wall area, and wall area
 * is the one quantity a room never has in the shape the geometry does - a 4 × 3 m room has four
 * walls and at least a door, usually a window, and the door is 1.85 m² of the 35 m² that no board
 * goes on. Counting the gross area over-orders by ~5 % on a single room and by more on a flat.
 *
 * Two things decide whether the subtraction is right, and both are here rather than in the screen:
 *
 *  - **Overlapping openings must not be subtracted twice.** Two windows that overlap in the wall's
 *    coordinate system (a wide opening split into two measured panes, a door with a fanlight, two
 *    openings that touch) cover a union, not a sum. Subtracting the sum takes board off a wall
 *    that is still there, and the estimate then *under*-orders - the failure mode that ends with a
 *    fitter short of board in a room that is already half boarded. The union is computed exactly,
 *    by sweeping the x breakpoints and merging the y spans in each strip.
 *  - **An opening that does not fit the wall is reported, not silently clamped.** A measured
 *    opening 20 cm past the end of its wall is a measurement problem the operator can fix, and a
 *    number that quietly clips it produces an estimate nobody can reconcile with the room. Clipped
 *    openings are counted and the rejection reasons are enumerated.
 *
 * Nothing here formats a sentence: the report carries numbers and reasons, and `:app` owns the
 * words (finding 100's rule).
 */

/** Wall height assumed when a wall arrives without one: German drywall ceiling height, 2.50 m. */
const val DEFAULT_WALL_HEIGHT_M: Float = 2.50f

private const val EPS = 1e-4

/**
 * One wall as it was measured: a straight run on the floor plane, from the floor to its height.
 *
 * The run is in the project's world coordinates (x/z, metres). Its length and gross area are
 * derived rather than stored, so there is exactly one definition of each.
 */
data class MeasuredWall(
    val startXM: Float,
    val startZM: Float,
    val endXM: Float,
    val endZM: Float,
    val heightM: Float = DEFAULT_WALL_HEIGHT_M,
) {
    /** Length of the run in metres; 0 for a degenerate wall (two identical points). */
    val lengthM: Float
        get() {
            val dx = (endXM - startXM).toDouble()
            val dz = (endZM - startZM).toDouble()
            return sqrt(dx * dx + dz * dz).toFloat()
        }

    /** Wall area with nothing cut out of it, in square metres. */
    val grossAreaM2: Float get() = lengthM * heightM
}

/** What kind of hole an opening is. Doors reach the floor; windows and passages need not. */
enum class OpeningKind { DOOR, WINDOW, PASSAGE }

/**
 * A door, window or passage in one wall.
 *
 * @param offsetM     distance along the wall from its start point to the opening's near edge
 * @param widthM      horizontal extent of the opening
 * @param sillHeightM height of the opening's bottom edge above the floor (0 for a door)
 * @param heightM     vertical extent of the opening
 */
data class WallOpening(
    val kind: OpeningKind,
    val offsetM: Float,
    val widthM: Float,
    val sillHeightM: Float,
    val heightM: Float,
)

/** Why an opening could not be subtracted. Enumerated so a screen can say it in its own words. */
enum class OpeningRejection {
    /** Zero or negative width or height: nothing to subtract and nothing to draw. */
    NEGATIVE_SIZE,

    /** The opening starts past the end of its wall, or ends before the wall's start. */
    OUTSIDE_WALL,

    /** The opening's bottom edge is at or above the top of its wall. */
    ABOVE_WALL,
}

/** One opening that was not subtracted, and why. */
data class RejectedOpening(
    val kind: OpeningKind,
    /** Index of the opening within its wall's list - the same index the caller supplied. */
    val index: Int,
    val reason: OpeningRejection,
)

/** One wall's line in the report. */
data class WallSurfaceLine(
    val wallIndex: Int,
    val lengthM: Float,
    val heightM: Float,
    val grossAreaM2: Float,
    val openingAreaM2: Float,
    val netAreaM2: Float,
    /** How many openings were actually subtracted from this wall. */
    val openingsSubtracted: Int,
    /** The openings covered the wall: net area is zero because there is no wall left. */
    val fullyOpen: Boolean,
)

/** The whole room's wall surface. */
data class WallSurfaceReport(
    val lines: List<WallSurfaceLine>,
    val totalLengthM: Float,
    val grossAreaM2: Float,
    val openingAreaM2: Float,
    val netAreaM2: Float,
    val rejected: List<RejectedOpening>,
    /** Openings that reached past the wall and were clipped to it before subtraction. */
    val clippedOpenings: Int,
) {
    /** True when there was at least one wall to measure. */
    val valid: Boolean get() = lines.isNotEmpty()
}

/**
 * Net wall area for every wall, with its openings subtracted as a **union**.
 *
 * @param walls    the measured walls, in the order the caller wants them reported
 * @param openings openings per wall index; a wall with no entry has none
 */
fun wallSurface(
    walls: List<MeasuredWall>,
    openings: Map<Int, List<WallOpening>> = emptyMap(),
): WallSurfaceReport {
    val lines = ArrayList<WallSurfaceLine>(walls.size)
    val rejected = ArrayList<RejectedOpening>()
    var clipped = 0

    walls.forEachIndexed { wallIndex, wall ->
        val length = wall.lengthM.toDouble()
        val height = wall.heightM.toDouble()
        val rectangles = ArrayList<Rect>()
        var subtracted = 0

        (openings[wallIndex] ?: emptyList()).forEachIndexed { openingIndex, opening ->
            val width = opening.widthM.toDouble()
            val openingHeight = opening.heightM.toDouble()
            val offset = opening.offsetM.toDouble()
            val sill = opening.sillHeightM.toDouble()
            when {
                !(width > EPS) || !(openingHeight > EPS) ->
                    rejected += RejectedOpening(opening.kind, openingIndex, OpeningRejection.NEGATIVE_SIZE)

                !(height > EPS) || !(sill < height - EPS) ->
                    rejected += RejectedOpening(opening.kind, openingIndex, OpeningRejection.ABOVE_WALL)

                !(length > EPS) || offset >= length - EPS || offset + width <= EPS ->
                    rejected += RejectedOpening(opening.kind, openingIndex, OpeningRejection.OUTSIDE_WALL)

                else -> {
                    val x0 = offset
                    val x1 = offset + width
                    val y0 = sill
                    val y1 = sill + openingHeight
                    // Clipped to the wall it belongs to, but flagged: a window 20 cm past the end of
                    // its wall is a measurement the operator can check, and a number that silently
                    // clips it cannot be reconciled with the room.
                    val touched =
                        x0 < -EPS || x1 > length + EPS || y1 > height + EPS || y0 < -EPS
                    val clippedX0 = x0.coerceIn(0.0, length)
                    val clippedX1 = x1.coerceIn(0.0, length)
                    val clippedY0 = y0.coerceIn(0.0, height)
                    val clippedY1 = y1.coerceIn(0.0, height)
                    if (clippedX1 - clippedX0 > EPS && clippedY1 - clippedY0 > EPS) {
                        rectangles += Rect(clippedX0, clippedX1, clippedY0, clippedY1)
                        subtracted++
                        if (touched) clipped++
                    } else {
                        rejected += RejectedOpening(
                            opening.kind,
                            openingIndex,
                            OpeningRejection.OUTSIDE_WALL,
                        )
                    }
                }
            }
        }

        val openingArea = unionArea(rectangles)
        val gross = length * height
        val net = (gross - openingArea).coerceAtLeast(0.0)
        lines += WallSurfaceLine(
            wallIndex = wallIndex,
            lengthM = length.toFloat(),
            heightM = height.toFloat(),
            grossAreaM2 = gross.toFloat(),
            openingAreaM2 = openingArea.toFloat(),
            netAreaM2 = net.toFloat(),
            openingsSubtracted = subtracted,
            fullyOpen = net <= EPS && gross > EPS,
        )
    }

    return WallSurfaceReport(
        lines = lines,
        totalLengthM = lines.sumOf { it.lengthM.toDouble() }.toFloat(),
        grossAreaM2 = lines.sumOf { it.grossAreaM2.toDouble() }.toFloat(),
        openingAreaM2 = lines.sumOf { it.openingAreaM2.toDouble() }.toFloat(),
        netAreaM2 = lines.sumOf { it.netAreaM2.toDouble() }.toFloat(),
        rejected = rejected,
        clippedOpenings = clipped,
    )
}

/** An axis-aligned rectangle in a wall's own coordinates: x along its length, y up. */
private data class Rect(val x0: Double, val x1: Double, val y0: Double, val y1: Double)

/**
 * Exact union area of axis-aligned rectangles.
 *
 * Sweeping the x edges into strips and merging the y spans inside each one is exact for this
 * shape family and has no tolerance to tune: overlapping windows subtract once, and the answer
 * does not depend on the order the openings were measured in.
 */
private fun unionArea(rectangles: List<Rect>): Double {
    if (rectangles.isEmpty()) return 0.0
    val edges = rectangles.flatMap { listOf(it.x0, it.x1) }.distinct().sorted()
    var area = 0.0
    for (index in 0 until edges.size - 1) {
        val left = edges[index]
        val right = edges[index + 1]
        if (right - left <= 0.0) continue
        val spans = rectangles
            .filter { it.x0 <= left && it.x1 >= right }
            .map { it.y0 to it.y1 }
            .sortedBy { it.first }
        var covered = 0.0
        var currentStart = Double.NaN
        var currentEnd = Double.NaN
        for ((start, end) in spans) {
            if (currentStart.isNaN()) {
                currentStart = start
                currentEnd = end
                continue
            }
            if (start <= currentEnd) {
                if (end > currentEnd) currentEnd = end
            } else {
                covered += currentEnd - currentStart
                currentStart = start
                currentEnd = end
            }
        }
        if (!currentStart.isNaN()) covered += currentEnd - currentStart
        area += covered * (right - left)
    }
    return area
}
