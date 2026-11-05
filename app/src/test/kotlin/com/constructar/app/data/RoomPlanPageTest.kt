/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.SketchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan page the protocol prints: one ring, one set of words, and no page when there is no room.
 *
 * The corners must be the stored ring in the stored order - the drawing side resamples nothing, so
 * a plan that reordered or dropped a corner here would be a second description of the room. The
 * words arrive from the resources; what this pins is *where* they land: the caption joins area and
 * perimeter, the note appears only for a wall the operator drew, and a half caption is dropped
 * rather than printed with a dangling separator.
 */
class RoomPlanPageTest {

    private fun room(closure: FloorplanSketch.Closure = FloorplanSketch.Closure.Measured) = FloorplanSketch(
        listOf(
            SketchPoint(0f, 0f),
            SketchPoint(4f, 0f),
            SketchPoint(4f, 3f),
            SketchPoint(0f, 3f),
        ),
        closure = closure,
    )

    private fun page(sketch: FloorplanSketch?) = RoomPlanPage.of(
        sketch = sketch,
        title = "Raumplan",
        areaText = "12.0 m²",
        perimeterText = "14.00 m",
        byHandNote = "Die Schlusswand wurde von Hand gezeichnet.",
    )

    @Test
    fun `the stored ring becomes the page's corners, in order`() {
        val section = page(room())

        assertNotNull(section)
        val corners = section?.corners.orEmpty()
        assertEquals(4, corners.size)
        assertEquals(0f, corners[0].x, 0.001f)
        assertEquals(0f, corners[0].z, 0.001f)
        assertEquals(4f, corners[1].x, 0.001f)
        assertEquals(4f, corners[2].x, 0.001f)
        assertEquals(3f, corners[2].z, 0.001f)
    }

    @Test
    fun `the caption joins the two numbers and the title is the caller's`() {
        val section = page(room())

        assertEquals("Raumplan", section?.title)
        assertEquals("12.0 m² · 14.00 m", section?.caption)
    }

    @Test
    fun `a measured ring explains nothing`() {
        assertEquals("", page(room())?.note)
    }

    @Test
    fun `a hand-drawn closing wall is stated on the page`() {
        val section = page(room(FloorplanSketch.Closure.DrawnByHand))

        assertEquals("Die Schlusswand wurde von Hand gezeichnet.", section?.note)
    }

    @Test
    fun `an open ring is not drawn, because it is not a room yet`() {
        // The card offers to close an open ring; the protocol must not print a plan of a walk that
        // never got back to its first corner.
        assertNull(page(room(FloorplanSketch.Closure.Open)))
    }

    @Test
    fun `no room, or a caption without both numbers, prints no page`() {
        assertNull(page(null))
        assertNull(page(FloorplanSketch()))
        assertNull(
            RoomPlanPage.of(
                sketch = room(),
                title = "Raumplan",
                areaText = "12.0 m²",
                perimeterText = " ",
                byHandNote = "note",
            ),
        )
    }

    @Test
    fun `a hand-drawn room with a missing number still prints no page`() {
        // Both reasons must hold independently: the note never rescues a caption that is broken.
        val section = RoomPlanPage.of(
            sketch = room(FloorplanSketch.Closure.DrawnByHand),
            title = "Raumplan",
            areaText = "",
            perimeterText = "14.00 m",
            byHandNote = "note",
        )

        assertTrue(section == null)
    }
}
