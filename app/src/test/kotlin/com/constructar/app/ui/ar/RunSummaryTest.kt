/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The room total in the viewfinder.
 *
 * Two ways to get this wrong, and both would be believed: counting the live run twice (it is
 * already in the project the moment it is finished, but not while it is being measured), and
 * counting a single stray tap as a run.
 */
class RunSummaryTest {

    @Test
    fun `an empty room has nothing to add up`() {
        val totals = RunSummary.of(committedMeters = emptyList(), liveNodes = 0, liveMeters = 0f)

        assertEquals(0, totals.runs)
        assertEquals(0f, totals.meters, 0.001f)
    }

    @Test
    fun `the run under the crosshair counts as soon as it is a measurement`() {
        val totals = RunSummary.of(committedMeters = listOf(2f), liveNodes = 2, liveMeters = 3.5f)

        assertEquals(2, totals.runs)
        assertEquals(5.5f, totals.meters, 0.001f)
    }

    @Test
    fun `a single tap is not a run`() {
        val totals = RunSummary.of(committedMeters = listOf(2f), liveNodes = 1, liveMeters = 0f)

        assertEquals(1, totals.runs)
        assertEquals(2f, totals.meters, 0.001f)
    }

    @Test
    fun `twelve runs of 2 point 4 metres read 28 point 8, not 28 point 799999`() {
        val totals = RunSummary.of(
            committedMeters = List(12) { 2.4f },
            liveNodes = 0,
            liveMeters = 0f,
        )

        assertEquals(12, totals.runs)
        assertEquals(28.8f, totals.meters, 0.0001f)
    }
}
