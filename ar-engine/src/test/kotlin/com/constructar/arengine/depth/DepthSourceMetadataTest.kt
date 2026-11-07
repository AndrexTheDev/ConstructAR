/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.depth

import com.constructar.arengine.scan.DepthCapture
import com.constructar.arengine.scan.TsdfScanPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthSourceMetadataTest {

    @Test
    fun `full depth does not claim raw confidence evidence`() {
        val full = DepthFrameSnapshot(
            depthMm = shortArrayOf(1_000),
            width = 1,
            height = 1,
            rowStride = 1,
            source = DepthSource.FULL,
        )
        assertFalse(full.confidenceAvailable)
        assertEquals(255, full.confidenceAt(0, 0))

        val raw = full.copy(source = DepthSource.RAW, confidence = byteArrayOf(0x40))
        assertTrue(raw.confidenceAvailable)
    }

    @Test
    fun `different image and camera timestamps remain observable without inferring their cause`() {
        val stats = DepthCapture.Stats(frameTimestampNs = 900L, depthTimestampNs = 850L)
        val state = TsdfScanPipeline.ScanState(lastFrameTimestampNs = 900L, lastDepthTimestampNs = 850L)
        assertTrue(stats.depthTimestampDiffersFromFrame)
        assertTrue(state.lastDepthTimestampDiffersFromFrame)
        assertFalse(stats.copy(depthTimestampNs = 900L).depthTimestampDiffersFromFrame)
        assertFalse(state.copy(lastDepthTimestampNs = 900L).lastDepthTimestampDiffersFromFrame)
    }
}
