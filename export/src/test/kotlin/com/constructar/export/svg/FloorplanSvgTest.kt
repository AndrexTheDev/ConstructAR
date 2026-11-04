/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.svg

import com.constructar.export.svg.FloorplanSvg.Corner
import com.constructar.export.svg.FloorplanSvg.Options
import com.constructar.export.svg.FloorplanSvg.Rejection
import com.constructar.export.svg.FloorplanSvg.Output
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A thumbnail is the only part of a room the operator sees without opening it, so it has to be the
 * room: one scale for both axes, a wall that is a wall at any canvas size, and numbers a C parser
 * reads the same way the drawing was computed.
 *
 * The reference room is 4 x 4 m on a 320 px canvas with 16 px of padding: an inner square of
 * 288 px, so 72 px per metre, the outline at 16 and 304.
 */
class FloorplanSvgTest {

    private val square4 = listOf(
        Corner(0f, 0f),
        Corner(4f, 0f),
        Corner(4f, 4f),
        Corner(0f, 4f),
    )

    private fun drawn(corners: List<Corner>, options: Options = Options()): FloorplanSvg.Drawing {
        val result = FloorplanSvg.render(corners, options)
        assertTrue("expected a drawing, got $result", result is Result.Drawn)
        return (result as Result.Drawn).drawing
    }

    private fun pointsOf(svg: String): String {
        val match = Regex("points=\"([^\"]*)\"").find(svg)
        assertTrue("the polygon has no points attribute", match != null)
        return match!!.groupValues[1]
    }

    @Test
    fun `a square room is drawn at the padding and reports its figures`() {
        val drawing = drawn(square4)

        assertEquals("16.00 16.00 304.00 16.00 304.00 304.00 16.00 304.00", pointsOf(drawing.svg))
        assertEquals(4f, drawing.widthMeters, 1e-5f)
        assertEquals(4f, drawing.depthMeters, 1e-5f)
        assertEquals(16f, drawing.areaSquareMeters, 1e-4f)
        assertEquals(16f, drawing.perimeterMeters, 1e-4f)
        assertEquals(72f, drawing.pixelsPerMeter, 1e-4f)
    }

    @Test
    fun `one scale serves both axes, so a long room is not stretched`() {
        // 4 m by 8 m: the long side sets the scale, the short side keeps its proportion.
        val drawing = drawn(
            listOf(Corner(-2f, 0f), Corner(2f, 0f), Corner(2f, 8f), Corner(-2f, 8f)),
        )

        assertEquals("88.00 16.00 232.00 16.00 232.00 304.00 88.00 304.00", pointsOf(drawing.svg))
        assertEquals(36f, drawing.pixelsPerMeter, 1e-4f)
        // 4 m across at 36 px/m is 144 px, and 8 m down the other way is 288.
        assertEquals(144f, (232f - 88f), 1e-4f)
        assertEquals(32f, drawing.areaSquareMeters, 1e-4f)
        assertEquals(24f, drawing.perimeterMeters, 1e-4f)
    }

    @Test
    fun `negative coordinates land inside the padding`() {
        // 3 m by 2 m, straddling both axes: width 288 px, height 192 px, centred vertically.
        val drawing = drawn(
            listOf(Corner(5f, -1f), Corner(8f, -1f), Corner(8f, 1f), Corner(5f, 1f)),
        )

        assertEquals("16.00 64.00 304.00 64.00 304.00 256.00 16.00 256.00", pointsOf(drawing.svg))
        assertEquals(96f, drawing.pixelsPerMeter, 1e-4f)
        assertEquals(6f, drawing.areaSquareMeters, 1e-4f)
        assertEquals(10f, drawing.perimeterMeters, 1e-4f)
    }

    @Test
    fun `a repeated closing corner draws the same picture`() {
        val closed = square4 + Corner(0f, 0f)

        assertEquals(pointsOf(drawn(square4).svg), pointsOf(drawn(closed).svg))
    }

