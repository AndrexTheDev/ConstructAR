/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialCalibrationGuardTest {
    private val width = 8
    private val height = 6
    private val config = SpatialCalibrationGuard.Config(
        columns = 2,
        rows = 1,
        minRegionSamples = 4,
        minRegionConflicts = 3,
        maxRegionInlierRatio = 0.5f,
        minResidualToleranceDisparity = 0.05f,
        residualToleranceRmsMultiplier = 2f,
        minRegionRmsDisparity = 0.08f,
    )

    private fun calibration() = MetricDepthCalibrator.Calibration(
        scale = 1f,
        shift = 0f,
        inlierRatio = 0.9f,
        inlierRmsDisparity = 0.01f,
        sampleCount = 1000,
        isUsable = true,
    )

    /** Two spatial halves at a true depth of 1 m; selected left anchors disagree by 1 m⁻¹. */
    private fun samples(
        frameWidth: Int = width,
        leftCount: Int = 4,
        leftConflicts: Int = 3,
        rightCount: Int = 4,
    ): CalibrationSampleSet {
        val half = frameWidth / 2
        val count = leftCount + rightCount
        return CalibrationSampleSet(
            relativeValues = FloatArray(count) { if (it < leftConflicts) 2f else 1f },
            metricMeters = FloatArray(count) { 1f },
            pixelX = IntArray(count) {
                if (it < leftCount) it % half else half + (it - leftCount) % (frameWidth - half)
            },
            pixelY = IntArray(count),
            frameWidth = frameWidth,
            frameHeight = height,
        )
    }

    private fun relativeMap() = RelativeDepthMap(FloatArray(width * height) { 1f }, width, height, 0, "test")

    @Test
    fun `a contradicted region blocks only its own network holes and preserves measurements`() {
        val mask = SpatialCalibrationGuard.evaluate(samples(), calibration(), config)
        assertEquals(1, mask.rejectedRegionCount)
        assertTrue(mask.rejectsPixel(0, 0, width, height))
        assertFalse(mask.rejectsPixel(width - 1, 0, width, height))

        val arcore = ShortArray(width * height)
        arcore[0] = 2_000
        val out = ShortArray(width * height) { (-1).toShort() }
        val confidence = ByteArray(width * height) { 0x7f }
        val result = AiDepthFusion.fuse(
            arcoreMm = arcore,
            confidence = null,
            confidenceWidth = 0,
            confidenceHeight = 0,
            confidenceRowStride = 0,
            width = width,
            height = height,
            relative = relativeMap(),
            calibration = calibration(),
            out = out,
            confidenceOut = confidence,
            spatialMask = mask,
        )

        assertEquals(1, result.measuredPixels)
        assertEquals(width * height / 2, result.filledPixels)
        assertEquals(width * height / 2 - 1, result.spatiallyRejectedPixels)
        for (index in out.indices) {
            val x = index % width
            val expected = when {
                index == 0 -> 2_000
                x < width / 2 -> 0
                else -> 1_000
            }
            val expectedConfidence = when {
                index == 0 -> 255
                x < width / 2 -> 0
                else -> 96
            }
            assertEquals("depth at $index", expected, out[index].toInt() and 0xFFFF)
            assertEquals("confidence at $index", expectedConfidence, confidence[index].toInt() and 0xFF)
        }
    }

    @Test
    fun `sparse regions isolated outliers and the exact ratio boundary stay neutral`() {
        val sparse = SpatialCalibrationGuard.evaluate(
            samples(leftCount = 2, leftConflicts = 2), calibration(), config,
        )
        assertEquals(0, sparse.rejectedRegionCount)

        val isolated = SpatialCalibrationGuard.evaluate(
            samples(leftConflicts = 1), calibration(), config,
        )
        assertEquals(0, isolated.rejectedRegionCount)

        val exactlyHalfInliers = SpatialCalibrationGuard.evaluate(
            samples(leftConflicts = 2), calibration(), config.copy(minRegionConflicts = 2),
        )
        assertEquals(0, exactlyHalfInliers.rejectedRegionCount)
    }

    @Test
    fun `a mask from different frame geometry fails closed for fills only`() {
        val wrongGeometry = SpatialCalibrationGuard.evaluate(
            samples(frameWidth = 4), calibration(), config,
        )
        val arcore = ShortArray(width * height)
        arcore[0] = 2_000
        val out = ShortArray(width * height)
        val result = AiDepthFusion.fuse(
            arcoreMm = arcore,
            confidence = null,
            confidenceWidth = 0,
            confidenceHeight = 0,
            confidenceRowStride = 0,
            width = width,
            height = height,
            relative = relativeMap(),
            calibration = calibration(),
            out = out,
            confidenceOut = ByteArray(width * height),
            spatialMask = wrongGeometry,
        )
        assertEquals(0, result.filledPixels)
        assertFalse(result.usedNetwork)
        assertEquals(width * height - 1, result.spatiallyRejectedPixels)
        assertEquals(2_000, out[0].toInt() and 0xFFFF)
        assertTrue(out.drop(1).all { it == 0.toShort() })
    }

    @Test
    fun `non finite usable calibration metadata cannot open network fills`() {
        val mask = SpatialCalibrationGuard.evaluate(
            samples(), calibration().copy(scale = Float.NaN), config,
        )
        assertEquals(config.columns * config.rows, mask.rejectedRegionCount)
        val result = AiDepthFusion.fuse(
            arcoreMm = ShortArray(width * height),
            confidence = null,
            confidenceWidth = 0,
            confidenceHeight = 0,
            confidenceRowStride = 0,
            width = width,
            height = height,
            relative = relativeMap(),
            calibration = calibration().copy(scale = Float.NaN),
            out = ShortArray(width * height),
            confidenceOut = ByteArray(width * height),
            spatialMask = mask,
        )
        assertEquals(0, result.filledPixels)
        assertEquals(width * height, result.spatiallyRejectedPixels)

        val overflowingTolerance = SpatialCalibrationGuard.evaluate(
            samples(), calibration().copy(inlierRmsDisparity = Float.MAX_VALUE), config,
        )
        assertEquals(config.columns * config.rows, overflowingTolerance.rejectedRegionCount)
    }

    @Test
    fun `sample coordinates stay aligned with confidence filtered measurements`() {
        val arcore = ShortArray(width * height)
        val first = 1 * width + 2
        val second = 4 * width + 6
        arcore[first] = 1_500
        arcore[second] = 2_500
        val confidence = ByteArray(width * height)
        confidence[first] = 0xFF.toByte()
        confidence[second] = 0xFF.toByte()
        val relative = RelativeDepthMap(
            FloatArray(width * height) { it.toFloat() / (width * height) },
            width, height, 0, "test",
        )
        val samples = AiDepthFusion.calibrationSamples(
            arcoreMm = arcore,
            confidence = confidence,
            confidenceWidth = width,
            confidenceHeight = height,
            confidenceRowStride = width,
            width = width,
            height = height,
            relative = relative,
            stride = 1,
        )
        assertEquals(2, samples.relativeValues.size)
        assertArrayEquals(intArrayOf(2, 6), samples.pixelX)
        assertArrayEquals(intArrayOf(1, 4), samples.pixelY)
        assertEquals(1.5f, samples.metricMeters[0], 0f)
        assertEquals(2.5f, samples.metricMeters[1], 0f)
        assertEquals(
            relative.sampleNormalized(2.5f / width, 1.5f / height),
            samples.relativeValues[0],
            1e-6f,
        )
    }
}
