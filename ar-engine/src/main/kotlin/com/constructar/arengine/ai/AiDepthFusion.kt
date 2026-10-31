/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.ai

import kotlin.math.max

/**
 * Fuses a monocular relative-depth map into ARCore's sparse metric depth.
 *
 * ## Why fusion, and what it is allowed to touch
 *
 * ARCore depth is *metric* but sparse and holey: invalid wherever the confidence is low, and
 * missing entirely on devices without the Depth API. Depth Anything V2 is *dense* but only
 * relative. Combining them is only safe under one rule:
 *
 * > **ARCore always wins where it has a confident sample.**
 *
 * The network is never allowed to overwrite a measured value — it only fills the holes. That
 * keeps the measured geometry (walls, cable trays) metric to ARCore's accuracy, while giving
 * the TSDF continuity on low-texture surfaces, which is where the holes appear in the first
 * place.
 *
 * ## Calibration
 *
 * Both maps are inverses of depth, so the network's output is converted with the affine
 * `1/z = s·d + t` fit by [MetricDepthCalibrator] on the *same frame*. No calibration, no
 * fusion: [fuse] refuses a calibration whose inlier statistics failed, because filling holes
 * with an uncalibrated map moves surfaces by metres.
 */
object AiDepthFusion {

    data class Result(
        /** Depth array that was written (same instance as passed in `out`). */
        val depthMm: ShortArray,
        /** Pixels copied from ARCore (metric, measured). */
        val measuredPixels: Int,
        /** Pixels filled from the network. */
        val filledPixels: Int,
        /** True when the fusion actually contributed (some pixels filled). */
        val usedNetwork: Boolean,
    ) {
        val fillRatio: Float
            get() = if (measuredPixels + filledPixels == 0) 0f else filledPixels.toFloat() / (measuredPixels + filledPixels)
    }

    /**
     * Writes the fused depth into [out] (millimetres, tightly packed).
     *
     * @param arcoreMm   ARCore depth for the frame, rows × width, 0 = invalid.
     * @param confidence ARCore confidence for [arcoreMm] (160×120), or null to treat every
     *                   non-zero sample as valid.
     * @param relative   network output, normalised to 0..1.
     * @param calibration affine fit for this frame.
     * @param minConfidence ARCore confidence floor for a sample to count as "measured".
     * @param minDepthMm / maxDepthMm clamp applied to network-filled values, so a bad pixel
     *        cannot place a surface 40 m away and wreck the TSDF bounds.
     */
    fun fuse(
        arcoreMm: ShortArray,
        confidence: ByteArray?,
        confidenceWidth: Int,
        confidenceHeight: Int,
        confidenceRowStride: Int,
        width: Int,
        height: Int,
        relative: RelativeDepthMap,
        calibration: MetricDepthCalibrator.Calibration,
        out: ShortArray,
        minConfidence: Int = 96,
        minDepthMm: Int = 250,
        maxDepthMm: Int = 8_000,
    ): Result {
        require(out.size >= width * height) { "output buffer is smaller than the depth frame" }
        require(arcoreMm.size >= width * height) { "depth buffer is smaller than the frame" }

        var measured = 0
        var filled = 0
        if (!calibration.isUsable) {
            // No trustworthy calibration: pass ARCore's data through untouched.
            System.arraycopy(arcoreMm, 0, out, 0, width * height)
            for (i in 0 until width * height) if (readMm(arcoreMm, i) > 0) measured++
            return Result(out, measured, 0, usedNetwork = false)
        }

        val hasConfidence = confidence != null && confidenceWidth > 0 && confidenceHeight > 0
        val cxScale = if (hasConfidence) confidenceWidth.toFloat() / width else 1f
        val cyScale = if (hasConfidence) confidenceHeight.toFloat() / height else 1f

        for (y in 0 until height) {
            val v = (y + 0.5f) / height
            val confidenceRow = if (hasConfidence) (y * cyScale).toInt().coerceIn(0, confidenceHeight - 1) else 0
            for (x in 0 until width) {
                val index = y * width + x
                val arcoreValue = readMm(arcoreMm, index)
                val arcoreConfidence = if (hasConfidence) {
                    val cx = (x * cxScale).toInt().coerceIn(0, confidenceWidth - 1)
                    confidence!![confidenceRow * confidenceRowStride + cx].toInt() and 0xFF
                } else 255

                if (arcoreValue > 0 && arcoreConfidence >= minConfidence) {
                    out[index] = arcoreMm[index]
                    measured++
                    continue
                }

                // Hole: ask the network, in metric terms via the calibrated affine map.
                val relativeValue = relative.sampleNormalized((x + 0.5f) / width, v)
                val metric = calibration.depthMeters(relativeValue)
                if (!metric.isFinite()) {
                    out[index] = 0
                    continue
                }
                val mm = (metric * 1000f).toInt().coerceIn(minDepthMm, maxDepthMm)
                out[index] = mm.toShort()
                filled++
            }
        }
        return Result(out, measured, filled, usedNetwork = filled > 0)
    }

    /**
     * Samples `(relative, metric)` pairs for the calibration fit.
     *
     * Uses a strided grid (every [stride]-th pixel in each axis) because the fit needs a few
     * thousand samples, not 300 000 — and doing it densely would cost more than the inference.
     * Only pixels with a confident ARCore depth are returned: those are the ground truth.
     */
    fun calibrationSamples(
        arcoreMm: ShortArray,
        confidence: ByteArray?,
        confidenceWidth: Int,
        confidenceHeight: Int,
        confidenceRowStride: Int,
        width: Int,
        height: Int,
        relative: RelativeDepthMap,
        minConfidence: Int = 96,
        stride: Int = 8,
    ): Pair<FloatArray, FloatArray> {
        val maxSamples = ((width + stride - 1) / stride) * ((height + stride - 1) / stride)
        val relativeValues = FloatArray(maxSamples)
        val metricValues = FloatArray(maxSamples)
        var count = 0

        val hasConfidence = confidence != null && confidenceWidth > 0 && confidenceHeight > 0
        for (y in 0 until height step stride) {
            for (x in 0 until width step stride) {
                val index = y * width + x
                val mm = readMm(arcoreMm, index)
                if (mm <= 0) continue
                if (hasConfidence) {
                    val cx = (x.toFloat() / width * confidenceWidth).toInt().coerceIn(0, confidenceWidth - 1)
                    val cy = (y.toFloat() / height * confidenceHeight).toInt().coerceIn(0, confidenceHeight - 1)
                    val c = confidence!![cy * confidenceRowStride + cx].toInt() and 0xFF
                    if (c < minConfidence) continue
                }
                relativeValues[count] = relative.sampleNormalized((x + 0.5f) / width, (y + 0.5f) / height)
                metricValues[count] = mm / 1000f
                count++
            }
        }
        return relativeValues.copyOf(count) to metricValues.copyOf(count)
    }

    /** Reads a 16-bit millimetre sample as an unsigned value. */
    private fun readMm(buffer: ShortArray, index: Int): Int = buffer[index].toInt() and 0xFFFF

    /** Convenience: how many samples a calibration needs, exposed for the UI hint. */
    fun hasEnoughSamples(sampleCount: Int, minSamples: Int = 400): Boolean = max(0, sampleCount) >= minSamples
}