    @Test
    fun `the wall keeps its physical thickness`() {
        // 10 cm of wall at 72 px/m is 7.2 px, and at 36 px/m it is 3.6 - not a fixed line width.
        assertTrue(drawn(square4).svg.contains("stroke-width=\"7.20\""))

        val long = drawn(listOf(Corner(-2f, 0f), Corner(2f, 0f), Corner(2f, 8f), Corner(-2f, 8f)))
        assertTrue(long.svg.contains("stroke-width=\"3.60\""))
    }

    @Test
    fun `a hall two hundred metres long still shows its walls`() {
        val drawing = drawn(listOf(Corner(0f, 0f), Corner(200f, 0f), Corner(200f, 20f), Corner(0f, 20f)))

        // 1.44 px/m would draw a 0.14 px wall, i.e. nothing; the hairline floor is 1 px.
        assertTrue(drawing.svg.contains("stroke-width=\"1.00\""))
    }

    @Test
    fun `numbers never take a decimal comma`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val points = pointsOf(drawn(square4).svg)

            assertFalse("a comma here is an SVG separator: $points", points.contains(','))
            assertEquals("16.00 16.00 304.00 16.00 304.00 304.00 16.00 304.00", points)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the file stands on its own`() {
        val svg = drawn(square4).svg

        assertTrue(svg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"))
        assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svg.contains("width=\"320\" height=\"320\""))
        assertTrue(svg.contains("viewBox=\"0 0 320 320\""))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
    }

    @Test
    fun `nothing in the file is a sentence`() {
        val svg = drawn(square4).svg

        for (forbidden in listOf("<text", "tspan", "aria-label", "role=", "<title", "<desc")) {
            assertFalse("the export owns no words, found $forbidden", svg.contains(forbidden))
        }
    }

    @Test
    fun `the colours are the caller's`() {
        val svg = drawn(square4, Options(floorColor = "#FFFFFF", wallColor = "#000000")).svg

        assertTrue(svg.contains("fill=\"#FFFFFF\""))
        assertTrue(svg.contains("stroke=\"#000000\""))
    }

    @Test
    fun `the same room draws the same bytes twice`() {
        assertEquals(drawn(square4).svg, drawn(square4.toList()).svg)
    }

    @Test
    fun `fewer than three corners is refused`() {
        val refused = FloorplanSvg.render(listOf(Corner(0f, 0f), Corner(4f, 0f)))

        assertEquals(Output.Refused(Rejection.TOO_FEW_CORNERS), refused)
    }

    @Test
    fun `a run that collapses to two corners is refused`() {
        val refused = FloorplanSvg.render(listOf(Corner(0f, 0f), Corner(4f, 0f), Corner(0f, 0f)))

        assertEquals(Output.Refused(Rejection.TOO_FEW_CORNERS), refused)
    }

    @Test
    fun `a broken coordinate is refused rather than drawn`() {
        for (broken in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val corners = listOf(Corner(0f, 0f), Corner(broken, 0f), Corner(4f, 4f), Corner(0f, 4f))
            assertEquals(Output.Refused(Rejection.NOT_FINITE), FloorplanSvg.render(corners))
        }
    }

    @Test
    fun `a room smaller than the minimum is not a plan`() {
        val corners = listOf(Corner(0f, 0f), Corner(0.05f, 0f), Corner(0.05f, 0.05f), Corner(0f, 0.05f))

        assertEquals(Output.Refused(Rejection.DEGENERATE), FloorplanSvg.render(corners))
    }

    @Test
    fun `an impossible scale is a bug, not a building`() {
        val corners = listOf(Corner(0f, 0f), Corner(500f, 0f), Corner(500f, 20f), Corner(0f, 20f))

        assertEquals(Output.Refused(Rejection.UNREASONABLE_SCALE), FloorplanSvg.render(corners))
    }

    @Test
    fun `a canvas with no room for a drawing is refused`() {
        assertEquals(
            Output.Refused(Rejection.CANVAS_TOO_SMALL),
            FloorplanSvg.render(square4, Options(sizePx = 32, paddingPx = 16f)),
        )
    }
}
