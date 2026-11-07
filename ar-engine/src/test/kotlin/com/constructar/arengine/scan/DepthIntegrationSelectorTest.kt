/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.arengine.depth.DepthFrameSnapshot
import com.constructar.core.geo.DepthIntrinsics
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Acquisition geometry is part of a depth frame, never implicit "latest frame" state. */
class DepthIntegrationSelectorTest {

    private val width = 4
    private val height = 4

    private fun pose(x: Float): FloatArray = FloatArray(16) { index ->
        when (index) {
            0, 5, 10, 15 -> 1f
            12 -> x
            else -> 0f
        }
    }

    private fun snapshot(
        timestampNs: Long,
        cameraX: Float,
        fx: Float,
    ): DepthFrameSnapshot = DepthFrameSnapshot(
        depthMm = ShortArray((width + 2) * height) { 2_000 },
        width = width,
        height = height,
        rowStride = width + 2,
        confidence = ByteArray(width * height) { 0x7f },
        confidenceWidth = width,
        confidenceHeight = height,
        confidenceRowStride = width,
        intrinsics = DepthIntrinsics(fx, fx + 1f, 2f, 2f),
        cameraToWorld = pose(cameraX),
        timestampNs = timestampNs,
    )

    private fun fused(source: DepthFrameSnapshot, receivedAtMillis: Long = 900L) = FusedDepthFrame(
        depthMm = ShortArray(width * height) { 1_000 },
        confidence = ByteArray(width * height) { if (it % 2 == 0) 0x60 else 0x7f },
        width = width,
        height = height,
        timestampNs = source.timestampNs,
        intrinsics = source.intrinsics,
        cameraToWorld = source.cameraToWorld,
        receivedAtMillis = receivedAtMillis,
    )

    @Test
    fun `a late inference result keeps its source pose intrinsics and timestamp`() {
        val source = snapshot(timestampNs = 100L, cameraX = 1.25f, fx = 100f)
        val newest = snapshot(timestampNs = 900L, cameraX = 9f, fx = 999f)
        val fused = fused(source)

        val selected = DepthIntegrationSelector.select(newest, fused, nowMillis = 1_000L, fusionTtlMillis = 200L)

        assertTrue(selected.usesFusedDepth)
        assertSame(fused.depthMm, selected.depthMm)
        assertEquals(100L, selected.timestampNs)
        assertEquals(100f, selected.intrinsics.fx)
        assertArrayEquals(pose(1.25f), selected.cameraToWorld, 0f)
        assertEquals(width, selected.rowStrideElements)
        assertSame(fused.confidence, selected.confidence)
        assertEquals(width, selected.confidenceWidth)
        assertEquals(height, selected.confidenceHeight)
        assertEquals(width, selected.confidenceRowStride)
    }

    @Test
    fun `a stale result falls back to the complete current raw frame`() {
        val source = snapshot(timestampNs = 100L, cameraX = 1.25f, fx = 100f)
        val newest = snapshot(timestampNs = 900L, cameraX = 9f, fx = 999f)

        val selected = DepthIntegrationSelector.select(
            newest,
            fused(source, receivedAtMillis = 800L),
            nowMillis = 1_000L,
            fusionTtlMillis = 200L,
        )

        assertFalse(selected.usesFusedDepth)
        assertSame(newest.depthMm, selected.depthMm)
        assertSame(newest.confidence, selected.confidence)
        assertEquals(900L, selected.timestampNs)
        assertEquals(999f, selected.intrinsics.fx)
        assertEquals(width + 2, selected.rowStrideElements)
        assertArrayEquals(pose(9f), selected.cameraToWorld, 0f)
    }

    @Test
    fun `a result from the future relative to the monotonic clock is not accepted`() {
        val source = snapshot(timestampNs = 100L, cameraX = 1.25f, fx = 100f)
        val newest = snapshot(timestampNs = 900L, cameraX = 9f, fx = 999f)
        val selected = DepthIntegrationSelector.select(
            newest,
            fused(source, receivedAtMillis = 1_001L),
            nowMillis = 1_000L,
            fusionTtlMillis = 200L,
        )
        assertFalse(selected.usesFusedDepth)
    }

    @Test
    fun `a resolution mismatch cannot mix pixels and geometry`() {
        val source = snapshot(timestampNs = 100L, cameraX = 1.25f, fx = 100f)
        val newest = snapshot(timestampNs = 900L, cameraX = 9f, fx = 999f)
        val mismatched = fused(source).copy(
            width = width + 1,
            depthMm = ShortArray((width + 1) * height),
            confidence = ByteArray((width + 1) * height),
        )
        val selected = DepthIntegrationSelector.select(newest, mismatched, 1_000L, 200L)
        assertFalse(selected.usesFusedDepth)
        assertSame(newest.depthMm, selected.depthMm)
    }
}
