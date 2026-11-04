/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payload parser is the only place a wrong offset can be caught on this side of the bridge.
 *
 * Every case below is a payload C++ could actually produce after a layout change - a header one
 * float too long, a count written as a fractional, a truncated array - and the parser's job is to
 * refuse rather than to read it field by field. A read that runs off the end returns zeros, and a
 * room with zero-length walls is a plan that draws and is wrong.
 */
class FloorplanPayloadTest {

    /** Builds the float array `packFloorplanPayload` would produce, field by field. */
    private fun pack(
        valid: Boolean = true,
        closed: Boolean = true,
        area: Float = 12f,
        perimeter: Float = 14f,
        occupiedCells: Int = 431,
        segments: List<FloatArray> = emptyList(),
        loopLengths: List<Int> = emptyList(),
        loopClosed: List<Boolean> = emptyList(),
        outline: List<Pair<Float, Float>> = emptyList(),
    ): FloatArray {
        val values = ArrayList<Float>()
        values += if (valid) 1f else 0f
        values += if (closed) 1f else 0f
        values += area
        values += perimeter
        values += segments.size.toFloat()
        values += loopLengths.size.toFloat()
        values += outline.size.toFloat()
        values += occupiedCells.toFloat()
        for (segment in segments) for (value in segment) values += value
        for (length in loopLengths) values += length.toFloat()
        for (flag in loopClosed) values += if (flag) 1f else 0f
        for (point in outline) {
            values += point.first
            values += point.second
        }
        return values.toFloatArray()
    }

    private val room = listOf(
        0f to 0f,
        4f to 0f,
        4f to 3f,
        0f to 3f,
    )

    private val walls = listOf(
        floatArrayOf(0f, 0f, 4f, 0f),
        floatArrayOf(4f, 0f, 4f, 3f),
    )

    @Test
    fun `a room is read back with its figures, segments and loop`() {
        val parsed = FloorplanPayload.parse(
            pack(segments = walls, loopLengths = listOf(4), loopClosed = listOf(true), outline = room),
        )

        assertTrue("a well-formed payload must parse", parsed != null)
        val plan = parsed!!
        assertTrue(plan.valid)
        assertTrue(plan.closed)
        assertEquals(12f, plan.areaSquareMeters, 1e-6f)
        assertEquals(14f, plan.perimeterMeters, 1e-6f)
        assertEquals(431, plan.occupiedCells)
        assertEquals(2, plan.segments.size)
        assertEquals(PlanPoint(0f, 0f), plan.segments[0].start)
        assertEquals(PlanPoint(4f, 3f), plan.segments[1].end)
        assertEquals(4f, plan.segments[0].lengthMeters, 1e-6f)
        assertEquals(1, plan.loops.size)
        assertEquals(4, plan.loops[0].points.size)
        assertTrue(plan.loops[0].closed)
        assertEquals(PlanPoint(0f, 3f), plan.loops[0].points[3])
    }

    @Test
    fun `a segment's length is measured, not read`() {
        val parsed = FloorplanPayload.parse(
            pack(segments = listOf(floatArrayOf(0f, 0f, 3f, 4f))),
        )!!

        assertEquals(5f, parsed.segments[0].lengthMeters, 1e-5f)
        assertEquals(5f, sqrt(3f * 3f + 4f * 4f), 1e-5f)
    }

    @Test
    fun `two loops keep their own points and their own closure`() {
        val outline = listOf(0f to 0f, 1f to 0f, 1f to 1f, 5f to 5f, 6f to 5f, 6f to 6f, 5f to 6f, 5f to 5f)
        val parsed = FloorplanPayload.parse(
            pack(
                loopLengths = listOf(3, 5),
                loopClosed = listOf(true, false),
                outline = outline,
            ),
        )!!

        assertEquals(2, parsed.loops.size)
        assertTrue(parsed.loops[0].closed)
        assertEquals(3, parsed.loops[0].points.size)
        assertFalse(parsed.loops[1].closed)
        assertEquals(5, parsed.loops[1].points.size)
        assertEquals(PlanPoint(5f, 5f), parsed.loops[1].points.first())
    }

    @Test
    fun `an open plan is data, not an error`() {
        val parsed = FloorplanPayload.parse(
            pack(valid = false, closed = false, area = 0f, perimeter = 0f, outline = room),
        )

        assertTrue("a doorway still reports wall runs", parsed != null)
        assertFalse(parsed!!.valid)
        assertFalse(parsed.closed)
        assertEquals(0f, parsed.areaSquareMeters, 1e-6f)
    }

