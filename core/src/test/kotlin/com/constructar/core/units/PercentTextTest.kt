/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.units

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One rounding rule for every percent the operator reads (finding 147).
 *
 * Truncation is what five surfaces did, and the three cases below are the ones that were wrong:
 * `0.29f * 100f` is `28.999998`, so a truncated percent is one unit low on a value the operator
 * can reach; `0.849f` is the confidence boundary the protocol's own sentence talks about, and it
 * had to round the same way on the phone and in the document.
 */
class PercentTextTest {

    @Test
    fun `a percentage rounds instead of dropping its remainder`() {
        // 0.29f * 100f == 28.999998 in binary floating point: `.toInt()` said 28.
        assertEquals(29, PercentText.of(0.29f))
        assertEquals(11, PercentText.of(0.105f))
        assertEquals(85, PercentText.of(0.849f))
        // A complete scan: 0.999f used to read "99 %" on the detail gauge.
        assertEquals(100, PercentText.of(0.999f))
    }

    @Test
    fun `the values a job actually carries do not move`() {
        assertEquals(7, PercentText.of(0.07f)) // cable default waste
        assertEquals(8, PercentText.of(0.08f)) // pipe default waste
        assertEquals(10, PercentText.of(0.10f)) // duct default waste
        assertEquals(5, PercentText.of(0.05f))
        assertEquals(15, PercentText.of(0.15f))
        assertEquals(0, PercentText.of(0f))
        assertEquals(100, PercentText.of(1f))
    }

    @Test
    fun `a measured ratio cannot print past a hundred`() {
        // Float noise around a finished scan must not read "101 %".
        assertEquals(100, PercentText.of(1.0004f))
        assertEquals(100, PercentText.of(1.5f))
        assertEquals(0, PercentText.of(-0.2f))
        // No number at all is 0, the way an unstarted scan reads.
        assertEquals(0, PercentText.of(Float.NaN))
    }

    @Test
    fun `the protocol template is filled with the same number`() {
        assertEquals("11 %", PercentText.format("%1\$d %%", 0.105f))
        assertEquals(
            "below 85 % it needs a second look",
            PercentText.format("below %1\$d %% it needs a second look", 0.849f),
        )
    }
}
