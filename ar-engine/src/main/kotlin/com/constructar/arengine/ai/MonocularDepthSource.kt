/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.ai

import android.content.Context
import android.util.Log
import com.constructar.arengine.depth.DepthFrameSnapshot
import com.google.ar.core.Frame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates the optional monocular-depth path:
 *
 * ```
 * render thread                       inference worker (1 thread, ~2 Hz)
 * ─────────────                       ─────────────────────────────────
 * throttle to every 500 ms            YUV → ARGB → Bitmap
 * copy YUV planes  (memcpy)           Depth Anything V2 (XNNPACK, 4 threads)
 * copy depth + confidence             sample ARCore↔network pairs
 * submit ───────────────────────────► MetricDepthCalibrator (RANSAC + MAD + Tukey IRLS)
 *                                     SpatialCalibrationGuard → regional fill veto
 *                                     AiDepthFusion → fused millimetre depth
 *                                     onFused(depth, confidence, w, h, source geometry)
 * ```
 *
 * Why the copies: the ARCore `Image` and the pipeline's pooled depth buffer both become
 * invalid moments after the frame they belong to. The copies cost ~0.25 ms on the render
 * thread (two `memcpy`s into [ScratchPlanes], which the previous pass already allocated — the
 * delegate ladder in this diagram is gone too: the graph runs on XNNPACK, see
 * `MonocularDepthEstimator`) and buy a worker job that can take 80 ms without touching anything
 * ARCore owns.
 *
 * If anything fails — model missing, delegate unavailable, calibration refused — [onFused] is
 * simply never called and the app runs on ARCore depth alone. This path is an enhancement, not
 * a dependency.
 */
