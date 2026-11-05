/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import android.graphics.ImageFormat
import android.media.Image
import com.constructar.core.calib.YuvPlane
import java.nio.ByteBuffer

/**
 * The luminance plane of one camera image, described in the terms `:core` packing understands.
 *
 * ARCore hands out a YUV_420_888 [Image] whose bytes are already direct and already in memory the
 * decoder can read - no copy is needed to get at them, only a description. [plane] is that
 * description and [source] is the buffer itself; keeping the two together is what stops the common
 * bug of passing a `YuvPlane` built from the *image* dimensions to a buffer indexed with the
 * *plane's* row stride.
 */
class CameraPlane(val plane: YuvPlane, val source: ByteBuffer)

/**
 * Reads the one plane a marker decoder needs out of an ARCore frame.
 *
 * Two refusals, both silent by design: a frame in a format that has no standalone luminance plane
 * (ARCore always gives YUV_420_888 today, and a future format must not be guessed at), and a plane
 * whose strides do not describe a plane at all. The caller keeps its previous state in both cases -
 * a frame the app cannot read is not evidence that the phone moved.
 *
 * Not covered by a JVM test: `android.media.Image` cannot be constructed off a device. The packing
 * it feeds *is* tested (`LuminancePlaneTest`), so this file is deliberately nothing but the adapter.
 */
object CameraPlanes {

    /** The luminance plane of [image], or `null` when this frame is not one that can be read. */
    fun luminance(image: Image): CameraPlane? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val planes = image.planes
        if (planes.isEmpty()) return null
        val y = planes[0]
        val plane = YuvPlane(
            width = image.width,
            height = image.height,
            rowStride = y.rowStride,
            pixelStride = y.pixelStride,
        )
        if (!plane.isUsable) return null
        val bytes = plane.requiredBytes ?: return null
        if (y.buffer.capacity() < bytes) return null
        return CameraPlane(plane, y.buffer)
    }
}
