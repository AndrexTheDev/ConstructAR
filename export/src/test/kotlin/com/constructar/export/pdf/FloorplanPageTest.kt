/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import com.constructar.export.svg.FloorplanSvg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan page's arithmetic, asserted without a `PdfDocument`.
 *
 * A plan is the page a customer measures with a folding rule, so three properties matter more than
 * any pixel: one scale for both axes (a stretch would make opposite walls stop being parallel), the
 * world's `z` running down the page (a mirrored plan is a different room), and a wall stroke that
 * stays inside the printable range for a bathroom and a hall alike. The rest of the page - the
 * heading, the caption, the note - is text the caller has already localised; the drawing is the only
 * thing this module decides, and it is what these tests pin.
 */
class FloorplanPageTest {

    private fun rect(x: Float = 4f, z: Float = 3f) = listOf(
        FloorplanSvg.Corner(0f, 0f),
        FloorplanSvg.Corner(x, 0f),
        FloorplanSvg.Corner(x, z),
        FloorplanSvg.Corner(0f, z),
    )

    @Test
    fun `the longer extent sets one scale for both axes`() {
        val layout = FloorplanLayout.of(rect(), left = 0f, top = 0f, width = 100f, height = 100f)

        assertNotNull(layout)
        // 4 m across 100 points is 25 points per metre; 3 m would allow 33.3, and 25 wins.
        assertEquals(25f, layout?.metresToPoints ?: 0f, 0.001f)
        val xs = layout?.points?.map { it.first }.orEmpty()
        val ys = layout?.points?.map { it.second }.orEmpty()
        // The room is 100 x 75 points, not 100 x 100: the scale is shared, so nothing is stretched.
        assertEquals(100f, (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f), 0.001f)
        assertEquals(75f, (ys.maxOrNull() ?: 0f) - (ys.minOrNull() ?: 0f), 0.001f)
    }

    @Test
    fun `world z runs down the page and the room is centred`() {
        val layout = FloorplanLayout.of(rect(), left = 10f, top = 20f, width = 100f, height = 100f)

        val points = layout?.points.orEmpty()
        assertEquals(4, points.size)
        // Corner order is the ring's order, unchanged: no reordering, no resampling.
        assertEquals(10f, points[0].first, 0.001f)
        // 12.5 points of slack above and below the 75-point-deep room.
        assertEquals(32.5f, points[0].second, 0.001f)
        assertEquals(10f, points[3].first, 0.001f)
        // The same corner at z = 3 m sits below the first: z grows downwards, never upwards.
        assertEquals(107.5f, points[3].second, 0.001f)
        assertTrue("a bigger z must be further down the page", points[3].second > points[0].second)
    }

    @Test
    fun `the wall is ten centimetres of paper, clamped at both ends`() {
        val normal = FloorplanLayout.of(rect(), 0f, 0f, 100f, 100f)
        assertEquals(2.5f, normal?.wallWidthPoints ?: 0f, 0.001f)

        // A 40 m hall on a 100-point box: 10 cm would be 0.25 points and print as nothing.
        val thin = FloorplanLayout.of(rect(40f, 30f), 0f, 0f, 100f, 100f)
        assertEquals(FloorplanLayout.MIN_WALL_POINTS, thin?.wallWidthPoints ?: 0f, 0.001f)

        // A 40 cm box on a 4 m room: 10 cm would be 25 points and flood the room with ink.
        val thick = FloorplanLayout.of(rect(), 0f, 0f, 400f, 400f)
        assertEquals(FloorplanLayout.MAX_WALL_POINTS, thick?.wallWidthPoints ?: 0f, 0.001f)
    }

    @Test
    fun `an outline with no depth on one axis is scaled by the other`() {
        // A corridor walked as a line: z never changes, so only the x extent may set the scale.
        val layout = FloorplanLayout.of(
            listOf(FloorplanSvg.Corner(0f, 0f), FloorplanSvg.Corner(1f, 0f), FloorplanSvg.Corner(4f, 0f)),
            left = 0f,
            top = 0f,
            width = 100f,
            height = 100f,
        )

        assertEquals(25f, layout?.metresToPoints ?: 0f, 0.001f)
        val ys = layout?.points?.map { it.second }.orEmpty()
        // Nothing to centre vertically: the line sits in the box's middle.
        assertEquals(50f, ys.firstOrNull() ?: 0f, 0.001f)
    }

    @Test
    fun `anything that is not a room has no layout`() {
        // Two corners are a wall, not a room.
        assertNull(
            FloorplanLayout.of(
                listOf(FloorplanSvg.Corner(0f, 0f), FloorplanSvg.Corner(1f, 0f)),
                0f, 0f, 100f, 100f,
            ),
        )
        assertNull(FloorplanLayout.of(emptyList(), 0f, 0f, 100f, 100f))
        // A coordinate that is not a number is a broken scan, not a room somewhere far away.
        assertNull(FloorplanLayout.of(listOf(FloorplanSvg.Corner(Float.NaN, 0f)) + rect(), 0f, 0f, 100f, 100f))
        assertNull(FloorplanLayout.of(listOf(FloorplanSvg.Corner(0f, Float.POSITIVE_INFINITY)) + rect(), 0f, 0f, 100f, 100f))
        // A box without area cannot set a scale either.
        assertNull(FloorplanLayout.of(rect(), 0f, 0f, 0f, 100f))
        assertNull(FloorplanLayout.of(rect(), 0f, 0f, 100f, 0f))
        assertNull(FloorplanLayout.of(rect(), 0f, 0f, -10f, 100f))
        // Every corner on one spot: no extent, so there is no scale to choose and no wall to draw.
        val spot = listOf(FloorplanSvg.Corner(1f, 1f), FloorplanSvg.Corner(1f, 1f), FloorplanSvg.Corner(1f, 1f))
        assertNull(FloorplanLayout.of(spot, 0f, 0f, 100f, 100f))
    }
}
