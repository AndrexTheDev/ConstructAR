/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.math.Vec3
import com.constructar.core.model.ProjectRecord
import kotlinx.serialization.Serializable

/**
 * German trade dimensions, because a default that has to be corrected on every job is worse than
 * none: a DIN 18101 door leaf of 885 mm in a 2010 mm reveal, a 1.20 × 1.40 m window with a 0.90 m
 * parapet (DIN 18015's socket line stays clear below it).
 *
 * File-level rather than companion constants: a primary-constructor default that names a companion
 * member of its own class is legal Kotlin only because the value is a compile-time constant, and
 * a rule that depends on that subtlety is a rule one refactor away from a build failure in a
 * sandbox that cannot compile.
 */
const val DOOR_WIDTH_M: Float = 0.885f
const val DOOR_HEIGHT_M: Float = 2.01f
const val WINDOW_WIDTH_M: Float = 1.20f
const val WINDOW_HEIGHT_M: Float = 1.40f
const val WINDOW_SILL_M: Float = 0.90f

/**
 * An opening the operator placed on a wall of the room's ring.
 *
 * The rectangle itself - kind, offset along the wall, width, sill height, height - is [WallOpening],
 * which `wallSurface` already subtracts as a *union*: two windows that overlap take board off the
 * wall once, not twice. This type only adds what a project file needs and a rectangle does not
 * have: **which wall of the ring** the opening sits on.
 *
 * No Clipper2, and no polygon library: the openings are axis-aligned rectangles on an
 * axis-aligned wall, and `wallSurface` computes their union exactly (sweep over the x edges the
 * openings introduce, merge the y spans inside each strip). That is the same boolean difference a
 * general clipping library would return for this input - without a third-party dependency in a
 * repository that has none, and without a floating-point tolerance nobody can argue with.
 *
 * @param wallIndex index into `ringWalls(...)`, i.e. edge `i` runs from corner `i` to corner `i+1`
 */
@Serializable
data class PlacedOpening(
    val wallIndex: Int,
    val kind: OpeningKind = OpeningKind.DOOR,
    /** Distance from the wall's start corner to the opening's left edge, metres. */
    val offsetM: Float = 0f,
    val widthM: Float = DOOR_WIDTH_M,
    /** Height of the opening's lower edge above the finished floor, metres. */
    val sillHeightM: Float = 0f,
    val heightM: Float = DOOR_HEIGHT_M,
) {

    /** The rectangle `wallSurface` subtracts. */
    val opening: WallOpening
        get() = WallOpening(
            kind = kind,
            offsetM = offsetM,
            widthM = widthM,
            sillHeightM = sillHeightM,
            heightM = heightM,
        )

    companion object {
        // Dimensions from the trade constants above, so a default door and a default window are
        // the same numbers everywhere the screen offers to place one.
        fun door(wallIndex: Int, offsetM: Float = 0f): PlacedOpening = PlacedOpening(
            wallIndex = wallIndex,
            kind = OpeningKind.DOOR,
            offsetM = offsetM,
            widthM = DOOR_WIDTH_M,
            sillHeightM = 0f,
            heightM = DOOR_HEIGHT_M,
        )

        fun window(wallIndex: Int, offsetM: Float = 0f): PlacedOpening = PlacedOpening(
            wallIndex = wallIndex,
            kind = OpeningKind.WINDOW,
            offsetM = offsetM,
            widthM = WINDOW_WIDTH_M,
            sillHeightM = WINDOW_SILL_M,
            heightM = WINDOW_HEIGHT_M,
        )
    }
}

/** The per-wall map `wallSurface` expects, from the flat list a project file stores. */
fun openingsByWall(openings: List<PlacedOpening>): Map<Int, List<WallOpening>> {
    if (openings.isEmpty()) return emptyMap()
    val grouped = LinkedHashMap<Int, MutableList<WallOpening>>()
    for (placed in openings) {
        if (placed.wallIndex < 0) continue
        grouped.getOrPut(placed.wallIndex) { ArrayList() } += placed.opening
    }
    return grouped
}

