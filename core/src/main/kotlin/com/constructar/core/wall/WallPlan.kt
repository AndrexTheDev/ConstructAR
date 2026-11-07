/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.calib.CalibrationTransform
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

        fun passage(wallIndex: Int, offsetM: Float = 0f): PlacedOpening = PlacedOpening(
            wallIndex = wallIndex,
            kind = OpeningKind.PASSAGE,
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

/** One nudge of the spot: a tenth of the wall, which is about a door's width on a 4 m run. */
const val SPOT_STEP: Float = 0.10f

/**
 * The spot a nudge moves the *next* opening to, kept inside the wall.
 *
 * A tap is not a control a screen reader can use, and it is not a precise one for a gloved finger
 * either. The elevation offers the same choice as two accessibility actions ("left", "right") that
 * move a tenth of the wall at a time, and this is the arithmetic behind them - in `:core` for the
 * same reason the clamping is (finding 223): a screen that stepped its own fractions would step off
 * the end of the wall, and the opening it placed would be clipped by `wallSurface` for a reason
 * nobody on the screen could see.
 */
fun stepOpeningFraction(fraction: Float, steps: Int, stepFraction: Float = SPOT_STEP): Float {
    // A fraction that is not a number has no position; the middle of the wall is the one answer
    // that cannot be off it, and the caller's step still applies from there.
    val start = (if (fraction.isFinite()) fraction else 0.5f).coerceIn(0f, 1f)
    // A step that is not a positive number is not a step: the spot stays where it is. Substituting
    // the default would move an opening the operator did not ask to move, and this is the code
    // path an accessibility action runs.
    if (!stepFraction.isFinite() || stepFraction <= 0f) return start
    return (start + steps * stepFraction).coerceIn(0f, 1f)
}

/**
 * An opening as a rectangle in its wall's own frame, every edge a fraction of the wall.
 *
 * A screen draws on a canvas whose size it learns at layout time and which has nothing to do with
 * metres; a take-off needs metres. Rather than let the screen divide by a length it was handed
 * (which is geometry, and geometry is not a screen's job), the clip is done once here: the same
 * clip `wallSurface` applies before subtracting, so what is drawn and what is priced cannot
 * disagree about where the hole is.
 *
 * `bottomFraction` counts **from the floor** (0) to the top of the wall (1), which is the way a
 * canvas is *not* laid out - the screen flips it, and that flip is the one line of arithmetic it
 * owns.
 */
data class OpeningRect(
    val leftFraction: Float,
    val rightFraction: Float,
    val bottomFraction: Float,
    val topFraction: Float,
) {
    /** Width as a fraction of the wall - 0 for a degenerate rectangle, which is never drawn. */
    val widthFraction: Float get() = (rightFraction - leftFraction).coerceAtLeast(0f)

    /** Height as a fraction of the wall - 0 for a degenerate rectangle. */
    val heightFraction: Float get() = (topFraction - bottomFraction).coerceAtLeast(0f)

    val isVisible: Boolean get() = widthFraction > 0f && heightFraction > 0f
}

/**
 * This opening on a wall of [wallLengthM] metres and [wallHeightM] metres, or `null` when nothing
 * of it would land on the wall.
 */
fun WallOpening.rectOn(wallLengthM: Float, wallHeightM: Float): OpeningRect? {
    if (!wallLengthM.isFinite() || !wallHeightM.isFinite()) return null
    if (wallLengthM <= 0f || wallHeightM <= 0f) return null
    if (!offsetM.isFinite() || !widthM.isFinite() || !sillHeightM.isFinite() || !heightM.isFinite()) {
        return null
    }
    val left = (offsetM / wallLengthM).coerceIn(0f, 1f)
    val right = ((offsetM + widthM) / wallLengthM).coerceIn(0f, 1f)
    val bottom = (sillHeightM / wallHeightM).coerceIn(0f, 1f)
    val top = ((sillHeightM + heightM) / wallHeightM).coerceIn(0f, 1f)
    val rect = OpeningRect(left, right, bottom, top)
    return if (rect.isVisible) rect else null
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
    /**
     * The floor-to-ceiling height every wall in this plan was built at, in metres.
     *
     * One number for the drawing, the area, the boards, the studs and the ceiling zone, because
     * two of them is how a picture ends up showing a 2.80 m wall while the purchase order is for a
     * 2.50 m one (finding 227).
     */
    val wallHeightM: Float,
    /**
     * True when [wallHeightM] is the trade default because the room's own height is not stated.
     *
     * The screen prints the height either way; this flag is the difference between *"2.50 m"* and
     * *"2.50 m, assumed"*, and a take-off that prints an assumption as a measurement is the one
     * the fitter prices the job on.
     */
    val wallHeightAssumed: Boolean,
    /** How many openings were subtracted. */
    val openingsRecorded: Int,
    /** The openings themselves, in the order the project stores them - what a screen draws. */
    val openings: List<PlacedOpening> = emptyList(),
    /**
     * Openings whose wall is not in the ring any more.
     *
     * A `wallIndex` is an index into `ringWalls`, and the ring is rebuilt from the sketch on every
     * read: close a gap by hand, add a corner, or reload a room whose outline changed, and an
     * opening recorded against wall 5 of a four-wall room is a rectangle that describes no wall.
     * `wallSurface` ignores it - correctly, it cannot subtract from a wall that does not exist -
     * and the consequence is a door that is still in the project file, still printed in the
     * opening count, and no longer in the area. Printing the number is the least a screen can do;
     * silently losing a door from a take-off is how an order comes up short (finding 225).
     */
    val openingsUnplaced: Int = 0,
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
    val calibration = CalibrationTransform.of(project.calibrationScale)
    // A manual height is already a physical dimension and takes precedence. The automatically
    // measured height is raw ARCore evidence, so it crosses the same calibration as every route
    // point. The trade default is not a measurement and is therefore never scaled.
    val manualHeight = statedRoomHeight(project.wallHeightM)
    val measuredHeight = statedRoomHeight(project.measuredRoomHeightM)
        ?.let(calibration::distance)
        ?.let(::statedRoomHeight)
    val wallHeightM = manualHeight ?: measuredHeight ?: DEFAULT_WALL_HEIGHT_M
    val walls = ringWalls(project.sketch?.let(calibration::sketch), metresScale = 1f, heightM = wallHeightM)
    val openings = openingsByWall(project.openings)
    val surface = wallSurface(walls, openings)
    val drywall = estimateDrywall(surface, spec, openings)
    val nodes = project.routes.flatMap { it.nodes }
    val relative = relativeNodes(
        nodes = nodes,
        sessionFloorHeights = project.sessionFloorHeights,
        metresScale = 1f,
        legacyFloorHeightM = project.floorHeightM,
    )
    val points: List<Vec3> = relative.points.map(calibration::point)
    // The ceiling zone is fixed by the same height the walls were built at - not by
    // `walls.first().heightM`, which is the same number only as long as one caller remembers to
    // pass it, and not by the default either: a room estimated at 2.80 m has its ceiling outlets
    // at 2.65 m (finding 227).
    val ceilingHeightM = wallHeightM
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
        wallHeightM = wallHeightM,
        wallHeightAssumed = manualHeight == null && measuredHeight == null,
        openingsRecorded = project.openings.size,
        openings = project.openings,
        openingsUnplaced = project.openings.count { it.wallIndex !in walls.indices },
        pointCount = points.size,
        foreignSessionPoints = relative.foreignSessionCount,
        noFloorPoints = relative.noFloorCount,
        invalidPoints = relative.invalidCount,
        sessionCount = project.sessionFloorHeights.size,
    )
}
