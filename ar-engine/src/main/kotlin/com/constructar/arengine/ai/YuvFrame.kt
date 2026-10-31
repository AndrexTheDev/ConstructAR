/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.ai

import android.graphics.Bitmap
import com.google.ar.core.ImageFormat
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer

/**
 * A CPU copy of ARCore's camera image, converted to a [Bitmap] off the render thread.
 *
 * `Frame.acquireCameraImage()` hands back a `YUV_420_888` `Image` that must be closed before
 * the next `update()`. Colour conversion is one of the more expensive per-pixel operations we
 * do (≈10 ms at 640×480), so the planes are copied on the render thread (a `memcpy`) and
 * converted on the inference worker — the render thread never blocks on it.
 *
 * Only used by the optional monocular-depth path, at ~2 Hz.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    private val y: ByteArray,
    private val yRowStride: Int,
    private val yPixelStride: Int,
    private val u: ByteArray,
    private val uRowStride: Int,
    private val uPixelStride: Int,
    private val v: ByteArray,
    private val vRowStride: Int,
    private val vPixelStride: Int,
    /** ARCore's `Image.getTimestamp()` — lets the fused depth be matched to a frame. */
    val timestampNs: Long,
) {
    /** Converts to ARGB. [pixels] must hold at least `width × height` ints (reused buffer). */
    fun toPixels(pixels: IntArray) {
        require(pixels.size >= width * height) { "pixel buffer too small" }
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = row / 2
            for (col in 0 until width) {
                val yValue = y[yRow + col * yPixelStride].toInt() and 0xFF
                val uIndex = uvRow * uRowStride + (col / 2) * uPixelStride
                val vIndex = uvRow * vRowStride + (col / 2) * vPixelStride
                val uValue = (u[uIndex].toInt() and 0xFF) - 128
                val vValue = (v[vIndex].toInt() and 0xFF) - 128

                // BT.601 studio-swing YUV → RGB (what every camera pipeline on Android emits).
                val c = (yValue - 16).coerceAtLeast(0)
                val r = ((298 * c + 409 * vValue + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * uValue - 208 * vValue + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * uValue + 128) shr 8).coerceIn(0, 255)
                pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    fun toBitmap(pixels: IntArray): Bitmap {
        toPixels(pixels)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /**
         * Copies the image planes out of [image]. Returns null for an unexpected format (some
         * devices deliver RGBA) rather than guessing and feeding garbage to the network.
         */
        fun copyOf(image: android.media.Image): YuvFrame? {
            if (image.format != ImageFormat.YUV_420_888) return null
            val planes = image.planes
            if (planes.size < 3) return null
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            return YuvFrame(
                width = image.width,
                height = image.height,
                y = readAll(yPlane.buffer),
                yRowStride = yPlane.rowStride,
                yPixelStride = yPlane.pixelStride,
                u = readAll(uPlane.buffer),
                uRowStride = uPlane.rowStride,
                uPixelStride = uPlane.pixelStride,
                v = readAll(vPlane.buffer),
                vRowStride = vPlane.rowStride,
                vPixelStride = vPlane.pixelStride,
                timestampNs = image.timestamp,
            )
        }

        /** Copies a plane's remaining bytes without disturbing the caller's buffer position. */
        private fun readAll(buffer: ByteBuffer): ByteArray {
            val duplicate = buffer.duplicate()
            val bytes = ByteArray(duplicate.remaining())
            duplicate.get(bytes)
            return bytes
        }

        /** Acquires + copies, or null when the frame is not ready yet (normal at start-up). */
        fun capture(frame: com.google.ar.core.Frame): YuvFrame? = try {
            frame.acquireCameraImage().use { image -> copyOf(image) }
        } catch (error: NotYetAvailableException) {
            null
        } catch (error: Throwable) {
            null
        }
    }
}
