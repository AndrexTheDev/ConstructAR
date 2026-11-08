/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import android.media.Image
import android.util.Log
import com.constructar.arengine.depth.DepthBufferPool
import com.constructar.arengine.depth.DepthFrameSnapshot
import com.constructar.arengine.depth.DepthSource
import com.constructar.core.geo.DepthIntrinsics
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer

/** Requested acquisition policy; the snapshot still records which source actually succeeded. */
enum class DepthAcquisitionMode { PREFER_RAW, RAW_ONLY, FULL_ONLY }

private val rawThenFull = listOf(DepthSource.RAW, DepthSource.FULL)
private val rawOnly = listOf(DepthSource.RAW)
private val fullOnly = listOf(DepthSource.FULL)

/** Raw is attempted first only for the production default; explicit modes never cross APIs. */
internal fun acquisitionOrder(mode: DepthAcquisitionMode): List<DepthSource> = when (mode) {
    DepthAcquisitionMode.PREFER_RAW -> rawThenFull
    DepthAcquisitionMode.RAW_ONLY -> rawOnly
    DepthAcquisitionMode.FULL_ONLY -> fullOnly
}

/**
 * Copies an ARCore depth image into a stable [DepthFrameSnapshot] **on the render thread**.
 *
 * Raw Depth and Full Depth are different APIs. `acquireRawDepthImage16Bits()` is the only source
 * paired with `acquireRawDepthConfidenceImage()`. Full Depth is acquired through
 * `acquireDepthImage16Bits()` and carries no fabricated or mismatched Raw Confidence. Raw Depth is
 * sparse; Full Depth is ARCore's smoothed/reprojected image and may have an older image timestamp
 * than the camera frame. Both timestamps and the successful source are retained in the snapshot.
 *
 * The ARCore `Image` is valid only until the next `Frame.update()`, while the TSDF worker runs
 * asynchronously. The depth plane is therefore copied into a pooled `ShortArray`, and matching Raw
 * Confidence (when present) into a pooled `ByteArray`. Both are released by the snapshot's final
 * owner, so no render-loop allocation or ARCore-image lifetime crosses into the worker.
 */
class DepthCapture(private val pool: DepthBufferPool = DepthBufferPool(capacity = 3)) {

    /** Reusable stats container; [meanConfidence] is the legacy neutral 255 fallback without Raw Confidence. */
    data class Stats(
        val width: Int = 0,
        val height: Int = 0,
        val meanConfidence: Int = 0,
        val validPixels: Int = 0,
        val bestDepthMeters: Float = 0f,
        val source: DepthSource? = null,
        val confidenceAvailable: Boolean = false,
        val frameTimestampNs: Long = 0L,
        val depthTimestampNs: Long = 0L,
    ) {
        /** Diagnostic timing mismatch only; new Raw Depth is detected against the previous image. */
        val depthTimestampDiffersFromFrame: Boolean
            get() = depthTimestampNs > 0L && frameTimestampNs > 0L && depthTimestampNs != frameTimestampNs
    }

    private var lastStats = Stats()
    private var cachedDroppedFrames = 0

    val stats: Stats get() = lastStats
    val droppedFrames: Int get() = cachedDroppedFrames

