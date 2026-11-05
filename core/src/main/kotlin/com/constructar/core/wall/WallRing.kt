/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.model.FloorplanSketch

/**
 * The range a *stated* room height may fall in, in metres.
 *
 * Below two metres there is no room to stand a ceiling outlet over; above 4.50 m the trade answer
 * is not a stud wall any more but a scaffold. Both ends are refusals rather than clamps: a height
 * outside them is a typo or a measurement to take again, and clamping it would print a room that
 * does not exist with the authority of one that does.
 */
const val MIN_ROOM_HEIGHT_M: Float = 2.00f

/** The upper end of [MIN_ROOM_HEIGHT_M]'s range, in metres. */
const val MAX_ROOM_HEIGHT_M: Float = 4.50f

/**
 * The room heights German flats are actually built with, in metres.
 *
 * The list is here, not in the screen, for the same reason the clamp is: which heights a take-off
 * may be made for is a trade fact. 2.40 to 2.80 m in ten-centimetre steps plus 3.00 m for the
 * post-war blocks; every one of them is inside [MIN_ROOM_HEIGHT_M]..[MAX_ROOM_HEIGHT_M], and
 * [DEFAULT_WALL_HEIGHT_M] is one of them, so the default is always a height the operator could
 * have chosen (finding 227).
 */
val STANDARD_ROOM_HEIGHTS_M: List<Float> = listOf(2.40f, 2.50f, 2.60f, 2.70f, 2.80f, 3.00f)

/**
 * The room height a plan may be built with: the stated one, or `null` for "nobody said".
 *
 * Kept next to [ringWalls] because the height is what every wall area is made of. A number that is
 * not real, or outside [MIN_ROOM_HEIGHT_M]..[MAX_ROOM_HEIGHT_M], is not a height anyone stated -
 * it is noise in a file - and the caller falls back to [DEFAULT_WALL_HEIGHT_M] *and says so*
 * (finding 227).
 */
fun statedRoomHeight(heightM: Float?): Float? {
    val value = heightM ?: return null
    if (!value.isFinite()) return null
    if (value < MIN_ROOM_HEIGHT_M || value > MAX_ROOM_HEIGHT_M) return null
    return value
}

/**
 * The room's stored outline as walls, one per edge, **closing the loop**.
 *
 * The closing edge is a wall like any other: a 4 × 3 m room is 14 m of wall, not 11 m, and 3 m of
 * missing wall is a board and a half on the purchase order. That is the whole reason this lives in
 * `:core` and not in the screen that shows it - the module rule is that `:app` never decides a
 * geometric question, and "the ring closes" is one.
 *
 * Two things produce no walls at all, deliberately:
 *
 *  - an outline that is not [FloorplanSketch.isDrawable] (fewer than three corners, or a ring the
 *    scan never closed): a room with a doorway in it is not a room yet, and the card already
 *    offers to close the gap by hand;
 *  - a scale or height that is not a positive, finite number: a wall that cannot be computed is
 *    missing rather than zero, because "0.00 m²" reads as a measurement.
 *
 * An edge that collapses to a point under the scale (a duplicated corner, a zero-length trace) is
 * dropped instead of being reported as 0.00 m of board.
 *
 * @param metresScale the project's calibration factor - a measured length, applied here and
 *   nowhere else on this path
 * @param heightM the floor-to-ceiling height every wall is built at. It is **not** scaled: it is
 *   not a length the session measured either - it is the height the operator stated, or
 *   [DEFAULT_WALL_HEIGHT_M] when nobody did, and `WallPlan.wallHeightAssumed` is what tells the
 *   two apart (finding 227).
 */

fun ringWalls(
    sketch: FloorplanSketch?,
    metresScale: Float = 1f,
    heightM: Float = DEFAULT_WALL_HEIGHT_M,
): List<MeasuredWall> {
    if (sketch == null || !sketch.isDrawable) return emptyList()
    if (!metresScale.isFinite() || metresScale <= 0f) return emptyList()
    if (!heightM.isFinite() || heightM <= 0f) return emptyList()
    val points = sketch.points
    if (points.size < 3) return emptyList()
    val walls = ArrayList<MeasuredWall>(points.size)
    for (index in points.indices) {
        val from = points[index]
        val to = points[(index + 1) % points.size]
        val wall = MeasuredWall(
            startXM = from.xMeters * metresScale,
            startZM = from.zMeters * metresScale,
            endXM = to.xMeters * metresScale,
            endZM = to.zMeters * metresScale,
            heightM = heightM,
        )
        if (wall.lengthM > 0f) walls += wall
    }
    return walls
}
