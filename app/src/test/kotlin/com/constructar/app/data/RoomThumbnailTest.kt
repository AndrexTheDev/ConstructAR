/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.SketchPoint
import com.constructar.nativebridge.FloorplanPayload
import com.constructar.nativebridge.PlanLoop
import com.constructar.nativebridge.PlanPoint
import com.constructar.nativebridge.PlanSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `the sketch is the same loop the svg draws, in metres`() {
        val sketch = RoomThumbnail.sketch(payload(listOf(room)))

        assertNotNull("a closed loop must produce a sketch", sketch)
        assertEquals(4, sketch!!.points.size)
        assertEquals(SketchPoint(4f, 0f), sketch.points[1])
        assertEquals(12f, sketch.areaSquareMeters, 1e-4f)
        assertTrue(sketch.isDrawable)
    }

    @Test
    fun `an open plan produces no sketch and no file`() {
        val doorway = loop(listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f), closed = false)

        assertNull(RoomThumbnail.sketch(payload(listOf(doorway))))
        assertNull(RoomThumbnail.svg(payload(listOf(doorway))))
    }

    @Test
    fun `the card projection is the exported file's projection`() {
        // The numbers `FloorplanSvgTest` pins: 4 x 4 m in a 320 px box with 16 px of padding.
        val square = FloorplanSketch(
            listOf(
                SketchPoint(0f, 0f),
                SketchPoint(4f, 0f),
                SketchPoint(4f, 4f),
                SketchPoint(0f, 4f),
            ),
        )
        val points = RoomThumbnail.pathPoints(square, sizePx = 320f, paddingPx = 16f)

        assertEquals(4, points.size)
        assertEquals(16f, points[0].first, 1e-4f)
        assertEquals(16f, points[0].second, 1e-4f)
        assertEquals(304f, points[1].first, 1e-4f)
        assertEquals(304f, points[2].second, 1e-4f)
    }

    @Test
    fun `the card centres a long room instead of stretching it`() {
        val long = FloorplanSketch(
            listOf(
                SketchPoint(-2f, 0f),
                SketchPoint(2f, 0f),
                SketchPoint(2f, 8f),
                SketchPoint(-2f, 8f),
            ),
        )
        val points = RoomThumbnail.pathPoints(long, sizePx = 320f, paddingPx = 16f)

        assertEquals(88f, points[0].first, 1e-4f)
        assertEquals(232f, points[1].first, 1e-4f)
        assertEquals(304f, points[2].second, 1e-4f)
    }

    @Test
    fun `world z runs down the card`() {
        val points = RoomThumbnail.pathPoints(
            FloorplanSketch(listOf(SketchPoint(0f, 0f), SketchPoint(4f, 0f), SketchPoint(0f, 3f))),
            sizePx = 200f,
            paddingPx = 0f,
        )

        assertTrue("a larger z must be lower on the card", points[2].second > points[0].second)
    }

    @Test
    fun `nothing drawable projects to nothing`() {
        assertTrue(RoomThumbnail.pathPoints(FloorplanSketch(), sizePx = 320f, paddingPx = 16f).isEmpty())
        // A box too small for its own padding is not a picture either.
        val square = FloorplanSketch(listOf(SketchPoint(0f, 0f), SketchPoint(1f, 0f), SketchPoint(0f, 1f)))
        assertTrue(RoomThumbnail.pathPoints(square, sizePx = 20f, paddingPx = 10f).isEmpty())
    }

    @Test
    fun `a sketch with no extent is not projected`() {
        val sliver = FloorplanSketch(listOf(SketchPoint(1f, 1f), SketchPoint(1f, 1f), SketchPoint(1f, 1f)))

        assertTrue(RoomThumbnail.pathPoints(sliver, sizePx = 320f, paddingPx = 16f).isEmpty())
    }

    @Test
    fun `the file name is the one the card will look for`() {
        assertEquals("thumbnail.svg", RoomThumbnail.FILE_NAME)
    }
}
