/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.PolygonMath
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outline the card draws and the project file stores.
 *
 * Two things matter here: the area it reports must be the area the protocol prints (one shape, one
 * number), and a sketch that cannot be drawn must say so rather than be drawn as a dot.
 */
class FloorplanSketchTest {

    private fun room(x: Float = 4f, z: Float = 3f) = FloorplanSketch(
        listOf(
            SketchPoint(0f, 0f),
            SketchPoint(x, 0f),
            SketchPoint(x, z),
            SketchPoint(0f, z),
        ),
    )

    @Test
    fun `a rectangle reports the area the protocol would print`() {
        assertEquals(12f, room().areaSquareMeters, 1e-4f)
        assertEquals(12f, PolygonMath.area(room().toFloorPolygon()), 1e-4f)
    }

    @Test
    fun `a winding that runs the other way is the same room`() {
        val clockwise = FloorplanSketch(room().points.reversed())

        assertEquals(12f, clockwise.areaSquareMeters, 1e-4f)
    }

    @Test
    fun `three corners are the least that enclose anything`() {
        assertFalse(FloorplanSketch().isDrawable)
        assertFalse(FloorplanSketch(listOf(SketchPoint(0f, 0f))).isDrawable)
        assertFalse(FloorplanSketch(listOf(SketchPoint(0f, 0f), SketchPoint(1f, 0f))).isDrawable)
        assertTrue(FloorplanSketch(listOf(SketchPoint(0f, 0f), SketchPoint(1f, 0f), SketchPoint(1f, 1f))).isDrawable)
    }

    @Test
    fun `an unsimplified trace is not drawn`() {
        val ring = (0 until FloorplanSketch.MAX_POINTS + 1).map { SketchPoint(it.toFloat(), 0f) }

        assertFalse(FloorplanSketch(ring).isDrawable)
    }

    @Test
    fun `an empty sketch has no area`() {
        assertEquals(0f, FloorplanSketch().areaSquareMeters, 1e-6f)
        assertEquals(0f, FloorplanSketch(listOf(SketchPoint(0f, 0f), SketchPoint(1f, 0f))).areaSquareMeters, 1e-6f)
    }

    @Test
    fun `the perimeter counts the closing edge`() {
        // 4 + 3 + 4 + 3: the walk back to the first corner is a wall like any other. Leaving it out
        // would report 11 m for this room and under-order skirting by 3 m.
        assertEquals(14f, room().perimeterMeters, 1e-4f)
    }

    @Test
    fun `a triangle's perimeter is the sum of its sides`() {
        val triangle = FloorplanSketch(
            listOf(SketchPoint(0f, 0f), SketchPoint(3f, 0f), SketchPoint(3f, 4f)),
        )

        assertEquals(12f, triangle.perimeterMeters, 1e-4f)
        assertEquals(6f, triangle.areaSquareMeters, 1e-4f)
    }

    @Test
    fun `a sketch without a ring has no perimeter`() {
        assertFalse(FloorplanSketch().perimeterMeters > 0f)
        assertFalse(FloorplanSketch(listOf(SketchPoint(0f, 0f), SketchPoint(1f, 0f))).perimeterMeters > 0f)
    }

    @Test
    fun `a coordinate that is not a number has no perimeter`() {
        val broken = FloorplanSketch(
            listOf(SketchPoint(0f, 0f), SketchPoint(Float.NaN, 0f), SketchPoint(1f, 1f)),
        )

        assertEquals(0f, broken.perimeterMeters, 1e-6f)
    }

    @Test
    fun `an open ring is stored but is not a room`() {
        val doorway = FloorplanSketch(room().points, FloorplanSketch.Closure.Open)

        assertFalse(doorway.isDrawable)
        assertEquals(0f, doorway.areaSquareMeters, 1e-6f)
        assertEquals(0f, doorway.perimeterMeters, 1e-6f)
    }

    @Test
    fun `closing a gap by hand makes the room measurable and says so`() {
        val doorway = FloorplanSketch(room().points, FloorplanSketch.Closure.Open)

        val closed = doorway.closedByHand()

        assertEquals(FloorplanSketch.Closure.DrawnByHand, closed.closure)
        assertTrue(closed.isDrawable)
        assertEquals(12f, closed.areaSquareMeters, 1e-4f)
        assertEquals(14f, closed.perimeterMeters, 1e-4f)
        // The corners are not moved by the assertion: closing a doorway adds a wall.
        assertEquals(doorway.points, closed.points)
    }

    @Test
    fun `closing a room that is already closed changes nothing`() {
        val measured = room()

        assertEquals(measured, measured.closedByHand())
        // A measured ring keeps its own word: it was not drawn by hand.
        assertEquals(FloorplanSketch.Closure.Measured, measured.closedByHand().closure)
    }

    @Test
    fun `a project file written before closure existed decodes as measured`() {
        // Round 94 wrote `{"points":[…]}` and only ever for a closed loop: the default has to keep
        // every one of those rooms a room.
        val legacy = """{"points":[{"xMeters":0.0,"zMeters":0.0},{"xMeters":4.0,"zMeters":0.0},{"xMeters":4.0,"zMeters":3.0},{"xMeters":0.0,"zMeters":3.0}]}"""

        val decoded = Json.decodeFromString(FloorplanSketch.serializer(), legacy)

        assertEquals(FloorplanSketch.Closure.Measured, decoded.closure)
        assertEquals(12f, decoded.areaSquareMeters, 1e-4f)
    }

    @Test
    fun `a sketch survives the project file's own serializer`() {
        val encoded = Json.encodeToString(FloorplanSketch.serializer(), room())
        val decoded = Json.decodeFromString(FloorplanSketch.serializer(), encoded)

        assertEquals(room(), decoded)
        assertTrue(encoded.contains("xMeters"))
    }
}
