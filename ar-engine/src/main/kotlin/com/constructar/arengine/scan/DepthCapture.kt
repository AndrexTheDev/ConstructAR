/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import android.util.Log
import com.constructar.arengine.depth.DepthBufferPool
import com.constructar.arengine.depth.DepthFrameSnapshot
import com.constructar.core.geo.DepthIntrinsics
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer

/**
 * Copies ARCore's raw-depth frame into a stable [DepthFrameSnapshot] **on the render thread**.
 *
 * ## Why the copy is unavoidable (and why it is cheap)
 *
 * ARCore's `Image` is only valid until the next `Frame.update()`; the TSDF worker runs on a
 * different thread and may still be busy. We therefore copy the depth plane (640×480×2 B ≈
 * 600 kB) into a pooled `ShortArray`, and the confidence plane (160×120 B ≈ 19 kB) into a pooled
 * `ByteArray` — both out of [DepthBufferPool]. That is a `memcpy` of ~0.15 ms — an order of
 * magnitude cheaper than the integration it enables — and it never allocates after the first few
 * frames. The confidence plane was the exception until finding 172: 19 kB per frame, 30 times a
 * second, is 0.6 MB/s of garbage in the render loop this class exists to keep clean.
 *
 * ## Why not `acquireDepthImageMeters`
 *
 * ARCore has no `acquireDepthImageMeters`; the API is `Frame.acquireDepthImage16Bits()`
 * (raw, millimetre, matches the confidence image) or `acquireDepthImage()` (visualised
 * DEPTH16 range). This class uses the 16-bit one and divides by 1000 — the "metres" view the
 * specification asks for, without the lossy visualisation path.
 */
class DepthCapture(private val pool: DepthBufferPool = DepthBufferPool(capacity = 3)) {

    /**
     * Reusable stats container — the UI reads [meanConfidence], no allocation per frame.
     *
     * `usableRatio` used to sit here as "the share of the frame with usable depth". Nothing read
     * it: it was passed into the tracking monitor as `scanCoverage`, stored in the status under a
     * doc claiming it "drives the scan quality gauge", shown nowhere - and because it changes on
     * every frame, it also defeated the monitor's compare-first guard (finding 103).
     */
    data class Stats(
        val width: Int = 0,
        val height: Int = 0,
        val meanConfidence: Int = 0,
        val validPixels: Int = 0,
        val bestDepthMeters: Float = 0f,
    )

    private var lastStats = Stats()
    private var cachedDroppedFrames = 0

    val stats: Stats get() = lastStats

    /**
     * Acquires depth + confidence and packages them for the worker.
     *
     * @param minConfidence pixels below this are zeroed so the mesher never integrates them.
     * @return snapshot, or null when the device has no depth or ARCore has no frame yet.
     */
    fun capture(frame: Frame, minConfidence: Int): DepthFrameSnapshot? {
        val depthImage = try {
            frame.acquireDepthImage16Bits()
        } catch (error: NotYetAvailableException) {
            // Normal on the very first frames, and on devices where depth warms up slowly.
            cachedDroppedFrames++
            return null
        } catch (error: Throwable) {
            Log.w(TAG, "depth acquisition failed", error)
            return null
        }

        try {
            val width = depthImage.width
            val height = depthImage.height
            val plane = depthImage.planes[0]
            val buffer = plane.buffer
            val rowStrideElements = plane.rowStride / 2

            // Compact copy: the destination is always tightly packed (rowStride == width),
            // which simplifies every consumer downstream.
            val target = pool.acquire(width * height)
            val shorts = buffer.asShortBuffer()
            copyRows(shorts, width, height, rowStrideElements, target)

            // Confidence (160×120 on every ARCore device).
            var confidenceBytes: ByteArray? = null
            var confidenceWidth = 0
            var confidenceHeight = 0
            var confidenceRowStride = 0
            try {
                frame.acquireRawDepthConfidenceImage().use { image ->
                    val confPlane = image.planes[0]
                    confidenceWidth = image.width
                    confidenceHeight = image.height
                    confidenceRowStride = confPlane.rowStride
                    // Pooled like the depth plane: 19 kB per frame is not free in the render
                    // loop, and this class's whole reason to exist is to not allocate here
                    // (finding 172).
                    val conf = pool.acquireConfidence(confidenceRowStride * confidenceHeight)
                    copyBytes(confPlane.buffer, confidenceRowStride, confidenceHeight, conf)
                    confidenceBytes = conf
                }
            } catch (error: Throwable) {
                // Depth confidence is optional (only DEPTH-mode devices lack RAW_DEPTH).
                Log.d(TAG, "no confidence image: ${error.message}")
            }

            val intrinsics = depthIntrinsics(frame.camera, width, height)
            val pose = FloatArray(16)
            frame.camera.pose.toMatrix(pose, 0)

            lastStats = computeStats(target, width, height, confidenceBytes, confidenceWidth, confidenceHeight, confidenceRowStride, minConfidence)

            // Read once, so the release below cannot see a different plane than the one this
            // snapshot was built with.
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
                timestampNs = frame.timestamp,
                // The depth image is already in sensor orientation; the display rotation only
                // matters when mapping a *screen* tap to a depth pixel, which the caller does.
                displayRotationDegrees = 0,
                minDepthMm = 180,
                // The pool is bound here, not at the point of return: the last holder of the
                // snapshot knows nothing about pools, and the frame that replaced it is not
                // necessarily the last holder (finding 168).
                onLastRelease = {
                    pool.release(target)
                    confidence?.let(pool::releaseConfidence)
                },
            )
        } finally {
            depthImage.close()
        }
    }

    /**
     * Scales the colour-camera intrinsics to the depth image. ARCore only exposes
     * `Camera.getImageIntrinsics(width, height)`, so we ask for exactly the depth resolution
     * (depth is a cropped/rescaled view of the same optical centre).
     */
    private fun depthIntrinsics(camera: Camera, width: Int, height: Int): DepthIntrinsics {
        val image = camera.imageIntrinsics // colour-resolution intrinsics
        // `CameraIntrinsics` reports its resolution as a two-element `int[]` (`[width, height]`);
        // there is no `imageWidth`/`imageHeight`/`Size` accessor on it.
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
        // Sample on a coarse grid: full-frame statistics twice per second is plenty.
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

        /** Integration stride used at nominal temperature. */
        const val PIXEL_STEP_NOMINAL = 3

        /** Integration stride when the device is thermally throttled. */
        const val PIXEL_STEP_THROTTLED = 5
    }
}
