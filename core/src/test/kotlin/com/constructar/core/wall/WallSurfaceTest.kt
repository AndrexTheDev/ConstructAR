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
 * Net wall area is the number the board order rests on, so it is tested where it can go wrong.
 *
 * The cases that matter are not "a door is subtracted" - that is arithmetic - but the four ways a
 * plausible implementation under-orders: two openings that overlap (subtracted twice), an opening
 * that reaches past the end of its wall (clipped silently), an opening taller than the wall
 * (clipped to nothing), and a wall that is nothing but opening (negative net area).
 */
class WallSurfaceTest {

    private fun room(): List<MeasuredWall> = listOf(
        MeasuredWall(0f, 0f, 4f, 0f),
        MeasuredWall(4f, 0f, 4f, 3f),
        MeasuredWall(4f, 3f, 0f, 3f),
        MeasuredWall(0f, 3f, 0f, 0f),
    )

    @Test
    fun `a bare room is its gross area`() {
        val report = wallSurface(room())
        assertEquals(14f, report.totalLengthM, 1e-4f)
        assertEquals(35f, report.grossAreaM2, 1e-4f)
        assertEquals(35f, report.netAreaM2, 1e-4f)
        assertEquals(0f, report.openingAreaM2, 1e-4f)
        assertEquals(4, report.lines.size)
        assertTrue(report.valid)
        assertTrue(report.rejected.isEmpty())
        assertEquals(0, report.clippedOpenings)
        report.lines.forEach { line ->
            assertEquals(2.5f, line.heightM, 1e-4f)
            assertEquals(0, line.openingsSubtracted)
            assertFalse(line.fullyOpen)
        }
    }

    @Test
    fun `a door and a window come off the walls they are in`() {
        val report = wallSurface(
            room(),
            mapOf(
                0 to listOf(WallOpening(OpeningKind.DOOR, 1f, 0.9f, 0f, 2.05f)),
                1 to listOf(WallOpening(OpeningKind.WINDOW, 0.5f, 1.2f, 0.9f, 1.4f)),
            ),
        )
        assertEquals(1.845f, report.lines[0].openingAreaM2, 1e-4f)
        assertEquals(8.155f, report.lines[0].netAreaM2, 1e-4f)
        assertEquals(1.68f, report.lines[1].openingAreaM2, 1e-4f)
        assertEquals(5.82f, report.lines[1].netAreaM2, 1e-4f)
        assertEquals(3.525f, report.openingAreaM2, 1e-4f)
        assertEquals(31.475f, report.netAreaM2, 1e-3f)
        assertEquals(1, report.lines[0].openingsSubtracted)
        assertEquals(1, report.lines[1].openingsSubtracted)
    }

    @Test
    fun `two windows that overlap are subtracted once`() {
        // Both windows are 1.2 m wide at 1.0 m and 1.5 m, so they share 0.7 m of the wall: the
        // union is 1.7 m wide, not 2.4 m. Subtracting the sum would take board off a wall that is
        // still there and leave the fitter short (the under-order this case exists for).
        val report = wallSurface(
            listOf(MeasuredWall(0f, 0f, 4f, 0f)),
            mapOf(
                0 to listOf(
                    WallOpening(OpeningKind.WINDOW, 1f, 1.2f, 1f, 1f),
                    WallOpening(OpeningKind.WINDOW, 1.5f, 1.2f, 1f, 1f),
                ),
            ),
        )
        assertEquals(1.7f, report.lines[0].openingAreaM2, 1e-4f)
        assertEquals(8.3f, report.lines[0].netAreaM2, 1e-4f)
        assertEquals(2, report.lines[0].openingsSubtracted)
    }

    @Test
    fun `a window inside a window is one hole`() {
        val report = wallSurface(
            listOf(MeasuredWall(0f, 0f, 4f, 0f)),
            mapOf(
                0 to listOf(
                    WallOpening(OpeningKind.WINDOW, 1f, 2f, 0.9f, 1.4f),
                    WallOpening(OpeningKind.WINDOW, 1.4f, 0.6f, 1f, 0.8f),
                ),
            ),
        )
        assertEquals(2.8f, report.lines[0].openingAreaM2, 1e-4f)
        assertEquals(7.2f, report.lines[0].netAreaM2, 1e-4f)
    }

    @Test
    fun `the subtraction does not depend on the order the openings arrived in`() {
        val first = WallOpening(OpeningKind.WINDOW, 1f, 2f, 0.9f, 1.4f)
        val second = WallOpening(OpeningKind.WINDOW, 1.4f, 0.6f, 1f, 0.8f)
        val forward = wallSurface(listOf(MeasuredWall(0f, 0f, 4f, 0f)), mapOf(0 to listOf(first, second)))
        val backward = wallSurface(listOf(MeasuredWall(0f, 0f, 4f, 0f)), mapOf(0 to listOf(second, first)))
        assertEquals(forward.netAreaM2, backward.netAreaM2, 1e-4f)
    }