    /**
     * Acquires the requested depth source and packages it for the worker.
     *
     * @param minConfidence pixels below this are zeroed in the fused depth selection.
     * @param mode raw-first production policy, or an explicit source for controlled A/B capture.
     * @return snapshot, or null when the selected source has no frame yet or acquisition fails.
     */
    fun capture(
        frame: Frame,
        minConfidence: Int,
        mode: DepthAcquisitionMode = DepthAcquisitionMode.PREFER_RAW,
    ): DepthFrameSnapshot? {
        var depthImage: Image? = null
        var source = DepthSource.FULL
        for (candidate in acquisitionOrder(mode)) {
            try {
                depthImage = when (candidate) {
                    DepthSource.RAW -> frame.acquireRawDepthImage16Bits()
                    DepthSource.FULL -> frame.acquireDepthImage16Bits()
                }
                source = candidate
                break
            } catch (_: NotYetAvailableException) {
                // Warm-up is normal. PREFER_RAW may try Full Depth; explicit modes fail closed.
            } catch (error: Throwable) {
                Log.w(TAG, "${candidate.name.lowercase()} depth acquisition failed", error)
                return null
            }
        }
        if (depthImage == null) {
            cachedDroppedFrames++
            return null
        }

        try {
            val width = depthImage.width
            val height = depthImage.height
            val plane = depthImage.planes[0]
            val buffer = plane.buffer
            val rowStrideElements = plane.rowStride / 2

            // Compact copy: downstream consumers always see rowStride == width.
            val target = pool.acquire(width * height)
            val shorts = buffer.asShortBuffer()
            copyRows(shorts, width, height, rowStrideElements, target)

            // Raw Confidence corresponds exclusively to Raw Depth, never to smoothed Full Depth.
            var confidenceBytes: ByteArray? = null
            var confidenceWidth = 0
            var confidenceHeight = 0
            var confidenceRowStride = 0
            if (source == DepthSource.RAW) {
                try {
                    frame.acquireRawDepthConfidenceImage().use { image ->
                        val confPlane = image.planes[0]
                        confidenceWidth = image.width
                        confidenceHeight = image.height
                        confidenceRowStride = confPlane.rowStride
                        val conf = pool.acquireConfidence(confidenceRowStride * confidenceHeight)
                        copyBytes(confPlane.buffer, confidenceRowStride, confidenceHeight, conf)
                        confidenceBytes = conf
                    }
                } catch (error: Throwable) {
                    Log.d(TAG, "no raw confidence image: ${error.message}")
                }
            }

            val intrinsics = depthIntrinsics(frame.camera, width, height)
            val pose = FloatArray(16)
            frame.camera.pose.toMatrix(pose, 0)
            val frameTimestampNs = frame.timestamp
            val depthTimestampNs = depthImage.timestamp

            lastStats = computeStats(
                target, width, height, confidenceBytes,
                confidenceWidth, confidenceHeight, confidenceRowStride, minConfidence,
            ).copy(
                source = source,
                confidenceAvailable = source == DepthSource.RAW && confidenceBytes != null,
                frameTimestampNs = frameTimestampNs,
                depthTimestampNs = depthTimestampNs,
            )

            // Read once, so release below cannot see a different plane than this snapshot used.
            val confidence = confidenceBytes
            return DepthFrameSnapshot(
                depthMm = target,
                width = width,
                height = height,
                rowStride = width,
                confidence = confidence,
                confidenceWidth = confidenceWidth,
                confidenceHeight = confidenceHeight,
                confidenceRowStride = confidenceRowStride,
                intrinsics = intrinsics,
                cameraToWorld = pose,
                timestampNs = frameTimestampNs,
                depthTimestampNs = depthTimestampNs,
                source = source,
                // Depth pixels are in sensor orientation; display rotation is applied by screen mapping.
                displayRotationDegrees = 0,
                minDepthMm = 180,
                onLastRelease = {
                    pool.release(target)
                    confidence?.let(pool::releaseConfidence)
                },
            )
        } finally {
            depthImage.close()
        }
    }

    /** Scales colour-camera intrinsics to the acquired depth image dimensions. */
    private fun depthIntrinsics(camera: Camera, width: Int, height: Int): DepthIntrinsics {
        val image = camera.imageIntrinsics
        val dimensions = image.imageDimensions
        val colorWidth = dimensions[0]
        val colorHeight = dimensions[1]
        val sx = width.toFloat() / colorWidth
        val sy = height.toFloat() / colorHeight
        return DepthIntrinsics(
            fx = image.focalLength[0] * sx,
            fy = image.focalLength[1] * sy,
            cx = image.principalPoint[0] * sx,
            cy = image.principalPoint[1] * sy,
        )
    }

    private fun copyRows(source: java.nio.ShortBuffer, width: Int, height: Int, rowStride: Int, target: ShortArray) {
        for (y in 0 until height) {
            source.position(y * rowStride)
            source.get(target, y * width, width)
        }
    }

    private fun copyBytes(source: ByteBuffer, rowStride: Int, height: Int, target: ByteArray) {
        for (y in 0 until height) {
            source.position(y * rowStride)
            source.get(target, y * rowStride, rowStride)
        }
    }

    private fun computeStats(
        depth: ShortArray,
        width: Int,
        height: Int,
        confidence: ByteArray?,
        confidenceWidth: Int,
        confidenceHeight: Int,
        confidenceRowStride: Int,
        minConfidence: Int,
    ): Stats {
        var valid = 0
        var nearest = Float.MAX_VALUE
        var confidenceSum = 0L
        var confidenceCount = 0
        val step = 4
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val mm = depth[y * width + x].toInt() and 0xFFFF
                if (mm <= 0) continue
                val conf = if (confidence != null && confidenceWidth > 0) {
                    val cx = (x.toFloat() / width * confidenceWidth).toInt().coerceIn(0, confidenceWidth - 1)
                    val cy = (y.toFloat() / height * confidenceHeight).toInt().coerceIn(0, confidenceHeight - 1)
                    confidence[cy * confidenceRowStride + cx].toInt() and 0xFF
                } else 255
                confidenceSum += conf
                confidenceCount++
                if (conf < minConfidence) continue
                valid++
                if (mm < nearest) nearest = mm.toFloat()
            }
        }
        return Stats(
            width = width,
            height = height,
            meanConfidence = if (confidenceCount > 0) (confidenceSum / confidenceCount).toInt() else 0,
            validPixels = valid,
            bestDepthMeters = if (nearest == Float.MAX_VALUE) 0f else nearest / 1000f,
        )
    }

    companion object {
        private const val TAG = "DepthCapture"
        const val PIXEL_STEP_NOMINAL = 3
        const val PIXEL_STEP_THROTTLED = 5
    }
}
