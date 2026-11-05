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
    fun `a sketch survives the project file's own serializer`() {
        val encoded = Json.encodeToString(FloorplanSketch.serializer(), room())
        val decoded = Json.decodeFromString(FloorplanSketch.serializer(), encoded)

        assertEquals(room(), decoded)
        assertTrue(encoded.contains("xMeters"))
    }
}
