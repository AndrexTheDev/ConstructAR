/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.model.FloorplanSketch

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
 * @param heightM the assumed floor-to-ceiling height. It is **not** scaled: it is the assumption
 *   the drywall estimate is made under, not a length the session measured.
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