/**
 * The opening a tap at [fraction] of the wall would place, kept inside the wall.
 *
 * A door is 0.885 m wide and a wall can be 0.60 m: the placement is clamped so the rectangle
 * starts at or after the wall's start and ends at or before its end, and an opening wider than the
 * whole wall starts at 0 (where `wallSurface` will clip it and count the clip, which is the honest
 * report - the operator placed something that does not fit).
 *
 * @param fraction 0..1 along the wall, from the wall's start corner
 */
fun openingOffsetForTap(fraction: Float, wallLengthM: Float, widthM: Float): Float {
    if (!fraction.isFinite() || !wallLengthM.isFinite() || !widthM.isFinite()) return 0f
    if (wallLengthM <= 0f) return 0f
    val centred = fraction.coerceIn(0f, 1f) * wallLengthM - widthM / 2f
    val latest = wallLengthM - widthM
    return if (latest <= 0f) 0f else centred.coerceIn(0f, latest)
}

/**
 * The wall screen's whole answer: what the room is, what it costs in board, which mounting heights
 * may be stated - and, when they may not, why.
 *
 * Everything here comes from a `:core` function; `:app` only formats it. The two counts exist so a
 * screen can be honest about a room measured across several sessions instead of printing a zone
 * count that silently covers half the points (finding 217).
 */
data class WallPlan(
    val walls: List<MeasuredWall>,
    val surface: WallSurfaceReport,
    val drywall: DrywallEstimate,
    /** Zone assignments for the points whose session has a measured floor, or `null` if none. */
    val installations: InstallationPlan?,
    /** The most recent floor height the project carries, when it has one. */
    val floorHeightM: Float?,
    /** How many openings were subtracted. */
    val openingsRecorded: Int,
    /** Points that were classified into a zone. */
    val pointCount: Int,
    /** Points from a session whose floor was never measured - not classifiable, not dropped. */
    val foreignSessionPoints: Int,
    /** Points that could not be classified because no floor exists at all yet. */
    val noFloorPoints: Int,
    /** Points whose stored position was not a number. */
    val invalidPoints: Int,
    /** Distinct AR sessions the nodes came from. */
    val sessionCount: Int,
) {
    /** True when there was a closed room to measure. */
    val valid: Boolean get() = walls.isNotEmpty()

    /**
     * True when every measured point could be resolved - the only case in which the zone counts
     * describe the whole room rather than the part of it measured in one session.
     */
    val zonesCoverEveryPoint: Boolean
        get() = foreignSessionPoints == 0 && noFloorPoints == 0 && invalidPoints == 0
}

/**
 * Assembles the plan for a project - the one place the wall screen's numbers come from.
 *
 * `spec` is the drywall specification the estimate is made under; the screen prints it, because a
 * purchase order without its assumptions is a number to argue with.
 */
fun wallPlan(project: ProjectRecord, spec: DrywallSpec = DrywallSpec()): WallPlan {
    val scale = project.calibrationScale.toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f
    val walls = ringWalls(project.sketch, metresScale = scale)
    val openings = openingsByWall(project.openings)
    val surface = wallSurface(walls, openings)
    val drywall = estimateDrywall(surface, spec)
    val nodes = project.routes.flatMap { it.nodes }
    val relative = relativeNodes(
        nodes = nodes,
        sessionFloorHeights = project.sessionFloorHeights,
        metresScale = scale,
        legacyFloorHeightM = project.floorHeightM,
    )
    val points: List<Vec3> = relative.points
    // The ceiling zone is fixed by the height the walls were measured at, not by the assumed
    // default: a room estimated at 2.80 m has its ceiling outlets at 2.65 m.
    val ceilingHeightM = walls.firstOrNull()?.heightM ?: DEFAULT_WALL_HEIGHT_M
    val installations = if (points.isEmpty()) {
        null
    } else {
        classifyInstallations(points, ceilingHeightM = ceilingHeightM)
    }
    return WallPlan(
        walls = walls,
        surface = surface,
        drywall = drywall,
        installations = installations,
        floorHeightM = project.floorHeightM,
        openingsRecorded = project.openings.size,
        pointCount = points.size,
        foreignSessionPoints = relative.foreignSessionCount,
        noFloorPoints = relative.noFloorCount,
        invalidPoints = relative.invalidCount,
        sessionCount = project.sessionFloorHeights.size,
    )
}
