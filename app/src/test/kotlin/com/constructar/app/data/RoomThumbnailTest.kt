/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import com.constructar.nativebridge.FloorplanPayload
import com.constructar.nativebridge.PlanLoop
import com.constructar.nativebridge.PlanPoint
import com.constructar.nativebridge.PlanSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card picture is a *choice* about which loop of a plan is the room, and the case that matters
 * is the one where no loop is: a doorway leaves the ring open, and joining its ends would draw a
 * wall across the door.
 */
class RoomThumbnailTest {

    private fun loop(points: List<Pair<Float, Float>>, closed: Boolean = true) = PlanLoop(
        points = points.map { PlanPoint(it.first, it.second) },
        closed = closed,
    )

    private fun payload(loops: List<PlanLoop>, segments: List<PlanSegment> = emptyList()) = FloorplanPayload(
        valid = loops.any { it.closed },
        closed = loops.any { it.closed },
        areaSquareMeters = 12f,
        perimeterMeters = 14f,
        occupiedCells = 431,
        segments = segments,
        loops = loops,
    )

    private val room = loop(listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f))

    @Test
    fun `a closed room becomes an outline of its corners`() {
        val outline = RoomThumbnail.outline(payload(listOf(room)))

        assertTrue("a closed loop must produce an outline", outline != null)
        assertEquals(4, outline!!.size)
        assertEquals(0f, outline[0].x, 1e-6f)
        assertEquals(3f, outline[2].z, 1e-6f)
    }

    @Test
    fun `the largest closed loop is the room`() {
        val niche = loop(listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f))
        val outline = RoomThumbnail.outline(payload(listOf(niche, room)))!!

        // The 4 x 3 m room wins over the 1 x 1 m niche, whichever order they arrive in.
        assertEquals(4, outline.size)
        assertEquals(4f, outline[1].x, 1e-6f)
        val reversed = RoomThumbnail.outline(payload(listOf(room, niche)))!!
        assertEquals(4, reversed.size)
        assertEquals(4f, reversed[1].x, 1e-6f)
    }

    @Test
    fun `an open loop is never closed here`() {
        val doorway = loop(listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f), closed = false)

        assertNull(RoomThumbnail.outline(payload(listOf(doorway))))
        assertNull(RoomThumbnail.svg(payload(listOf(doorway))))
    }

    @Test
    fun `a bigger open loop never beats a smaller closed one`() {
        val hall = loop(listOf(0f to 0f, 12f to 0f, 12f to 1f, 0f to 1f), closed = false)
        val outline = RoomThumbnail.outline(payload(listOf(hall, room)))!!

        assertEquals(4, outline.size)
        assertEquals(4f, outline[1].x, 1e-6f)
    }

    @Test
    fun `no loops at all is no picture`() {
        assertNull(RoomThumbnail.svg(payload(emptyList())))
    }

    @Test
    fun `a loop of two points cannot be a room`() {
        assertNull(RoomThumbnail.outline(payload(listOf(loop(listOf(0f to 0f, 4f to 0f))))))
    }

    @Test
    fun `the svg is a standalone drawing of the outline`() {
        val svg = RoomThumbnail.svg(payload(listOf(room)))!!

        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("<polygon"))
        assertTrue(svg.contains("points="))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
        assertFalse("the export owns no words", svg.contains("<text"))
    }

    @Test
    fun `a room the renderer refuses produces no picture`() {
        // 4 mm across: below FloorplanSvg's 10 cm minimum, so the drawing is refused rather than
        // scaled into a smear.
        val speck = loop(listOf(0f to 0f, 0.004f to 0f, 0.004f to 0.004f, 0f to 0.004f))

        assertNull(RoomThumbnail.svg(payload(listOf(speck))))
    }

    @Test
    fun `the area of a loop is the area the app measures with`() {
        assertEquals(12f, RoomThumbnail.areaSquareMeters(room), 1e-4f)
    }

    @Test
    fun `the file name is the one the card will look for`() {
        assertEquals("thumbnail.svg", RoomThumbnail.FILE_NAME)
    }
}
