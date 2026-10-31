package com.constructar.arengine.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
 * `(s, t)` to ARCore's sparse but metric samples is therefore the correct least-squares
 * model; fitting directly against `depth` produces systematic curvature error at the near
 * end (where 5 % depth error is most visible for cable lengths).
 *
 * ## Robustness on construction sites
 *
 * ARCore depth is noisy on glass, mirrors and dark cable trays, and it is *wrong* wherever the
 * reflection of a corridor appears behind a window. A plain least-squares fit lets those
 * outliers bend the scale. [calibrate] therefore runs iteratively reweighted least squares
 * (IRLS) with a Huber weight, seeded by a confidence-weighted fit, and reports the inlier
 * ratio so the caller can refuse the result ("re-scan, depth too weak here") instead of
 * silently measuring 8 % long.
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
        /** IRLS iterations after the initial fit. */
        val irlsIterations: Int = 4,
        /** Huber threshold in normalised-residual units. */
        val huberK: Float = 1.5f,
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
        /** Biome-specific guard: a fit with s ≤ 0 or t ≤ 0 is physically implausible. */
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

        // 2. Weighted affine fit inv ≈ s·rel + t, then IRLS with Huber weights.
        var fit = weightedAffine(rel, inv, weights, count)
        if (fit == null) return Calibration.IDENTITY

        val robust = FloatArray(count)
        System.arraycopy(weights, 0, robust, 0, count)
        var rms = Float.MAX_VALUE
        repeat(max(0, config.irlsIterations)) {
            val current = weightedAffine(rel, inv, robust, count) ?: return Calibration.IDENTITY
            fit = current
            // Residuals → Huber weights.
            var sumSquared = 0.0
            for (i in 0 until count) {
                val residual = abs(current.first * rel[i] + current.second - inv[i])
                sumSquared += residual.toDouble() * residual
                val scale = max(1e-4f, residual)
                robust[i] = weights[i] * if (scale <= config.huberK * 0.02f) 1f
                else (config.huberK * 0.02f) / scale
            }
            rms = kotlin.math.sqrt(sumSquared / count).toFloat()
        }

        // 3. Final inlier statistics on the unweighted residuals.
        var inliers = 0
        var squared = 0.0
        for (i in 0 until count) {
            val residual = abs(fit.first * rel[i] + fit.second - inv[i])
            if (residual <= max(config.huberK * 0.02f, 2f * rms)) {
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
