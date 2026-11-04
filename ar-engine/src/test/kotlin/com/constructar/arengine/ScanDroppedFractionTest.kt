/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.scan.TsdfScanPipeline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The share of frames the integrator refused (finding 170).
 *
 * `ScanState.framesDropped` was counted on every refused frame and read by nothing, while
 * `docs/ARCHITECTURE.md` promised that it made "the scan looks sparse" distinguishable from "the
 * device is overloaded". The overlay now asks this number, so the arithmetic behind the sentence
 * is pinned here: the denominator is the frames that were *offered* to the integrator, not the
 * camera's, because a frame the thermal gate skips is a deliberate saving (finding 169).
 */
class ScanDroppedFractionTest {

    private fun state(integrated: Int, dropped: Int) =
        TsdfScanPipeline.ScanState(framesIntegrated = integrated, framesDropped = dropped)

    @Test
    fun `a pipeline that has not offered a frame yet reports nothing`() {
        assertEquals(0f, state(integrated = 0, dropped = 0).droppedFraction, 0f)
    }

    @Test
    fun `the share is taken over the frames that were offered to the integrator`() {
        assertEquals(0.1f, state(integrated = 27, dropped = 3).droppedFraction, 1e-6f)
        assertEquals(0.5f, state(integrated = 15, dropped = 15).droppedFraction, 1e-6f)
    }

    @Test
    fun `a queue that refuses everything reports one`() {
        assertEquals(1f, state(integrated = 0, dropped = 12).droppedFraction, 0f)
    }
}
