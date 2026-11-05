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
 * The four floats `nativeLastManhattan` writes, read the way a screen would read them.
 *
 * The values are the native step's own report - "the room was 4.2 degrees off square and four runs
 * were rebuilt" - so a parser that rounds a flag, accepts a negative residual or fills a missing
 * field with zero would invent a claim about a measurement. Everything that is not a report is
 * `null`.
 */
class ManhattanReportTest {

    @Test
    fun `a squared plan reports what it did`() {
        val report = ManhattanReport.from(floatArrayOf(1f, 4.2f, 4f, 4f))
        assertEquals(true, report?.applied)
        assertEquals(4.2f, report?.residualDegrees ?: 0f, 1e-4f)
        assertEquals(4, report?.snappedRuns)
    }

    @Test
    fun `a tolerance of zero is a report too - it says nothing was squared`() {
        val report = ManhattanReport.from(floatArrayOf(0f, 0f, 0f, 0f))
        assertFalse(report?.applied ?: true)
        assertEquals(0, report?.snappedRuns)
    }

    @Test
    fun `anything that is not four finite numbers is no report at all`() {
        assertNull(ManhattanReport.from(null))
        assertNull(ManhattanReport.from(floatArrayOf(1f, 4.2f, 4f)))
        assertNull(ManhattanReport.from(floatArrayOf(1f, 4.2f, 4f, 4f, 0f)))
        // A flag that is not 0 or 1: a library and a Kotlin side that disagree about the layout.
        assertNull(ManhattanReport.from(floatArrayOf(0.5f, 4.2f, 4f, 4f)))
        // A residual that cannot be a measurement, and a negative one.
        assertNull(ManhattanReport.from(floatArrayOf(1f, Float.NaN, 4f, 4f)))
        assertNull(ManhattanReport.from(floatArrayOf(1f, -1f, 4f, 4f)))
        // Counts are written as floats: 2.5 runs is a broken writer, not a rounding artefact.
        assertNull(ManhattanReport.from(floatArrayOf(1f, 4.2f, 2.5f, 4f)))
        assertNull(ManhattanReport.from(floatArrayOf(1f, 4.2f, -1f, 4f)))
        assertNull(ManhattanReport.from(floatArrayOf(1f, 4.2f, 4f, Float.NaN)))
        assertTrue(ManhattanReport.SIZE == 4)
    }
}