    @Test
    fun `a bare header says no plan`() {
        val parsed = FloorplanPayload.parse(pack(valid = false, closed = false, area = 0f, perimeter = 0f))!!

        assertTrue(parsed.segments.isEmpty())
        assertTrue(parsed.loops.isEmpty())
        assertEquals(0, parsed.occupiedCells)
    }

    @Test
    fun `the header is eight floats`() {
        // One segment occupying exactly the bytes after the header proves where the header ends:
        // with seven or nine this array would be truncated or would read the wrong field.
        val payload = FloatArray(FloorplanPayload.HEADER + 4)
        payload[0] = 1f
        payload[4] = 1f
        payload[6] = 0f
        payload[7] = 0f
        payload[8] = 1f
        payload[9] = 2f
        payload[10] = 3f
        payload[11] = 4f

        val parsed = FloorplanPayload.parse(payload)!!

        assertEquals(PlanPoint(1f, 2f), parsed.segments[0].start)
        assertEquals(PlanPoint(3f, 4f), parsed.segments[0].end)
    }

    @Test
    fun `a missing payload is no plan`() {
        assertNull(FloorplanPayload.parse(null))
    }

    @Test
    fun `a payload shorter than the header is refused`() {
        assertNull(FloorplanPayload.parse(FloatArray(FloorplanPayload.HEADER - 1)))
    }

    @Test
    fun `a truncated segment region is refused`() {
        val payload = pack(segments = walls, outline = room).copyOf(FloorplanPayload.HEADER + 4)

        assertNull(FloorplanPayload.parse(payload))
    }

    @Test
    fun `a truncated outline is refused`() {
        val full = pack(loopLengths = listOf(4), loopClosed = listOf(true), outline = room)

        assertNull(FloorplanPayload.parse(full.copyOf(full.size - 2)))
    }

    @Test
    fun `a fractional count is a broken writer, not a rounding`() {
        val payload = pack(segments = walls)
        payload[4] = 1.5f

        assertNull(FloorplanPayload.parse(payload))
    }

    @Test
    fun `a negative count is refused`() {
        val payload = pack()
        payload[5] = -1f

        assertNull(FloorplanPayload.parse(payload))
    }

    @Test
    fun `a count larger than the array is refused without allocating it`() {
        val payload = pack()
        payload[6] = 2_000_000f

        assertNull(FloorplanPayload.parse(payload))
    }

    @Test
    fun `a room with more cells than floats still parses`() {
        // The occupied-cell count does not index the payload: 431 cells arrive in 26 floats, and a
        // parser that bounded every count by the array's length would refuse every real plan.
        val parsed = FloorplanPayload.parse(pack(occupiedCells = 431))

        assertTrue("a real payload must parse", parsed != null)
        assertEquals(431, parsed!!.occupiedCells)
    }

    @Test
    fun `a fractional cell count is refused`() {
        val payload = pack()
        payload[7] = 12.5f

        assertNull(FloorplanPayload.parse(payload))
    }

    @Test
    fun `a non-finite coordinate is refused rather than drawn`() {
        val payload = pack(segments = listOf(floatArrayOf(0f, 0f, Float.NaN, 4f)))

        assertNull(FloorplanPayload.parse(payload))
    }

    @Test
    fun `a non-finite area is refused rather than printed`() {
        assertNull(FloorplanPayload.parse(pack(area = Float.NaN)))
        assertNull(FloorplanPayload.parse(pack(perimeter = Float.POSITIVE_INFINITY)))
    }

    @Test
    fun `loops that claim more points than the outline holds are refused`() {
        val payload = pack(loopLengths = listOf(4), loopClosed = listOf(true), outline = room)
        payload[6] = 3f

        assertNull(FloorplanPayload.parse(payload))
    }

    @Test
    fun `a fractional loop length is refused instead of truncated`() {
        val payload = pack(loopLengths = listOf(4), loopClosed = listOf(true), outline = room)
        payload[FloorplanPayload.HEADER + 4 * walls.size] = 2.5f

        assertNull(FloorplanPayload.parse(payload))
    }

    @Test
    fun `trailing floats are ignored, so a longer payload stays readable`() {
        val payload = pack(
            segments = walls,
            loopLengths = listOf(4),
            loopClosed = listOf(true),
            outline = room,
        ) + floatArrayOf(99f, 99f)

        val parsed = FloorplanPayload.parse(payload)!!

        assertEquals(2, parsed.segments.size)
    }
}
