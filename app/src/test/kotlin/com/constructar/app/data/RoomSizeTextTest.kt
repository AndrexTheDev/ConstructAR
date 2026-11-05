/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.SketchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The card's two numbers are digits with no unit, no rounding of their own and no second source.
 *
 * Three things are worth a test: the ring is closed (an open walk drops a wall from the
 * perimeter), the digits do not follow the phone's decimal mark (the length column beside them is
 * `Locale.ROOT` too, and two decimal marks on one row is how finding 193 started), and a plan that
 * encloses nothing states nothing instead of "0.0 m²".
 */
class RoomSizeTextTest {

    private fun room(x: Float = 4f, z: Float = 3f) = FloorplanSketch(
        listOf(
            SketchPoint(0f, 0f),
            SketchPoint(x, 0f),
            SketchPoint(x, z),
            SketchPoint(0f, z),
        ),
    )

    @Test
    fun `a 4 x 3 m room states its area and a closed perimeter`() {
        val size = RoomSizeText.of(room())

        assertTrue(size != null)
        assertEquals("12.0", size?.area)
        // 14.00, not 11.00: the walk back to the first corner is a wall.
        assertEquals("14.00", size?.perimeter)
    }

    @Test
    fun `the digits carry no unit and follow no locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val size = RoomSizeText.of(room())
            val text = size?.area.orEmpty() + "|" + size?.perimeter.orEmpty()

            // The unit is in `card_area`/`card_perimeter`, never in Kotlin.
            assertFalse("the unit belongs to the resource", text.contains("m"))
            // A German phone still gets a dot from this side: the row's length column does too.
            assertFalse("no decimal comma on the card", text.contains(","))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a room that was never scanned states nothing`() {
        assertNull(RoomSizeText.of(null))
        assertNull(RoomSizeText.of(FloorplanSketch()))
        assertNull(RoomSizeText.of(FloorplanSketch(listOf(SketchPoint(0f, 0f), SketchPoint(1f, 0f)))))
    }

    @Test
    fun `an outline that encloses nothing states nothing`() {
        // Every corner on one line: a ring of zero area, and "0.0 m²" would be a claim about the
        // room rather than about the scan.
        val collinear = FloorplanSketch(
            listOf(SketchPoint(0f, 0f), SketchPoint(1f, 0f), SketchPoint(2f, 0f)),
        )

        assertNull(RoomSizeText.of(collinear))
    }

    @Test
    fun `an open ring states nothing and a hand-closed one states its numbers`() {
        val doorway = FloorplanSketch(room().points, FloorplanSketch.Closure.Open)

        assertNull(RoomSizeText.of(doorway))

        val closed = doorway.closedByHand()
        val size = RoomSizeText.of(closed)

        assertEquals("12.0", size?.area)
        // Closing the gap adds the missing 3 m wall: 11.00 would be the open walk.
        assertEquals("14.00", size?.perimeter)
    }

    @Test
    fun `a coordinate that is not a number states nothing`() {
        val broken = FloorplanSketch(
            listOf(SketchPoint(0f, 0f), SketchPoint(Float.NaN, 0f), SketchPoint(1f, 1f)),
        )

        assertNull(RoomSizeText.of(broken))
    }

    @Test
    fun `an unsimplified trace draws nothing and states nothing`() {
        val ring = (0 until FloorplanSketch.MAX_POINTS + 1).map { SketchPoint(it.toFloat(), 0f) }

        assertNull(RoomSizeText.of(FloorplanSketch(ring)))
    }
}
