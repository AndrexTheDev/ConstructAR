/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drywall take-off is a purchase order, so its numbers are asserted exactly.
 *
 * The room used throughout is the one the app is built for: 4 × 3 m, 2.50 m high, with a door and a
 * window. Its figures are worked out in the comments rather than produced by the implementation -
 * a test that asks the code what the answer is proves nothing.
 */
class DrywallEstimatorTest {

    private fun roomWithDoorAndWindow(): WallSurfaceReport = wallSurface(
        listOf(
            MeasuredWall(0f, 0f, 4f, 0f),
            MeasuredWall(4f, 0f, 4f, 3f),
            MeasuredWall(4f, 3f, 0f, 3f),
            MeasuredWall(0f, 3f, 0f, 0f),
        ),
        mapOf(
            0 to listOf(WallOpening(OpeningKind.DOOR, 1f, 0.9f, 0f, 2.05f)),
            1 to listOf(WallOpening(OpeningKind.WINDOW, 0.5f, 1.2f, 0.9f, 1.4f)),
        ),
    )

    @Test
    fun `a four by three metre room with a door and a window`() {
        val estimate = estimateDrywall(roomWithDoorAndWindow())
        assertTrue(estimate.valid)

        // 35 m² gross − 1.845 door − 1.68 window = 31.475 m² net.
        assertEquals(31.475f, estimate.netAreaM2, 1e-3f)
        // 31.475 / 3.125 = 10.07 → 11 boards bare; × 1.10 = 11.08 → 12 boards bought.
        assertEquals(11, estimate.boardsBare)
        assertEquals(12, estimate.boards)
        assertEquals(3.125f, estimate.boardAreaM2, 1e-4f)
        assertEquals(37.5f, estimate.boardCoverageM2, 1e-4f)
        // Screws follow the board that goes on the wall: 29 × 37.5 = 1087.5 → 1088.
        assertEquals(1088, estimate.screws)
        // Studs: 4 m at 62.5 cm is 6 intervals → 7 studs; 3 m is 4 intervals + 1 = 5. Two of each: 24.
        assertEquals(24, estimate.studs)
        // 24 × 2.50 m × 1.10 = 66.0 m of CW profile.
        assertEquals(66f, estimate.studProfileMeters, 1e-3f)
        // 2 × 14 m of run × 1.10 = 30.8 m of UW track.
        assertEquals(30.8f, estimate.trackProfileMeters, 1e-3f)
        assertEquals(2.5f, estimate.wallHeightM, 1e-4f)
        assertFalse(estimate.mixedHeights)
        assertEquals(0.1f, estimate.wasteFraction, 1e-4f)
        assertEquals(4, estimate.walls.size)
        assertEquals(7, estimate.walls[0].studs)
        assertEquals(5, estimate.walls[1].studs)
    }

    @Test
    fun `the waste is what separates the bare count from the order`() {
        val bare = estimateDrywall(roomWithDoorAndWindow(), DrywallSpec(wasteFraction = 0f))
        assertEquals(11, bare.boards)
        assertEquals(31.475f, bare.netAreaM2, 1e-3f)
        // 29 screws per square metre of the 34.375 m² that 11 boards cover.
        assertEquals(997, bare.screws)
        assertEquals(60f, bare.studProfileMeters, 1e-3f)
        assertEquals(28f, bare.trackProfileMeters, 1e-3f)

        val withWaste = estimateDrywall(roomWithDoorAndWindow())
        assertTrue(withWaste.boards > bare.boards)
        assertTrue(withWaste.screws > bare.screws)
    }

    @Test
    fun `a stud spacing of half a board puts a stud under every board edge`() {
        // The default is 62.5 cm = half of 1.25 m, which is why a 4 m wall is 7 studs and not 6.
        val oneWall = wallSurface(listOf(MeasuredWall(0f, 0f, 4f, 0f)))
        assertEquals(7, estimateDrywall(oneWall).studs)
        val wider = estimateDrywall(oneWall, DrywallSpec(studSpacingM = 1.25f))
        assertEquals(4, wider.studs)
    }

    @Test
    fun `a wall below one stud spacing still gets two studs`() {
        val stub = wallSurface(listOf(MeasuredWall(0f, 0f, 0.4f, 0f)))
        val estimate = estimateDrywall(stub)
        assertEquals(2, estimate.studs)
        // 2 × 2.5 m × 1.1 = 5.5 m of profile for 0.4 m of wall: the ends still need metal.
        assertEquals(5.5f, estimate.studProfileMeters, 1e-3f)
    }

    @Test
    fun `mixed wall heights are flagged, because board edges stop lining up`() {
        val report = wallSurface(
            listOf(MeasuredWall(0f, 0f, 4f, 0f, 2.5f), MeasuredWall(0f, 2f, 4f, 2f, 3f)),
        )
        val estimate = estimateDrywall(report)
        assertTrue(estimate.mixedHeights)
        assertEquals(3f, estimate.wallHeightM, 1e-4f)
        assertEquals(22f, estimate.netAreaM2, 1e-3f)
    }

    @Test
    fun `no walls is no take-off and it says so`() {
        val estimate = estimateDrywall(wallSurface(emptyList()))
        assertFalse(estimate.valid)
        assertEquals(0, estimate.boards)
        assertEquals(0, estimate.boardsBare)
        assertEquals(0, estimate.studs)
        assertEquals(0, estimate.screws)
        assertTrue(estimate.walls.isEmpty())
    }

    @Test
    fun `a spec that cannot be built refuses rather than divides by zero`() {
        val report = roomWithDoorAndWindow()
        val badSpacing = estimateDrywall(report, DrywallSpec(studSpacingM = 0f))
        assertFalse(badSpacing.valid)
        assertEquals(0, badSpacing.studs)
        assertEquals(0, badSpacing.boards)

        val badBoard = estimateDrywall(report, DrywallSpec(boardWidthM = 0f))
        assertFalse(badBoard.valid)
        assertEquals(0, badBoard.boards)

        val negativeWaste = estimateDrywall(report, DrywallSpec(wasteFraction = -0.1f))
        assertFalse(negativeWaste.valid)
        assertFalse(DrywallSpec(studSpacingM = 0f).valid)
        assertTrue(DrywallSpec().valid)
    }

    @Test
    fun `the default board is one wall height of standard gypsum`() {
        // 2500 × 1250 × 12.5 mm is the board a German fitter buys, and 2.50 m is the wall height
        // the estimator assumes when a wall arrives without one.
        val spec = DrywallSpec()
        assertEquals(3.125f, spec.boardAreaM2, 1e-4f)
        assertEquals(2.5f, spec.boardHeightM, 1e-4f)
        assertEquals(DEFAULT_WALL_HEIGHT_M, spec.boardHeightM, 1e-4f)
        assertEquals(0.625f, spec.studSpacingM, 1e-4f)
        assertEquals(29f, spec.screwsPerSquareMetre, 1e-4f)
    }
}
