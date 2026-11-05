/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import java.nio.ByteBuffer

/**
 * The shape of one plane of a camera frame, as the camera reports it.
 *
 * A camera does not hand out a compact picture. It hands out rows that are [rowStride] bytes long
 * — usually wider than [width], because the hardware writes in fixed bursts — and, on some devices,
 * samples that are [pixelStride] bytes apart instead of one. Both are facts about the sensor, and
 * neither is visible in a `ByteArray` once it is copied: a buffer that ignored them still has the
 * right length, still looks like a picture, and has every row shifted left by the padding that was
 * dropped. A tag read out of that buffer shears exactly like the floorplan did in finding 229, and
 * the decoder answers "no tag in view" — which is a lie a caller has no way to check.
 */
data class YuvPlane(val width: Int, val height: Int, val rowStride: Int, val pixelStride: Int) {

    /** True when every number describes a plane that could come out of a camera. */
    val isUsable: Boolean get() = LuminancePlane.isUsable(this)

    /** Bytes this plane occupies once packed, or `null` when it is not a plane. */
    val packedBytes: Int? get() = LuminancePlane.packedSize(this)

    /** Bytes the camera's buffer must hold to contain every sample of this plane. */
    val requiredBytes: Int? get() = LuminancePlane.requiredBytes(this)
}

/**
 * Turns one camera plane into the tightly packed grey buffer the AprilTag decoder reads.
 *
 * This is `:core`'s because it is data handling, not Android: `java.nio.ByteBuffer` is standard
 * Java, so the whole conversion is testable in a JVM test with no phone, no ARCore and no camera.
 * That matters, because the conversion is the only part of the marker path that can be *proved*
 * here — everything beyond it needs a lens.
 *
 * **Absolute reads.** The source is read with `ByteBuffer.get(index)`, never through its position:
 * a plane buffer belongs to the camera session, and a helper that advanced its position or flipped
 * it would corrupt a buffer somebody else still owns.
 *
 * **Packed, not strided.** The output is `width * height` bytes with no padding, so the caller
 * passes `width` as the row stride from then on. Passing the camera's stride to a packed buffer —
 * or the reverse — is the mistake this function exists to make impossible.
 */
object LuminancePlane {

    /** Below this a frame cannot hold a readable tag; above it, nothing a phone produces. */
    const val MIN_SIDE = 16

    /** Upper bound on a side, so a corrupt `Image` cannot ask for a gigabyte of scratch. */
    const val MAX_SIDE = 8192

    /** Upper bound on a row stride, as a multiple of the width: padding is alignment, not slop. */
    const val MAX_STRIDE_FACTOR = 4

    /** Bytes per sample a camera may report; beyond this it is not a grey plane. */
    const val MAX_PIXEL_STRIDE = 4

    fun isUsable(plane: YuvPlane): Boolean {
        if (plane.width !in MIN_SIDE..MAX_SIDE || plane.height !in MIN_SIDE..MAX_SIDE) return false
        if (plane.pixelStride !in 1..MAX_PIXEL_STRIDE) return false
        if (plane.rowStride < plane.width || plane.rowStride > plane.width * MAX_STRIDE_FACTOR) return false
        return requiredBytes(plane) != null
    }

    /** `width * height`, or `null` when the plane is not one a camera could produce. */
    fun packedSize(plane: YuvPlane): Int? = if (isUsable(plane)) plane.width * plane.height else null

    /**
     * Bytes the buffer must hold: the last sample sits at `(h-1) * rowStride + (w-1) * pixelStride`.
     *
     * Computed in `Long` and refused when it does not fit an `Int`: a plane whose stride and height
     * multiply past two gigabytes is not a frame, and letting that wrap would pass a negative
     * length to a bounds check that treats it as "small enough".
     */
    fun requiredBytes(plane: YuvPlane): Int? {
        if (plane.width <= 0 || plane.height <= 0) return null
        if (plane.rowStride < plane.width || plane.pixelStride < 1) return null
        val bytes = (plane.height - 1).toLong() * plane.rowStride +
            (plane.width - 1).toLong() * plane.pixelStride + 1
        return if (bytes in 1..Int.MAX_VALUE.toLong()) bytes.toInt() else null
    }

    /**
     * Packs [source] into [out]; false when either is not what this call needs.
     *
     * [out] may be longer than the packed size — a caller reuses one scratch buffer across frames —
     * and only its first `packedSize` bytes are written. Nothing is written when the call returns
     * false, so a caller that ignores the answer cannot decode half a frame from the previous one.
     */
    fun pack(source: ByteBuffer, plane: YuvPlane, out: ByteArray): Boolean {
        val size = packedSize(plane) ?: return false
        if (out.size < size) return false
        val required = requiredBytes(plane) ?: return false
        if (source.capacity() < required) return false
        var write = 0
        for (row in 0 until plane.height) {
            var read = row * plane.rowStride
            for (column in 0 until plane.width) {
                out[write++] = source.get(read)
                read += plane.pixelStride
            }
        }
        return true
    }
}
