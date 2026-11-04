/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.ai.ScratchPlanes
import com.constructar.arengine.depth.DepthFrameSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffers a fusion pass copies the frame into (finding 173).
 *
 * The copies are necessary — the pass outlives the pooled frame — but a fresh `copyOf()` per
 * pass was 600 kB of depth every 500 ms on the render thread, in a class that reuses its ARGB
 * and output buffers for the same reason. One pass runs at a time, so one pair of planes is
 * enough, and these cases pin that they are reused and that what they hold is the frame.
 */
class ScratchPlanesTest {

    private fun snapshot(
        width: Int,
        height: Int,
        depthMm: ShortArray = ShortArray(width * height) { (it + 1).toShort() },
        confidence: ByteArray? = null,
        confidenceRowStride: Int = 0,
    ) = DepthFrameSnapshot(
        depthMm = depthMm,
        width = width,
        height = height,
        rowStride = width,
        confidence = confidence,
        confidenceWidth = if (confidence == null) 0 else width,
        confidenceHeight = if (confidence == null) 0 else height,
        confidenceRowStride = confidenceRowStride,
    )

    @Test
    fun `the same planes are handed out pass after pass`() {
        val planes = ScratchPlanes()
        val first = planes.depthFrom(snapshot(width = 4, height = 2))
        val second = planes.depthFrom(snapshot(width = 4, height = 2))
        assertSame("a fusion pass must not allocate a 600 kB plane of its own", first, second)
    }

    @Test
    fun `what the plane holds is the frame it was copied from`() {
        val planes = ScratchPlanes()
        val source = ShortArray(6) { (100 + it).toShort() }
        val copied = planes.depthFrom(snapshot(width = 3, height = 2, depthMm = source))
        assertEquals(100.toShort(), copied[0])
        assertEquals(105.toShort(), copied[5])
    }

    @Test
    fun `a bigger frame grows the plane`() {
        val planes = ScratchPlanes()
        planes.depthFrom(snapshot(width = 2, height = 2))
        val bigger = planes.depthFrom(snapshot(width = 8, height = 4))
        assertTrue("the plane must be able to hold the whole frame", bigger.size >= 32)
        assertEquals(32.toShort(), bigger[31])
    }

    @Test
    fun `a frame without confidence reports none`() {
        val planes = ScratchPlanes()
        assertEquals(3, planes.confidenceFrom(snapshot(width = 1, height = 1, confidence = ByteArray(3)))?.size)
        assertNull("a device without a confidence image must not hand out a stale one", planes.confidenceFrom(snapshot(width = 1, height = 1)))
    }
}
