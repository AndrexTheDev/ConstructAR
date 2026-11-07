/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import kotlin.test.Test
import kotlin.test.assertEquals

class DrywallReinforcementTest {
    private val wall = MeasuredWall(0f, 0f, 4f, 0f, 2.5f)

    @Test
    fun `door studs move from CW to two millimetre UA`() {
        val openings = mapOf(0 to listOf(WallOpening(OpeningKind.DOOR, 0.5f, 1.0f, 0f, 2.01f)))
        val estimate = estimateDrywall(wallSurface(listOf(wall), openings), openings = openings)
        assertEquals(5, estimate.studs)
        assertEquals(2, estimate.uaStuds)
        assertEquals(2, estimate.uaProfileThicknessMm)
        assertEquals(5.5f, estimate.uaProfileMeters, 1e-4f)
        assertEquals(13.75f, estimate.studProfileMeters, 1e-4f)
    }

    @Test
    fun `a window does not replace door reinforcement studs`() {
        val openings = mapOf(0 to listOf(WallOpening(OpeningKind.WINDOW, 0.5f, 1.0f, 0.9f, 1.4f)))
        val estimate = estimateDrywall(wallSurface(listOf(wall), openings), openings = openings)
        assertEquals(7, estimate.studs)
        assertEquals(0, estimate.uaStuds)
        assertEquals(0f, estimate.uaProfileMeters)
    }

    @Test
    fun `overlapping doors never replace one stud twice`() {
        val openings = mapOf(
            0 to listOf(
                WallOpening(OpeningKind.DOOR, 0.5f, 1.0f, 0f, 2.01f),
                WallOpening(OpeningKind.PASSAGE, 1.25f, 1.0f, 0f, 2.01f),
            ),
        )
        val estimate = estimateDrywall(wallSurface(listOf(wall), openings), openings = openings)
        assertEquals(4, estimate.studs)
        assertEquals(3, estimate.uaStuds)
    }

    @Test
    fun `CW to UA replacement never changes opening subtraction or board quantities`() {
        val openings = mapOf(0 to listOf(WallOpening(OpeningKind.DOOR, 0.5f, 1.0f, 0f, 2.01f)))
        val surface = wallSurface(listOf(wall), openings)
        val areaOnly = estimateDrywall(surface)
        val reinforced = estimateDrywall(surface, openings = openings)

        assertEquals(areaOnly.netAreaM2, reinforced.netAreaM2)
        assertEquals(areaOnly.boardsBare, reinforced.boardsBare)
        assertEquals(areaOnly.boards, reinforced.boards)
        assertEquals(areaOnly.trackProfileMeters, reinforced.trackProfileMeters)
    }

    @Test
    fun `an opening clipped at the wall end affects only existing studs`() {
        val openings = mapOf(0 to listOf(WallOpening(OpeningKind.DOOR, 3.5f, 2.0f, 0f, 2.01f)))
        val estimate = estimateDrywall(wallSurface(listOf(wall), openings), openings = openings)
        assertEquals(6, estimate.studs)
        assertEquals(1, estimate.uaStuds)
    }
}
