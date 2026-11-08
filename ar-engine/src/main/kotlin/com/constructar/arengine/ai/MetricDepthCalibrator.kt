/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Turns Depth Anything V2's **relative** depth into metric depth using ARCore's sparse
 * metric depth as the calibration signal.
 *
 * ## Why affine, and why on inverse depth
 *
 * Monocular relative-depth networks regress a *disparity-like* field `d ∈ (0, 1]`: scale and
 * shift are arbitrary per image. Depth Anything V2 (like MiDaS) is trained so that
 *
 *     disparity ≈ s · z⁻¹ + t
 *
 * is affine — i.e. the network output and `1/realDepth` are related by an affine map. Fitting
 * that map to ARCore's sparse but metric samples is therefore the correct least-squares model;
 * fitting directly against `depth` produces systematic curvature error at the near end (where
 * 5 % depth error is most visible for cable lengths).
 *
 * The fitted model, and therefore the meaning of [Calibration.scale]/[Calibration.shift], is
 *
 *     1 / z ≈ scale · relative + shift
 *
 * i.e. the *inverse* of the network's own `relative ≈ s·z⁻¹ + t`. For that network,
 * `scale ≈ 1/s` and `shift ≈ −t/s`; [Calibration.depthMeters] inverts the map back to metres.
 * Note that `shift` is legitimately negative — a positive-disparity network maps `1/z` down as
 * the relative output grows, which is exactly why only positivity of `scale` is checked.
 *
 * ## Robustness on construction sites
 *
 * ARCore depth is noisy on glass, mirrors and dark cable trays, and it is *wrong* wherever the
 * reflection of a corridor appears behind a window. A plain least-squares fit lets those
 * outliers bend the scale. [calibrate] therefore starts with deterministic RANSAC, estimates a
 * data-dependent residual scale from the median absolute deviation (MAD), and refines with
 * Tukey-biweight IRLS. Gross outliers get zero weight instead of merely a smaller one. The final
 * inlier ratio is measured independently so the caller can refuse the result ("re-scan, depth
 * too weak here") instead of silently measuring 8 % long.
 *
 * The class is pure Kotlin with no Android imports so it runs in host unit tests.
 */
class MetricDepthCalibrator(private val config: Config = Config()) {

    data class Config(
        /** Minimum number of (relative, metric) pairs required. */
        val minSamples: Int = 400,
        /** Reject samples closer/further than this (metres). */
        val minDepthMeters: Float = 0.25f,
        val maxDepthMeters: Float = 8f,
        /** Samples whose confidence is below this are ignored outright. */
        val minConfidence: Int = 96,
        /** Deterministic affine RANSAC attempts before IRLS. */
        val ransacIterations: Int = 64,
        /** Minimum relative-output separation of a RANSAC pair; avoids ill-conditioned seeds. */
        val ransacMinPairSeparation: Float = 0.02f,
        /** IRLS iterations after the RANSAC seed. */
        val irlsIterations: Int = 4,
        /** Inlier threshold in robust-standard-deviation units. */
        val huberK: Float = 1.5f,
        /** Tukey's standard redescending tuning constant; residuals beyond this get zero weight. */
        val tukeyK: Float = 4.685f,
        /** Sensor/model-noise floor in inverse-depth units; prevents a zero MAD degenerating IRLS. */
        val minResidualScaleDisparity: Float = 0.02f,
        /** Refuse the calibration when fewer samples survive as inliers. */
        val minInlierRatio: Float = 0.55f,
        /** Refuse when the inlier RMS in disparity units exceeds this. */
        val maxInlierRmsDisparity: Float = 0.35f,
        /** Clamp the assumed scale so a broken fit cannot invert the depth ordering. */
        val maxAbsScale: Float = 100f,
    )

    /** A calibrated affine map, applicable to a whole relative-depth map. */
    data class Calibration(
        val scale: Float,
        val shift: Float,
        val inlierRatio: Float,
        val inlierRmsDisparity: Float,
        val sampleCount: Int,
        /**
         * Whether the caller may use this calibration: the fit converged, `scale > 0` (so the
         * map stays monotonic and cannot invert depth ordering) and enough samples survived
         * as inliers. `shift` may be negative - see the class documentation for why.
         */
        val isUsable: Boolean,
    ) {
        /** Metric depth (metres) from a relative network output. */
        fun depthMeters(relative: Float): Float {
            val disparity = scale * relative + shift
            // Guard against a non-positive denominator: clamp to the nearest sane depth.
            if (disparity <= 1e-4f) return MAX_DEPTH_METERS
            return (1f / disparity).coerceIn(MIN_DEPTH_METERS, MAX_DEPTH_METERS)
        }

        companion object {
            const val MIN_DEPTH_METERS = 0.1f
            const val MAX_DEPTH_METERS = 40f
            val IDENTITY = Calibration(1f, 0f, 0f, 0f, 0, isUsable = false)
        }
    }

    /**
     * Least-squares calibration against ARCore samples.
     *
     * @param relativeValues network output per sample (same order as the other arrays).
     * @param metricMeters   ARCore depth in metres for the same samples.
     * @param confidence     0..255 ARCore depth confidence.
     */
    fun calibrate(
        relativeValues: FloatArray,
        metricMeters: FloatArray,
        confidence: IntArray? = null,
    ): Calibration {
        require(relativeValues.size == metricMeters.size) { "arrays must be aligned" }
        val n = relativeValues.size
        if (n < config.minSamples) return Calibration.IDENTITY

        // 1. Collect valid pairs and their initial weights.
        val rel = FloatArray(n)
        val inv = FloatArray(n)
        val weights = FloatArray(n)
        var count = 0
        for (i in 0 until n) {
            val z = metricMeters[i]
            val r = relativeValues[i]
            if (!z.isFinite() || !r.isFinite()) continue
            if (z < config.minDepthMeters || z > config.maxDepthMeters) continue
            if (confidence != null && confidence[i] < config.minConfidence) continue
            rel[count] = r
            inv[count] = 1f / z
            // ARCore confidence is quantised; use it as a soft weight (0.25 … 1).
            weights[count] = if (confidence == null) 1f else 0.25f + 0.75f * (confidence[i] / 255f)
            count++
        }
        if (count < config.minSamples) return Calibration.IDENTITY

        // 2. RANSAC finds the dominant affine basin before any least-squares fit can be bent by
        // gross outliers. Tukey IRLS then refines it; unlike plain Huber IRLS, its redescending
        // weight is exactly zero beyond the tuning cutoff and cannot walk back into that basin.
        var fit: Pair<Float, Float> = ransacSeed(rel, inv, weights, count)
            ?: return Calibration.IDENTITY
        val robust = FloatArray(count)
        val residuals = FloatArray(count)
        repeat(max(0, config.irlsIterations)) {
            for (i in 0 until count) residuals[i] = fit.first * rel[i] + fit.second - inv[i]
            val scale = robustScale(residuals)
            val tukeyScale = max(1e-6f, config.tukeyK * scale)
            for (i in 0 until count) {
                val u = abs(residuals[i]) / tukeyScale
                robust[i] = if (u >= 1f) 0f else {
                    val oneMinusSquare = 1f - u * u
                    weights[i] * oneMinusSquare * oneMinusSquare
                }
            }
            fit = weightedAffine(rel, inv, robust, count) ?: return Calibration.IDENTITY
        }

        // 3. Final inlier statistics on the unweighted residuals. The scale comes from the MAD,
        // never from an all-sample RMS: a few metre-sized errors must not define their own gate.
        for (i in 0 until count) residuals[i] = fit.first * rel[i] + fit.second - inv[i]
        val inlierCutoff = max(1e-6f, config.huberK * robustScale(residuals))
        var inliers = 0
        var squared = 0.0
        for (i in 0 until count) {
            val residual = abs(residuals[i])
            if (residual <= inlierCutoff) {
                inliers++
                squared += residual.toDouble() * residual
            }
        }
        val inlierRatio = inliers.toFloat() / count
        val inlierRms = if (inliers > 0) kotlin.math.sqrt(squared / inliers).toFloat() else Float.MAX_VALUE

        val plausible = fit.first > 1e-5f && fit.first < config.maxAbsScale &&
            fit.second.isFinite()
        return Calibration(
            scale = fit.first,
            shift = fit.second,
            inlierRatio = inlierRatio,
            inlierRmsDisparity = inlierRms,
            sampleCount = count,
            isUsable = plausible &&
                inlierRatio >= config.minInlierRatio &&
                inlierRms <= config.maxInlierRmsDisparity,
        )
    }

    /** Deterministic two-point RANSAC for the affine inverse-depth model. */
    private fun ransacSeed(
        rel: FloatArray,
        inv: FloatArray,
        baseWeights: FloatArray,
        count: Int,
    ): Pair<Float, Float>? {
        if (config.ransacIterations <= 0) return weightedAffine(rel, inv, baseWeights, count)
        val random = Random(RANSAC_SEED)
        val cutoff = max(1e-6f, config.huberK * config.minResidualScaleDisparity)
        var bestCost = Double.POSITIVE_INFINITY
        var bestInlierWeight = -1.0
        var best: Pair<Float, Float>? = null

        repeat(config.ransacIterations) {
            val first = random.nextInt(count)
            var second = random.nextInt(count)
            var attempts = 0
            while ((second == first || abs(rel[second] - rel[first]) < config.ransacMinPairSeparation) &&
                attempts < 11
            ) {
                second = random.nextInt(count)
                attempts++
            }
            if (second == first || abs(rel[second] - rel[first]) < config.ransacMinPairSeparation) {
                return@repeat
            }
            val scale = (inv[second] - inv[first]) / (rel[second] - rel[first])
            val shift = inv[first] - scale * rel[first]
            if (!scale.isFinite() || !shift.isFinite() || scale <= 1e-5f || scale >= config.maxAbsScale) {
                return@repeat
            }

            // Truncated least squares: every gross outlier has the same bounded cost, so a pair
            // sampled from a 25 % contamination tail cannot outscore the dominant surface model.
            var cost = 0.0
            var inlierWeight = 0.0
            for (i in 0 until count) {
                val residual = scale * rel[i] + shift - inv[i]
                val normalised = residual / cutoff
                val weight = baseWeights[i].toDouble()
                if (abs(normalised) <= 1f) {
                    cost += weight * normalised * normalised
                    inlierWeight += weight
                } else {
                    cost += weight
                }
            }
            if (cost < bestCost || (cost == bestCost && inlierWeight > bestInlierWeight)) {
                bestCost = cost
                bestInlierWeight = inlierWeight
                best = scale to shift
            }
        }
        return best ?: weightedAffine(rel, inv, baseWeights, count)
    }

    /** 1.4826·MAD, with a declared model-noise floor for an almost noiseless synthetic field. */
    private fun robustScale(residuals: FloatArray): Float {
        val centre = median(residuals)
        val deviations = FloatArray(residuals.size) { abs(residuals[it] - centre) }
        return max(config.minResidualScaleDisparity, MAD_TO_SIGMA * median(deviations))
    }

    private fun median(values: FloatArray): Float {
        val sorted = values.copyOf()
        sorted.sort()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) * 0.5f
        else sorted[middle]
    }

    /** Weighted least squares for `y = a·x + b`; null when degenerate. */
    private fun weightedAffine(x: FloatArray, y: FloatArray, w: FloatArray, count: Int): Pair<Float, Float>? {
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until count) {
            val wi = w[i].toDouble()
            if (wi <= 0.0) continue
            val xi = x[i].toDouble()
            val yi = y[i].toDouble()
            sw += wi
            sx += wi * xi
            sy += wi * yi
            sxx += wi * xi * xi
            sxy += wi * xi * yi
        }
        if (sw < 1e-6) return null
        val denominator = sw * sxx - sx * sx
        if (abs(denominator) < 1e-9) return null
        val a = ((sw * sxy - sx * sy) / denominator).toFloat()
        val b = ((sy - a * sx) / sw).toFloat()
        if (!a.isFinite() || !b.isFinite()) return null
        return a to b
    }

    companion object {
        private const val RANSAC_SEED = 0x5EED
        private const val MAD_TO_SIGMA = 1.4826f

        /**
         * Applies a calibration to a whole relative-depth map, in place, and returns the
         * metric depth map (metres). Values outside [Calibration.MIN_DEPTH_METERS,
         * Calibration.MAX_DEPTH_METERS] are clamped rather than dropped so the TSDF never
         * receives a hole where the network is confident.
         */
        fun applyInPlace(calibration: Calibration, relative: FloatArray): FloatArray {
            for (i in relative.indices) {
                relative[i] = calibration.depthMeters(relative[i])
            }
            return relative
        }

        /**
         * Least-squares *scale only* fit — used as a sanity cross-check (and by the unit
         * tests) when shift must stay at zero because the network already outputs
         * metric-aware values.
         */
        fun fitScale(relativeValues: FloatArray, metricMeters: FloatArray): Float? {
            var sxy = 0.0
            var sxx = 0.0
            for (i in relativeValues.indices) {
                val z = metricMeters[i]
                val r = relativeValues[i]
                if (!z.isFinite() || !r.isFinite() || z <= 0f) continue
                sxy += r.toDouble() / z
                sxx += r.toDouble() * r
            }
            if (sxx <= 1e-9) return null
            val scale = (sxy / sxx).toFloat()
            return if (scale.isFinite() && scale > 0f) scale else null
        }
    }
}

/** Result of one monocular inference pass, in the model's own units. */
data class RelativeDepthMap(
    val values: FloatArray,
    val width: Int,
    val height: Int,
    val inferenceMillis: Int,
    val backend: String,
) {
    init {
        require(values.size == width * height) { "values must be width×height" }
    }

    /** Bilinear sample; coordinates are normalised [0,1] with (0,0) at the top-left. */
    fun sampleNormalized(u: Float, v: Float): Float {
        val x = (u * (width - 1)).coerceIn(0f, (width - 1).toFloat())
        val y = (v * (height - 1)).coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        val top = values[y0 * width + x0] * (1 - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1 - fx) + values[y1 * width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }
}
