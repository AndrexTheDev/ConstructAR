/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.ai.MetricDepthCalibrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The AI depth path is only useful if its metric calibration is exact on synthetic data and
 * *refuses* to fit on garbage. Both are asserted here.
 */
class MetricDepthCalibratorTest {

    @Test
    fun `recovers a known affine disparity mapping`() {
        val random = Random(7)
        val trueScale = 0.9f
        val trueShift = 0.05f
        val count = 2000
        val relative = FloatArray(count)
        val metric = FloatArray(count)
        for (i in 0 until count) {
            val depth = 0.4f + random.nextFloat() * 4f
            val disparity = trueScale / depth + trueShift
            // The network output is the normalised disparity and carries a little noise.
            relative[i] = disparity + (random.nextFloat() - 0.5f) * 0.002f
            metric[i] = depth
        }

        val calibration = MetricDepthCalibrator().calibrate(relative, metric)
        assertTrue(calibration.isUsable)
        // The calibrator fits `1/z ≈ scale·relative + shift`, so the network's own affine
        // parameters come back inverted: scale ≈ 1/s, shift ≈ −t/s.
        assertEquals(1f / trueScale, calibration.scale, 0.02f)
        assertEquals(-trueShift / trueScale, calibration.shift, 0.01f)
        // Round-tripping a depth through the calibration must be accurate to ~2 %.
        val probeDepth = 2.5f
        val probeRelative = trueScale / probeDepth + trueShift
        assertEquals(probeDepth, calibration.depthMeters(probeRelative), probeDepth * 0.02f)
    }

    @Test
    fun `refuses a fit on uncorrelated noise`() {
        val random = Random(11)
        val count = 2000
        val relative = FloatArray(count) { random.nextFloat() }
        val metric = FloatArray(count) { 0.5f + random.nextFloat() * 4f }
        val calibration = MetricDepthCalibrator().calibrate(relative, metric)
        assertFalse("a noise fit must not be trusted", calibration.isUsable)
    }

    @Test
    fun `rejects when there are too few samples`() {
        val calibration = MetricDepthCalibrator().calibrate(FloatArray(10) { 1f }, FloatArray(10) { 2f })
        assertFalse(calibration.isUsable)
    }

    @Test
    fun `ignores samples outside the plausible depth range`() {
        val random = Random(3)
        val count = 1500
        val relative = FloatArray(count)
        val metric = FloatArray(count)
        for (i in 0 until count) {
            val depth = 1f + random.nextFloat() * 3f
            relative[i] = 1.2f / depth + 0.02f
            metric[i] = depth
        }
        // Poison the tail with impossible depths: they must not move the fit at all.
        for (i in 0 until 200) {
            relative[i] = 999f
            metric[i] = if (i % 2 == 0) 0.01f else 100f
        }
        val calibration = MetricDepthCalibrator().calibrate(relative, metric)
        assertEquals(1f / 1.2f, calibration.scale, 0.05f)
        assertTrue(calibration.inlierRatio > 0.5f)
    }

    @Test
    fun `depth clamp never returns a non-positive or infinite depth`() {
        val calibration = MetricDepthCalibrator.Calibration(scale = 1f, shift = -10f, 1f, 0f, 100, isUsable = true)
        val depth = calibration.depthMeters(0.1f)
        assertTrue(depth.isFinite())
        assertTrue(depth > 0f)
    }
}
