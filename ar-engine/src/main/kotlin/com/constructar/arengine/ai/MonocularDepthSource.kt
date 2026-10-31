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
 * copy YUV planes  (memcpy)           Depth Anything V2 (NNAPI/GPU/CPU)
 * copy depth + confidence             sample ARCore↔network pairs
 * submit ───────────────────────────► MetricDepthCalibrator (IRLS + Huber)
 *                                     AiDepthFusion → fused millimetre depth
 *                                     onFused(depth, w, h, timestamp)
 * ```
 *
 * Why the copies: the ARCore `Image` and the pipeline's pooled depth buffer both become
 * invalid moments after the frame they belong to. The copies cost ~0.25 ms on the render
 * thread (two `memcpy`s) and buy a worker job that can take 80 ms without touching anything
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
    /** Called on the worker thread when a fused depth frame is ready. */
    private val onFused: (depthMm: ShortArray, width: Int, height: Int, timestampNs: Long) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mono-depth").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val latest = AtomicReference<MonocularDepthEstimator?>(null)
    private val pixels = IntArray(640 * 480)
    @Volatile private var fusedBuffer = ShortArray(640 * 480)

    @Volatile private var lastRunMillis = 0L

    /** Latest successful calibration, for the diagnostics screen. */
    @Volatile var lastCalibration: MetricDepthCalibrator.Calibration? = null
        private set

    @Volatile var lastFillRatio: Float = 0f
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
            return
        }
        executor.execute {
            latest.set(MonocularDepthEstimator.create(context, config))
            isRunning = latest.get() != null
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
        val yuv = YuvFrame.capture(frame)
        val depthCopy = snapshot.depthMm.copyOf()
        val confidenceCopy = snapshot.confidence?.copyOf()
        if (yuv == null) {
            busy.set(false)
            return
        }

        executor.execute {
            try {
                runFusion(
                    estimator = estimator,
                    yuv = yuv,
                    arcoreMm = depthCopy,
                    confidence = confidenceCopy,
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

        val bitmap = yuv.toBitmap(pixels)
        val relative = try {
            estimator.estimate(bitmap)
        } finally {
            bitmap.recycle()
        } ?: return

        val (relativeSamples, metricSamples) = AiDepthFusion.calibrationSamples(
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

        val calibration = MetricDepthCalibrator().calibrate(relativeSamples, metricSamples)
        lastCalibration = calibration
        if (!calibration.isUsable) {
            Log.i(TAG, "calibration refused (inliers=${(calibration.inlierRatio * 100).toInt()} %) — ARCore depth only")
            return
        }

        if (fusedBuffer.size < width * height) {
            // Only reallocates when the device switches depth resolution; then it keeps it.
            fusedBuffer = ShortArray(width * height)
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
            minConfidence = minConfidence,
        )
        lastFillRatio = result.fillRatio
        isRunning = true
        if (result.usedNetwork) {
            onFused(fusedBuffer, width, height, yuv.timestampNs)
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
