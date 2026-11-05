/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.core.calib.LuminancePlane
import com.constructar.core.calib.YuvPlane
import com.constructar.nativebridge.AprilTagReport
import com.constructar.nativebridge.NativeSlamEngine
import java.nio.ByteBuffer

/**
 * The pinhole camera a frame came from, in pixels.
 *
 * ARCore reports these per session and they do not change while it runs. They are here rather than
 * read from the session inside the reader so that the reader stays a pure function of its inputs -
 * a decoder's answer is only a distance because *these* numbers say how big a pixel is.
 */
data class CameraIntrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float) {

    /** Positive focal lengths and a principal point that is not negative: a camera, not four zeros. */
    val isPlausible: Boolean
        get() = fx > 0f && fy > 0f && cx >= 0f && cy >= 0f &&
            fx.isFinite() && fy.isFinite() && cx.isFinite() && cy.isFinite()
}

/**
 * The seam between this module and the vendored decoder.
 *
 * An interface, not a direct call, for one reason: the composition below - a camera plane becomes
 * a packed grey buffer, the buffer becomes a metric sighting - is the part that can be proved in a
 * JVM test, and it can only be proved if the decoder can be replaced by something that records
 * what it was given. `EngineTagDecoder` is the implementation that reaches the native layer.
 */
interface TagDecoder {
    /**
     * Decodes the tags in one packed grey frame.
     *
     * [gray] holds exactly `plane.width * plane.height` bytes, row by row, with no padding - so the
     * row stride the native layer gets is the width, not the camera's. Returns `null` when it could
     * not look at all, and a report with no tags when it looked and saw none.
     */
    fun decode(
        gray: ByteArray,
        plane: YuvPlane,
        camera: CameraIntrinsics,
        tagSizeMetres: Float,
    ): AprilTagReport?
}

/** The decoder this build ships: the vendored tag36h11 core, reached through the JNI bridge. */
class EngineTagDecoder(private val engine: NativeSlamEngine) : TagDecoder {

    override fun decode(
        gray: ByteArray,
        plane: YuvPlane,
        camera: CameraIntrinsics,
        tagSizeMetres: Float,
    ): AprilTagReport? = engine.detectAprilTagScale(
        gray = gray,
        width = plane.width,
        height = plane.height,
        // Packed, so the stride is the width. Passing the camera's stride here would make the
        // native layer skip bytes that are no longer padding (finding 233).
        rowStride = plane.width,
        fx = camera.fx,
        fy = camera.fy,
        cx = camera.cx,
        cy = camera.cy,
        tagSizeMetres = tagSizeMetres,
    )
}

/**
 * Turns one camera plane into a metric tag sighting, or `null` when the question was broken.
 *
 * Three answers, and they are different on purpose:
 *
 * * `null` - this was not a frame this reader can use: an impossible plane, a camera without a
 *   focal length, a printed size outside what a marker can be, a buffer too short for the rows it
 *   claims. Nothing was looked at.
 * * an empty [AprilTagReport] - it looked, and the frame held no tag. The operator can move the
 *   phone; the app can say "no marker in view" without lying.
 * * a report with sightings - metres, from the printed size and the intrinsics.
 *
 * The scratch buffer is kept between calls: this runs per frame while a session is live, and
 * allocating a 300 kB array sixty times a second is how a GC pause becomes a dropped frame.
 */
class MarkerFrameReader(
    private val decoder: TagDecoder,
    private val tagSizeMetres: Float = DEFAULT_TAG_SIZE_METRES,
) {

    private var scratch = ByteArray(0)

    /** True when the printed size this reader was given is one a marker can have. */
    val isUsable: Boolean get() = tagSizeMetres in MIN_TAG_SIZE_METRES..MAX_TAG_SIZE_METRES

    /** The bytes the last frame needed; a caller sizing a buffer can ask without packing. */
    fun scratchSizeFor(plane: YuvPlane): Int = LuminancePlane.packedSize(plane) ?: 0

    fun read(source: ByteBuffer, plane: YuvPlane, camera: CameraIntrinsics): AprilTagReport? {
        if (!isUsable) return null
        if (!camera.isPlausible) return null
        val packed = LuminancePlane.packedSize(plane) ?: return null
        if (scratch.size < packed) scratch = ByteArray(packed)
        if (!LuminancePlane.pack(source, plane, scratch)) return null
        return decoder.decode(scratch, plane, camera, tagSizeMetres)
    }

    companion object {
        /** A 20 cm marker: the size a printed wall tag is cut to (docs/CALIBRATION.md, tier 2). */
        const val DEFAULT_TAG_SIZE_METRES = 0.20f

        const val MIN_TAG_SIZE_METRES = 0.01f

        const val MAX_TAG_SIZE_METRES = 5f
    }
}
