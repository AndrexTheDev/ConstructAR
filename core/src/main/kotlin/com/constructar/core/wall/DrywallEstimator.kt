/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The drywall take-off: boards, CW studs, UW tracks and screws for a measured room.
 *
 * The numbers here are the trade's, not an invention, and each one is stated with the reason it is
 * what it is - a material list is a purchase order, and a plausible-looking number is worse than a
 * missing one:
 *
 *  - **Board comes in 2.50 × 1.25 m (3.125 m²).** That is the standard gypsum board in Germany
 *    (DIN 18180 / EN 520, 12.5 mm, 2500 × 1250), which is also why [DEFAULT_WALL_HEIGHT_M] is
 *    2.50 m: one board is one wall height.
 *  - **Studs are spaced at 62.5 cm**, i.e. half a board width, so every board edge lands on metal.
 *    A run gets a stud at each end plus one per interval; a 4 m wall is 7 studs, not 6, because
 *    both ends need one.
 *  - **Tracks are twice the run** - one on the floor, one on the ceiling.
 *  - **29 screws per square metre** of board (single-layer, 25 mm, 25 cm spacing on studs, 30 cm on
 *    the perimeter). Counted against the *installed* board area, i.e. the boards that will actually
 *    be on the wall including the offcuts that are thrown away, because it is the board that is
 *    screwed, not the net wall.
 *  - **10 % waste on everything that is cut**: boards (a 2.50 m board cannot be reused after a
 *    0.9 m door has been cut out of it), studs and tracks. The waste is applied once, to the
 *    quantity, and never to a number that is *already* a count of purchased pieces.
 *
 * The estimate refuses rather than guesses: a spec with a zero board or zero stud spacing returns
 * `valid = false` with every number zero, and no wall returns the same, so a screen has something
 * to say instead of a take-off of nothing.
 */
data class DrywallSpec(
    val boardWidthM: Float = 1.25f,
    val boardHeightM: Float = 2.50f,
    val studSpacingM: Float = 0.625f,
    val screwsPerSquareMetre: Float = 29f,
    val wasteFraction: Float = 0.10f,
) {
    /** Area of one board, in square metres. */
    val boardAreaM2: Float get() = boardWidthM * boardHeightM

    /** Every dimension has to be positive, and the waste cannot be negative. */
    val valid: Boolean
        get() = boardWidthM > 0f && boardHeightM > 0f && studSpacingM > 0f &&
            screwsPerSquareMetre >= 0f && wasteFraction >= 0f
}

/** One wall's line in the take-off. */
data class DrywallWallLine(
    val wallIndex: Int,
    val lengthM: Float,
    val heightM: Float,
    /** Studs on this run: one at each end plus one per spacing interval. */
    val studs: Int,
    /** Stud profile to buy: studs × height, with waste. */
    val studProfileMeters: Float,
    /** UW track to buy for this run: floor + ceiling, with waste. */
    val trackProfileMeters: Float,
)

/** The whole take-off. */
data class DrywallEstimate(
    val netAreaM2: Float,
    /** Boards the area alone needs, before waste. */
    val boardsBare: Int,
    /** Boards to buy, waste included. This is the number that is ordered. */
    val boards: Int,
    val boardAreaM2: Float,
    /** Board that will be installed: [boards] × [boardAreaM2]. */
    val boardCoverageM2: Float,
    val studs: Int,
    val studProfileMeters: Float,
    val trackProfileMeters: Float,
    val screws: Int,
    /** Tallest wall: the board cutting height a fitter has to plan for. */
    val wallHeightM: Float,
    /** The walls are not all the same height, so board edges will not line up floor to ceiling. */
    val mixedHeights: Boolean,
    val wasteFraction: Float,
    val walls: List<DrywallWallLine>,
    val valid: Boolean,
)

private const val EPS = 1e-4

/**
 * Turns a [WallSurfaceReport] into a take-off under [spec].
 *
 * Boards and profiles are counted from the *net* area (what has to be boarded); screws from the
 * board that will be installed. Both are explained in the class KDoc - the distinction is the kind
 * of thing a later "simplification" deletes, and it changes the order by 5 %.
 */
fun estimateDrywall(
    surface: WallSurfaceReport,
    spec: DrywallSpec = DrywallSpec(),
): DrywallEstimate {
    if (!spec.valid || surface.lines.isEmpty()) {
        return DrywallEstimate(
            netAreaM2 = 0f,
            boardsBare = 0,
            boards = 0,
            boardAreaM2 = if (spec.valid) spec.boardAreaM2 else 0f,
            boardCoverageM2 = 0f,
            studs = 0,
            studProfileMeters = 0f,
            trackProfileMeters = 0f,
            screws = 0,
            wallHeightM = 0f,
            mixedHeights = false,
            wasteFraction = spec.wasteFraction,
            walls = emptyList(),
            valid = false,
        )
    }

    val walls = surface.lines.map { line ->
        val studs = if (line.lengthM <= EPS.toFloat()) {
            0
        } else {
            max(2, floor((line.lengthM / spec.studSpacingM).toDouble()).toInt() + 1)
        }
        DrywallWallLine(
            wallIndex = line.wallIndex,
            lengthM = line.lengthM,
            heightM = line.heightM,
            studs = studs,
            studProfileMeters = studs * line.heightM * (1f + spec.wasteFraction),
            trackProfileMeters = 2f * line.lengthM * (1f + spec.wasteFraction),
        )
    }

    val netArea = surface.netAreaM2.toDouble()
    val boardArea = spec.boardAreaM2.toDouble()
    val boardsBare = if (netArea > EPS) ceil(netArea / boardArea).toInt() else 0
    val boards = if (netArea > EPS) {
        ceil(netArea * (1.0 + spec.wasteFraction) / boardArea).toInt()
    } else {
        0
    }
    val coverage = boards * spec.boardAreaM2
    val screws = ceil(spec.screwsPerSquareMetre.toDouble() * coverage.toDouble()).toInt()

    val heights = surface.lines.map { it.heightM }.distinct()
    return DrywallEstimate(
        netAreaM2 = surface.netAreaM2,
        boardsBare = boardsBare,
        boards = boards,
        boardAreaM2 = spec.boardAreaM2,
        boardCoverageM2 = coverage,
        studs = walls.sumOf { it.studs },
        studProfileMeters = walls.sumOf { it.studProfileMeters.toDouble() }.toFloat(),
        trackProfileMeters = walls.sumOf { it.trackProfileMeters.toDouble() }.toFloat(),
        screws = screws,
        wallHeightM = heights.maxOrNull() ?: 0f,
        mixedHeights = heights.size > 1,
        wasteFraction = spec.wasteFraction,
        walls = walls,
        valid = true,
    )
}
