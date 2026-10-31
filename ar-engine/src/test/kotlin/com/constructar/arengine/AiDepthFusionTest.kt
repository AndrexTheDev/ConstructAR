package com.constructar.arengine

import com.constructar.arengine.ai.AiDepthFusion
import com.constructar.arengine.ai.MetricDepthCalibrator
import com.constructar.arengine.ai.RelativeDepthMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fusion policy: the network may fill holes, it may never overwrite a measured sample.
 */
class AiDepthFusionTest {

    private val width = 8
    private val height = 6

    private fun usableCalibration() = MetricDepthCalibrator.Calibration(
        scale = 1f,
        shift = 0f,
        inlierRatio = 0.9f,
        inlierRmsDisparity = 0.01f,
        sampleCount = 1000,
        isUsable = true,
    )

    /** A depth map where the left half is measured at 2 m and the right half is a hole. */
    private fun halfMeasured(): ShortArray = ShortArray(width * height) { index ->
        if ((index % width) < width / 2) 2000 else 0
    }

    /** Relative disparity for 1 m everywhere (so the calibrated depth is 1 m). */
    private fun flatRelative(): RelativeDepthMap = RelativeDepthMap(FloatArray(width * height) { 1f }, width, height, 12, "test")

    @Test
    fun `holes are filled and measured pixels are preserved`() {
        val out = ShortArray(width * height)
        val result = AiDepthFusion.fuse(
            arcoreMm = halfMeasured(),
            confidence = null,
            confidenceWidth = 0,
            confidenceHeight = 0,
            confidenceRowStride = 0,
            width = width,
            height = height,
            relative = flatRelative(),
            calibration = usableCalibration(),
            out = out,
        )
        assertEquals(width * height / 2, result.measuredPixels)
        assertEquals(width * height / 2, result.filledPixels)
        assertTrue(result.usedNetwork)
        // Measured half untouched.
        for (index in 0 until width * height) {
            val column = index % width
            if (column < width / 2) assertEquals(2000, out[index].toInt() and 0xFFFF)
            else assertEquals(1000, out[index].toInt() and 0xFFFF) // 1 m from the calibration
        }
    }

    @Test
    fun `low confidence samples count as holes`() {
        val out = ShortArray(width * height)
        val confidence = ByteArray(width * height) { 10 } // far below the 96 floor
        val result = AiDepthFusion.fuse(
            arcoreMm = ShortArray(width * height) { 2000 },
            confidence = confidence,
            confidenceWidth = width,
            confidenceHeight = height,
            confidenceRowStride = width,
            width = width,
            height = height,
            relative = flatRelative(),
            calibration = usableCalibration(),
            out = out,
        )
        assertEquals(0, result.measuredPixels)
        assertEquals(width * height, result.filledPixels)
    }

    @Test
    fun `an unusable calibration passes ARCore data through untouched`() {
        val out = ShortArray(width * height)
        val result = AiDepthFusion.fuse(
            arcoreMm = halfMeasured(),
            confidence = null,
            confidenceWidth = 0,
            confidenceHeight = 0,
            confidenceRowStride = 0,
            width = width,
            height = height,
            relative = flatRelative(),
            calibration = MetricDepthCalibrator.Calibration.IDENTITY,
            out = out,
        )
        assertFalse(result.usedNetwork)
        assertEquals(0, result.filledPixels)
        for (index in 0 until width * height) {
            assertEquals(halfMeasured()[index].toInt() and 0xFFFF, out[index].toInt() and 0xFFFF)
        }
    }

    @Test
    fun `filled depths are clamped to the plausible range`() {
        val out = ShortArray(width * height)
        val farRelative = RelativeDepthMap(FloatArray(width * height) { 0.0001f }, width, height, 12, "test")
        val calibration = MetricDepthCalibrator.Calibration(1f, 0f, 1f, 0f, 100, isUsable = true)
        val result = AiDepthFusion.fuse(
            arcoreMm = ShortArray(width * height),
            confidence = null,
            confidenceWidth = 0,
            confidenceHeight = 0,
            confidenceRowStride = 0,
            width = width,
            height = height,
            relative = farRelative,
            calibration = calibration,
            out = out,
        )
        assertTrue(result.usedNetwork)
        // 1/0.0001 = 10 000 m → clamped to 8 m.
        assertTrue(out.all { (it.toInt() and 0xFFFF) in 250..8_000 })
    }

    @Test
    fun `calibration samples come only from confident measurable pixels`() {
        val depth = ShortArray(width * height) { index -> if (index % 3 == 0) 0 else 1500 }
        val confidence = ByteArray(width * height) { if (it % 2 == 0) 255 else 10 }
        val (relative, metric) = AiDepthFusion.calibrationSamples(
            arcoreMm = depth,
            confidence = confidence,
            confidenceWidth = width,
            confidenceHeight = height,
            confidenceRowStride = width,
            width = width,
            height = height,
            relative = flatRelative(),
            minConfidence = 96,
            stride = 1,
        )
        assertEquals(relative.size, metric.size)
        assertTrue(metric.isNotEmpty())
        assertTrue(metric.all { it > 0f })
    }
}
