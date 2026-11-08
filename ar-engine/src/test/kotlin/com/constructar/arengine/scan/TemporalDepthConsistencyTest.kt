/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.arengine.depth.DepthFrameSnapshot
import com.constructar.arengine.depth.DepthSource
import com.constructar.core.geo.DepthIntrinsics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalDepthConsistencyTest {
    private val width = 4
    private val height = 4
    private val intrinsics = DepthIntrinsics(fx = 100f, fy = 100f, cx = 1.5f, cy = 1.5f)
    private val policy = TemporalConsistencyPolicy(sampleStride = 1)

    private fun pose(z: Float = 0f): FloatArray = FloatArray(16) { index ->
        when (index) {
            0, 5, 10, 15 -> 1f
            14 -> z
            else -> 0f
        }
    }

    private fun snapshot(
        depthMm: ShortArray,
        cameraToWorld: FloatArray = pose(),
        confidence: ByteArray? = ByteArray(width * height) { 0x7f },
    ): DepthFrameSnapshot {
        val stride = width + 2
        val padded = ShortArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) padded[y * stride + x] = depthMm[y * width + x]
        }
        return DepthFrameSnapshot(
            depthMm = padded,
            width = width,
            height = height,
            rowStride = stride,
            confidence = confidence,
            confidenceWidth = if (confidence == null) 0 else width,
            confidenceHeight = if (confidence == null) 0 else height,
            confidenceRowStride = if (confidence == null) 0 else width,
            intrinsics = intrinsics,
            cameraToWorld = cameraToWorld,
            timestampNs = 900L,
            depthTimestampNs = 850L,
            source = DepthSource.RAW,
        )
    }

    private fun fused(source: DepthFrameSnapshot, depthMm: ShortArray) = FusedDepthFrame(
        depthMm = depthMm,
        confidence = ByteArray(width * height) { 0x7f },
        width = width,
        height = height,
        timestampNs = 100L,
        intrinsics = source.intrinsics,
        cameraToWorld = source.cameraToWorld,
        receivedAtMillis = 900L,
        depthTimestampNs = 80L,
        source = source.source,
    )

    private fun select(current: DepthFrameSnapshot, fused: FusedDepthFrame) = DepthIntegrationSelector.select(
        snapshot = current,
        fused = fused,
        nowMillis = 1_000L,
        fusionTtlMillis = 200L,
        minConfidence = 96,
        temporalPolicy = policy,
    )

    @Test
    fun `consistent reprojected depth keeps the fused frame`() {
        val current = snapshot(ShortArray(width * height) { 1_000 })
        val selected = select(current, fused(current, ShortArray(width * height) { 1_000 }))
        assertTrue(selected.usesFusedDepth)
        assertEquals(16, selected.temporalReport?.comparedSamples)
        assertEquals(0, selected.temporalReport?.conflictingSamples)
        assertFalse(selected.temporalReport?.rejected == true)
    }

    @Test
    fun `nearer occluders and removed foreground both reject the stale hypothesis`() {
        val reference = snapshot(ShortArray(width * height) { 1_000 })
        val stale = fused(reference, ShortArray(width * height) { 1_000 })

        val nearer = snapshot(ShortArray(width * height) { 500 })
        val occluded = select(nearer, stale)
        assertFalse(occluded.usesFusedDepth)
        assertSame(nearer.depthMm, occluded.depthMm)
        assertEquals(TemporalRejectionReason.CONTRADICTED, occluded.temporalReport?.reason)
        assertEquals(16, occluded.temporalReport?.conflictingSamples)

        val farther = snapshot(ShortArray(width * height) { 1_500 })
        val removed = select(farther, stale)
        assertFalse(removed.usesFusedDepth)
        assertSame(farther.depthMm, removed.depthMm)
        assertEquals(TemporalRejectionReason.CONTRADICTED, removed.temporalReport?.reason)
    }

    @Test
    fun `forward camera motion is compared in 3d instead of as a raw pixel difference`() {
        val source = snapshot(ShortArray(width * height) { 1_000 })
        val moved = snapshot(ShortArray(width * height) { 1_200 }, cameraToWorld = pose(z = 0.2f))
        val selected = select(moved, fused(source, ShortArray(width * height) { 1_000 }))
        assertTrue(selected.usesFusedDepth)
        assertEquals(16, selected.temporalReport?.comparedSamples)
        assertEquals(0, selected.temporalReport?.conflictingSamples)
    }

    @Test
    fun `isolated outliers need both enough conflicts and a sufficient ratio`() {
        val current = snapshot(ShortArray(width * height) { 1_000 })
        val stale = fused(current, ShortArray(width * height) { 1_000 })
        val threeOutliers = ShortArray(width * height) { 1_000 }
        for (index in 0 until 3) threeOutliers[index] = 500
        val below = select(snapshot(threeOutliers), stale)
        assertTrue(below.usesFusedDepth)
        assertEquals(3, below.temporalReport?.conflictingSamples)

        val fourOutliers = threeOutliers.copyOf()
        fourOutliers[3] = 500
        val above = select(snapshot(fourOutliers), stale)
        assertFalse(above.usesFusedDepth)
        assertEquals(4, above.temporalReport?.conflictingSamples)
        assertEquals(0.25f, above.temporalReport?.conflictRatio ?: -1f, 1e-6f)
    }

    @Test
    fun `missing or low confidence measurements neither confirm nor reject`() {
        val reference = snapshot(ShortArray(width * height) { 1_000 })
        val stale = fused(reference, ShortArray(width * height) { 1_000 })

        val missing = select(snapshot(ShortArray(width * height)), stale)
        assertTrue(missing.usesFusedDepth)
        assertEquals(0, missing.temporalReport?.comparedSamples)
        assertFalse(missing.temporalReport?.rejected == true)

        val lowConfidence = select(
            snapshot(
                ShortArray(width * height) { 500 },
                confidence = ByteArray(width * height) { 95 },
            ),
            stale,
        )
        assertTrue(lowConfidence.usesFusedDepth)
        assertEquals(0, lowConfidence.temporalReport?.comparedSamples)
        assertFalse(lowConfidence.temporalReport?.rejected == true)
    }

    @Test
    fun `invalid acquisition geometry fails closed to the current frame`() {
        val current = snapshot(ShortArray(width * height) { 1_000 })
        val malformed = fused(current, ShortArray(width * height) { 1_000 }).copy(
            cameraToWorld = pose().also { it[0] = Float.NaN },
        )
        val selected = select(current, malformed)
        assertFalse(selected.usesFusedDepth)
        assertSame(current.depthMm, selected.depthMm)
        assertEquals(TemporalRejectionReason.INVALID_GEOMETRY, selected.temporalReport?.reason)
        assertEquals(0, selected.temporalReport?.comparedSamples)
    }
}
