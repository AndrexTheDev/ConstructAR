/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Confidence-qualified ARCore/network pairs together with their source-pixel locations. */
class CalibrationSampleSet(
    val relativeValues: FloatArray,
    val metricMeters: FloatArray,
    val pixelX: IntArray,
    val pixelY: IntArray,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    init {
        require(frameWidth > 0 && frameHeight > 0) { "sample frame dimensions must be positive" }
        require(relativeValues.size == metricMeters.size) { "calibration values must be aligned" }
        require(pixelX.size == relativeValues.size && pixelY.size == relativeValues.size) {
            "calibration coordinates must be aligned"
        }
        for (i in relativeValues.indices) {
            require(pixelX[i] in 0 until frameWidth && pixelY[i] in 0 until frameHeight) {
                "calibration coordinate is outside its acquisition frame"
            }
        }
    }

    /** Preserve the original two-value destructuring used by calibration-only callers. */
    operator fun component1(): FloatArray = relativeValues
    operator fun component2(): FloatArray = metricMeters
}

/** Spatial network-fill veto. Measured ARCore pixels never consult this mask. */
data class SpatialCalibrationMask(
    val frameWidth: Int,
    val frameHeight: Int,
    val columns: Int,
    val rows: Int,
    private val rejectedRegions: BooleanArray,
) {
    init {
        require(frameWidth > 0 && frameHeight > 0) { "mask frame dimensions must be positive" }
        require(columns > 0 && rows > 0) { "mask grid dimensions must be positive" }
        require(rejectedRegions.size == columns * rows) { "mask does not match its grid" }
    }

    val rejectedRegionCount: Int
        get() = rejectedRegions.count { it }

    /** A mask from another acquisition geometry fails closed for network fills. */
    fun rejectsPixel(x: Int, y: Int, width: Int, height: Int): Boolean {
        if (width != frameWidth || height != frameHeight || columns <= 0 || rows <= 0) return true
        if (x !in 0 until width || y !in 0 until height) return true
        val column = (x.toLong() * columns / width).toInt().coerceIn(0, columns - 1)
        val row = (y.toLong() * rows / height).toInt().coerceIn(0, rows - 1)
        return rejectedRegions[row * columns + column]
    }
}

/**
 * Detects local model failure that a globally robust affine fit can hide.
 *
 * Only regions with their own sufficient, confident measured evidence can be rejected. Sparse
 * regions stay neutral: absence of local comparisons is not treated as either confirmation or
 * contradiction. Both an inlier-ratio and an RMS gate are required so borderline residuals and
 * isolated outliers do not suppress otherwise useful hole filling.
 */
object SpatialCalibrationGuard {
    data class Config(
        val columns: Int = 4,
        val rows: Int = 3,
        val minRegionSamples: Int = 12,
        val minRegionConflicts: Int = 6,
        /** Reject only when strictly fewer than this fraction agree with the global fit. */
        val maxRegionInlierRatio: Float = 0.50f,
        /** Per-anchor conflict threshold, with a robust global-RMS adaptation. */
        val minResidualToleranceDisparity: Float = 0.06f,
        val residualToleranceRmsMultiplier: Float = 2f,
        /** Regional RMS over all valid anchors must also exceed this absolute model-error floor. */
        val minRegionRmsDisparity: Float = 0.08f,
        val minDepthMeters: Float = 0.25f,
        val maxDepthMeters: Float = 8f,
    ) {
        init {
            require(columns > 0 && rows > 0) { "spatial grid dimensions must be positive" }
            require(minRegionSamples > 0 && minRegionConflicts > 0) { "region evidence must be positive" }
            require(maxRegionInlierRatio.isFinite() && maxRegionInlierRatio in 0f..1f) {
                "region inlier ratio must be in 0..1"
            }
            require(minResidualToleranceDisparity.isFinite() && minResidualToleranceDisparity > 0f)
            require(residualToleranceRmsMultiplier.isFinite() && residualToleranceRmsMultiplier > 0f)
            require(minRegionRmsDisparity.isFinite() && minRegionRmsDisparity > 0f)
            require(
                minDepthMeters.isFinite() && minDepthMeters > 0f &&
                    maxDepthMeters.isFinite() && maxDepthMeters >= minDepthMeters,
            ) {
                "invalid calibration depth range"
            }
        }
    }

    fun evaluate(
        samples: CalibrationSampleSet,
        calibration: MetricDepthCalibrator.Calibration,
        config: Config = Config(),
    ): SpatialCalibrationMask {
        val rejected = BooleanArray(config.columns * config.rows)
        if (!calibration.isUsable) {
            return SpatialCalibrationMask(
                samples.frameWidth, samples.frameHeight, config.columns, config.rows, rejected,
            )
        }
        if (!calibration.scale.isFinite() || !calibration.shift.isFinite() ||
            !calibration.inlierRmsDisparity.isFinite() || calibration.inlierRmsDisparity < 0f
        ) {
            rejected.fill(true)
            return SpatialCalibrationMask(
                samples.frameWidth, samples.frameHeight, config.columns, config.rows, rejected,
            )
        }

        val counts = IntArray(rejected.size)
        val conflicts = IntArray(rejected.size)
        val squaredResiduals = DoubleArray(rejected.size)
        val residualTolerance = max(
            config.minResidualToleranceDisparity,
            config.residualToleranceRmsMultiplier * calibration.inlierRmsDisparity,
        )
        if (!residualTolerance.isFinite() || residualTolerance <= 0f) {
            rejected.fill(true)
            return SpatialCalibrationMask(
                samples.frameWidth, samples.frameHeight, config.columns, config.rows, rejected,
            )
        }
        for (i in samples.relativeValues.indices) {
            val relative = samples.relativeValues[i]
            val depth = samples.metricMeters[i]
            if (!relative.isFinite() || !depth.isFinite()) continue
            if (depth < config.minDepthMeters || depth > config.maxDepthMeters) continue
            val residual = calibration.scale * relative + calibration.shift - 1f / depth
            if (!residual.isFinite()) continue
            val x = samples.pixelX[i]
            val y = samples.pixelY[i]
            val column = (x.toLong() * config.columns / samples.frameWidth).toInt()
                .coerceIn(0, config.columns - 1)
            val row = (y.toLong() * config.rows / samples.frameHeight).toInt()
                .coerceIn(0, config.rows - 1)
            val region = row * config.columns + column
            counts[region]++
            squaredResiduals[region] += residual.toDouble() * residual
            if (abs(residual) > residualTolerance) conflicts[region]++
        }

        for (region in rejected.indices) {
            val count = counts[region]
            if (count < config.minRegionSamples || conflicts[region] < config.minRegionConflicts) continue
            val inlierRatio = (count - conflicts[region]).toFloat() / count
            if (inlierRatio >= config.maxRegionInlierRatio) continue
            val rms = sqrt(squaredResiduals[region] / count).toFloat()
            rejected[region] = rms.isFinite() && rms > config.minRegionRmsDisparity
        }
        return SpatialCalibrationMask(
            samples.frameWidth, samples.frameHeight, config.columns, config.rows, rejected,
        )
    }
}