    @Test
    fun `an opening past the end of its wall is clipped and counted`() {
        val report = wallSurface(
            listOf(MeasuredWall(0f, 0f, 4f, 0f)),
            mapOf(0 to listOf(WallOpening(OpeningKind.WINDOW, 3.5f, 1f, 0.5f, 2f))),
        )
        assertEquals(1f, report.lines[0].openingAreaM2, 1e-4f)
        assertEquals(9f, report.lines[0].netAreaM2, 1e-4f)
        assertEquals(1, report.clippedOpenings)
        assertTrue(report.rejected.isEmpty())
    }

    @Test
    fun `an opening taller than its wall is clipped to the wall`() {
        val report = wallSurface(
            listOf(MeasuredWall(0f, 0f, 4f, 0f, 2f)),
            mapOf(0 to listOf(WallOpening(OpeningKind.DOOR, 0.5f, 1f, 0f, 2.05f))),
        )
        assertEquals(2f, report.lines[0].openingAreaM2, 1e-4f)
        assertEquals(6f, report.lines[0].netAreaM2, 1e-4f)
        assertEquals(1, report.clippedOpenings)
    }

    @Test
    fun `an opening that cannot be subtracted is reported with its reason`() {
        val report = wallSurface(
            listOf(MeasuredWall(0f, 0f, 4f, 0f), MeasuredWall(0f, 2f, 3f, 2f)),
            mapOf(
                0 to listOf(
                    WallOpening(OpeningKind.WINDOW, 1f, 0f, 1f, 1f),
                    WallOpening(OpeningKind.WINDOW, 5f, 1f, 1f, 1f),
                    WallOpening(OpeningKind.WINDOW, 1f, 1f, 2.6f, 0.5f),
                ),
            ),
        )
        assertEquals(3, report.rejected.size)
        assertEquals(OpeningRejection.NEGATIVE_SIZE, report.rejected[0].reason)
        assertEquals(OpeningRejection.OUTSIDE_WALL, report.rejected[1].reason)
        assertEquals(OpeningRejection.ABOVE_WALL, report.rejected[2].reason)
        assertEquals(0, report.rejected[0].index)
        assertEquals(1, report.rejected[1].index)
        assertEquals(OpeningKind.WINDOW, report.rejected[1].kind)
        assertEquals(17.5f, report.netAreaM2, 1e-4f)
    }

    @Test
    fun `a wall that is nothing but opening has no net area`() {
        val report = wallSurface(
            listOf(MeasuredWall(0f, 0f, 4f, 0f)),
            mapOf(0 to listOf(WallOpening(OpeningKind.PASSAGE, 0f, 4f, 0f, 2.5f))),
        )
        assertEquals(0f, report.lines[0].netAreaM2, 1e-4f)
        assertTrue(report.lines[0].fullyOpen)
        assertEquals(0f, report.netAreaM2, 1e-4f)
    }

    @Test
    fun `no walls is not a take-off`() {
        val report = wallSurface(emptyList())
        assertFalse(report.valid)
        assertTrue(report.lines.isEmpty())
        assertEquals(0f, report.grossAreaM2, 1e-4f)
        assertEquals(0f, report.netAreaM2, 1e-4f)
        assertEquals(0f, report.totalLengthM, 1e-4f)
    }

    @Test
    fun `a degenerate wall keeps its row and refuses its openings`() {
        val report = wallSurface(
            listOf(MeasuredWall(2f, 2f, 2f, 2f)),
            mapOf(0 to listOf(WallOpening(OpeningKind.DOOR, 0f, 0.9f, 0f, 2f))),
        )
        assertEquals(1, report.lines.size)
        assertEquals(0f, report.lines[0].lengthM, 1e-4f)
        assertEquals(0f, report.lines[0].grossAreaM2, 1e-4f)
        assertEquals(1, report.rejected.size)
        assertEquals(OpeningRejection.OUTSIDE_WALL, report.rejected[0].reason)
        assertFalse(report.lines[0].fullyOpen)
    }

    @Test
    fun `an opening on a wall with no height is refused rather than subtracted`() {
        val report = wallSurface(
            listOf(MeasuredWall(0f, 0f, 4f, 0f, 0f)),
            mapOf(0 to listOf(WallOpening(OpeningKind.WINDOW, 1f, 1f, 0f, 1f))),
        )
        assertEquals(0f, report.lines[0].grossAreaM2, 1e-4f)
        assertEquals(1, report.rejected.size)
        assertEquals(OpeningRejection.ABOVE_WALL, report.rejected[0].reason)
    }
}
