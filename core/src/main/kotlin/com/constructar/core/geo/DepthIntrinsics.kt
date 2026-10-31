/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.geo

/**
 * Pinhole intrinsics of an ARCore **depth** image, in depth-image pixels.
 *
 * The depth image and the colour image are not the same resolution: ARCore's depth map is
 * typically 160×120 (or 320×240 on newer sensors) while the colour stream is 640×480 or
 * larger, so the intrinsics have to be scaled from `Camera.imageIntrinsics` before any pixel
 * of the depth buffer can be unprojected. This type is the single place that scaled set lives,
 * which is why it sits in `:core` (both `:ar-engine`, which captures depth, and `:native-slam`,
 * which integrates it, need it — and neither may depend on the other).
 */
data class DepthIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
) {
    /** False for a default-constructed (0×0) value: nothing can be unprojected from it. */
    fun isValid(): Boolean = fx > 0f && fy > 0f

    /**
     * Scales colour-image intrinsics by the ratio of the two resolutions.
     *
     * ARCore exposes intrinsics for the *colour* image only; the depth image is a scaled copy
     * of the same optical centre, so a per-axis scale factor is exact.
     */
    companion object {
        fun scaled(
            colorFx: Float,
            colorFy: Float,
            colorCx: Float,
            colorCy: Float,
            colorWidth: Int,
            colorHeight: Int,
            depthWidth: Int,
            depthHeight: Int,
        ): DepthIntrinsics {
            if (colorWidth <= 0 || colorHeight <= 0 || depthWidth <= 0 || depthHeight <= 0) {
                return DepthIntrinsics(0f, 0f, 0f, 0f)
            }
            val sx = depthWidth.toFloat() / colorWidth.toFloat()
            val sy = depthHeight.toFloat() / colorHeight.toFloat()
            return DepthIntrinsics(colorFx * sx, colorFy * sy, colorCx * sx, colorCy * sy)
        }
    }
}