class MonocularDepthSource(
    private val context: Context,
    private val config: MonocularDepthEstimator.Config = MonocularDepthEstimator.Config(),
    private val intervalMillis: Long = 500L,
    private val minConfidence: Int = 96,
    /**
     * Called on the worker thread when a fused depth frame is ready. [DepthFrameSnapshot] is the
     * immutable acquisition metadata of the exact frame used for inference; the consumer copies
     * its pose/intrinsics before the next integration. Its pooled pixel arrays are not read here.
     */
    private val onFused: (
        depthMm: ShortArray,
        confidence: ByteArray,
        width: Int,
        height: Int,
        source: DepthFrameSnapshot,
    ) -> Unit,
    /**
     * Called once, on the worker thread, with whether the model loaded.
     *
     * The screen has to be able to say which depth it is measuring with, and `isRunning` is set
     * inside the loader's thread: without this callback the first composable to read it would see
     * `false` and never be recomposed. The state flips exactly once, so one callback is the whole
     * protocol - a flow would be a second source of truth for the same boolean (finding 221).
     */
    private val onModelState: ((loaded: Boolean) -> Unit)? = null,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mono-depth").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val latest = AtomicReference<MonocularDepthEstimator?>(null)
    /** Reused ARGB buffer for YUV conversion; grown only if the camera resolution grows. */
    private var pixels = IntArray(640 * 480)
    /** Reused copies of the frame's depth and confidence planes (finding 173). */
    private val scratch = ScratchPlanes()
    @Volatile private var fusedBuffer = ShortArray(640 * 480)
    @Volatile private var fusedConfidenceBuffer = ByteArray(640 * 480)

    @Volatile private var lastRunMillis = 0L

    /** Latest successful calibration, for the diagnostics screen. */
    @Volatile var lastCalibration: MetricDepthCalibrator.Calibration? = null
        private set

    @Volatile var lastFillRatio: Float = 0f
        private set

    @Volatile var lastSpatiallyRejectedPixels: Int = 0
        private set

    @Volatile var lastSpatialRejectedRegions: Int = 0
        private set

    /** True once the model has been loaded and at least one inference succeeded. */
    @Volatile var isRunning: Boolean = false
        private set

    val backend: String get() = latest.get()?.lastBackend ?: "unavailable"

    /** Loads the model on first use; safe to call repeatedly. */
    fun warmUp() {
        if (latest.get() != null) return
        if (!MonocularDepthEstimator.isModelPresent(context, config)) {
            Log.i(TAG, "no monocular depth model bundled — using ARCore depth only")
            onModelState?.invoke(false)
            return
        }
        executor.execute {
            latest.set(MonocularDepthEstimator.create(context, config))
            val loaded = latest.get() != null
            isRunning = loaded
            onModelState?.invoke(loaded)
        }
    }

    /**
     * Called from the render thread. Cheap when it decides to do nothing, which is the common
     * case (12 out of 13 calls at 30 fps).
     */
    fun onFrame(frame: Frame, snapshot: DepthFrameSnapshot) {
        val estimator = latest.get() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRunMillis < intervalMillis) return
        if (!busy.compareAndSet(false, true)) return
        lastRunMillis = now

        // ── Copies taken here, while the ARCore frame is still valid ────────────────────
        // The camera frame first: a capture that fails must not cost a 600 kB copy.
        val yuv = YuvFrame.capture(frame)
        if (yuv == null) {
            busy.set(false)
            return
        }
        // These two are copied into reused planes — `busy` is set, so no other pass is holding
        // them (finding 173).
        val arcoreMm = scratch.depthFrom(snapshot)
        val confidence = scratch.confidenceFrom(snapshot)

        executor.execute {
            try {
                runFusion(
                    estimator = estimator,
                    yuv = yuv,
                    arcoreMm = arcoreMm,
                    confidence = confidence,
                    snapshot = snapshot,
                )
            } catch (error: Throwable) {
                Log.w(TAG, "monocular depth pass failed", error)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun runFusion(
        estimator: MonocularDepthEstimator,
        yuv: YuvFrame,
        arcoreMm: ShortArray,
        confidence: ByteArray?,
        snapshot: DepthFrameSnapshot,
    ) {
        val width = snapshot.width
        val height = snapshot.height
        if (arcoreMm.size < width * height) return

        if (pixels.size < yuv.width * yuv.height) {
            pixels = IntArray(yuv.width * yuv.height)
        }
        val bitmap = yuv.toBitmap(pixels)
        val relative = try {
            estimator.estimate(bitmap)
        } finally {
            bitmap.recycle()
        } ?: return

        val samples = AiDepthFusion.calibrationSamples(
            arcoreMm = arcoreMm,
            confidence = confidence,
            confidenceWidth = snapshot.confidenceWidth,
            confidenceHeight = snapshot.confidenceHeight,
            confidenceRowStride = snapshot.confidenceRowStride,
            width = width,
            height = height,
            relative = relative,
            minConfidence = minConfidence,
        )

        val calibration = MetricDepthCalibrator().calibrate(samples.relativeValues, samples.metricMeters)
        lastCalibration = calibration
        if (!calibration.isUsable) {
            lastFillRatio = 0f
            lastSpatiallyRejectedPixels = 0
            lastSpatialRejectedRegions = 0
            Log.i(TAG, "calibration refused (inliers=${(calibration.inlierRatio * 100).toInt()} %) — ARCore depth only")
            return
        }
        val spatialMask = SpatialCalibrationGuard.evaluate(samples, calibration)
        lastSpatialRejectedRegions = spatialMask.rejectedRegionCount

        if (fusedBuffer.size < width * height) {
            // Only reallocates when the device switches depth resolution; then it keeps both.
            fusedBuffer = ShortArray(width * height)
            fusedConfidenceBuffer = ByteArray(width * height)
        }

        val result = AiDepthFusion.fuse(
            arcoreMm = arcoreMm,
            confidence = confidence,
            confidenceWidth = snapshot.confidenceWidth,
            confidenceHeight = snapshot.confidenceHeight,
            confidenceRowStride = snapshot.confidenceRowStride,
            width = width,
            height = height,
            relative = relative,
            calibration = calibration,
            out = fusedBuffer,
            confidenceOut = fusedConfidenceBuffer,
            minConfidence = minConfidence,
            spatialMask = spatialMask,
        )
        lastFillRatio = result.fillRatio
        lastSpatiallyRejectedPixels = result.spatiallyRejectedPixels
        isRunning = true
        if (result.usedNetwork) {
            onFused(fusedBuffer, fusedConfidenceBuffer, width, height, snapshot)
        }
    }

    fun close() {
        executor.shutdown()
        latest.getAndSet(null)?.close()
        isRunning = false
    }

    companion object {
        private const val TAG = "MonocularDepthSource"
    }
}
