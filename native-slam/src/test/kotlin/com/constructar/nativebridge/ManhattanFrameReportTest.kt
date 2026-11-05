/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame a floorplan was squared onto, read from `nativeAlignManhattanFrame`.
 *
 * The two facts that matter to a caller are whether a frame existed at all and which of its three
 * axes are horizontal - the rest (inlier counts, residual) explains them. A parser that accepted a
 * truncated array, a flag of `0.5` or a plane count the snapped planes exceed would hand a screen a
 * frame it can print but cannot justify.
 */
class ManhattanFrameReportTest {

    private fun frame(
        valid: Float = 1f,
        residual: Float = 1.4f,
        planes: Float = 4f,
        snapped: Float = 4f,
        axis0: FloatArray = floatArrayOf(1f, 0f, 0f),
        axis1: FloatArray = floatArrayOf(0f, 1f, 0f),
        axis2: FloatArray = floatArrayOf(0f, 0f, 1f),
    ) = ManhattanFrameReport.from(
        floatArrayOf(
            valid, residual, planes, snapped,
            axis0[0], axis0[1], axis0[2],
            axis1[0], axis1[1], axis1[2],
            axis2[0], axis2[1], axis2[2],
        ),
    )

    @Test
    fun `a fitted room frame reports its axes and how many walls agreed`() {
        val report = frame()
        assertEquals(true, report?.valid)
        assertEquals(1.4f, report?.residualDegrees ?: 0f, 1e-4f)
        assertEquals(4, report?.planes)
        assertEquals(4, report?.snappedPlanes)
        assertEquals(2, report?.horizontalAxes?.size)
        // The vertical axis is the one with the largest |y|: a wall direction is level.
        assertTrue(report?.axes?.get(1)?.isHorizontal ?: false)
        assertFalse(report?.axes?.get(0)?.isHorizontal ?: true)
    }

    @Test
    fun `a frame that is not valid says so and is still a report`() {
        val report = frame(valid = 0f, planes = 1f, snapped = 0f)
        assertFalse(report?.valid ?: true)
        assertEquals(0, report?.snappedPlanes)
    }

    @Test
    fun `anything that is not thirteen finite numbers is no frame at all`() {
        assertNull(ManhattanFrameReport.from(null))
        assertNull(ManhattanFrameReport.from(floatArrayOf(1f, 1.4f, 4f, 4f)))
        // A flag that is neither 0 nor 1, a negative residual, a snapped count above the plane
        // count, and a non-finite axis: each would otherwise become a claim about a room.
        assertNull(frame(valid = 0.5f))
        assertNull(frame(residual = -0.1f))
        assertNull(frame(planes = 2f, snapped = 4f))
        assertNull(frame(axis0 = floatArrayOf(Float.NaN, 0f, 0f)))
        assertNull(frame(planes = 3.5f))
        assertEquals(ManhattanFrameReport.SIZE, 13)
    }
}
