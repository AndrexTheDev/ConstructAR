/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.scan.TsdfScanPipeline
import com.constructar.nativebridge.IntegrationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one integration call does to the HUD's state (finding 171).
 *
 * The copy that followed every call used to merge the block-budget flag as
 * `result?.hitBlockBudget ?: false`, so a *failed* call — no result at all — read as "the volume
 * is fine" and took the "scan limit reached" sentence off the screen (finding 97) and out of the
 * protocol's `scanLimited` note, on a volume that was still full and had no way to shrink.
 */
class ScanStateAfterIntegrationTest {

    private fun result(hitBudget: Boolean): IntegrationResult = IntegrationResult(
        raysProcessed = 0,
        voxelUpdates = 0,
        blocksAllocated = 0,
        blocksTotal = 0,
        hitBlockBudget = hitBudget,
        milliseconds = 0,
        voxelCount = 0,
        truncationMillimeters = 0,
    )

    @Test
    fun `a spent budget survives a call that returned nothing`() {
        val state = TsdfScanPipeline.ScanState(hitBlockBudget = true).afterIntegration(result = null, millis = 4)
        assertTrue("a failed call knows nothing, it does not free the volume", state.hitBlockBudget)
    }

    @Test
    fun `a call that reports the limit latches it`() {
        val state = TsdfScanPipeline.ScanState()
            .afterIntegration(result(true), millis = 4)
            .afterIntegration(result(false), millis = 4)
        assertTrue("the volume never shrinks back under its limit", state.hitBlockBudget)
        assertFalse(TsdfScanPipeline.ScanState().afterIntegration(result(false), millis = 4).hitBlockBudget)
    }

    @Test
    fun `every offered frame is counted, whatever the call did`() {
        val state = TsdfScanPipeline.ScanState(framesIntegrated = 3, framesDropped = 1)
            .afterIntegration(result = null, millis = 7)
        assertEquals("the denominator of droppedFraction is offered frames", 4, state.framesIntegrated)
        assertEquals("a dropped frame stays dropped", 1, state.framesDropped)
        assertEquals(7, state.lastIntegrationMillis)
    }
}
