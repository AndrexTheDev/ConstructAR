/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.depth

import com.constructar.core.geo.DepthIntrinsics
import com.constructar.core.math.Ray
import com.constructar.core.math.Vec3
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * One ARCore depth frame, captured **on the render thread** and then consumed asynchronously by
 * the TSDF pipeline.
 *
 * Why a copy: `Frame`/`Image` objects are only valid until the next `update()`, and the
 * integration worker must never touch them. A 640×480 16-bit depth image is 600 kB — copied
 * into a pooled `ShortArray` so the per-frame cost is a `memcpy`, never an allocation (see
 * `DepthBufferPool`).
 *
 * ## The buffer has several readers, so it is reference counted
 *
 * The pixels are immutable, but the array they live in is one of three in a pool, and a frame
 * is handed to **two** consumers at once: the pipeline's published snapshot (the hit-test
 * cascade, the AI-depth pass) and the integration worker. Until finding 168 the pipeline returned the array to the pool when
 * the *next frame replaced it*, which says nothing about the worker — so a hot device, where
 * integration takes longer than a frame, had the worker reading a `ShortArray` that was
 * already back in the pool and being rewritten by `DepthCapture.capture`. The result is depth
 * from one frame fused under another frame's pose.
 *
 * So the last holder returns the buffers, and only the last holder: [retain] before handing a
 * snapshot to another thread, [release] when done. The count starts at one for the capturing
 * thread.
 *
 * The confidence plane (160×120 on every ARCore device, 19 kB) is pooled the same way and
 * released with the depth array — it used to be a fresh `ByteArray` on every frame, in the class
 * whose whole comment is about not allocating in the render loop (finding 172).
 */
class DepthFrameSnapshot(
    /** Depth in **millimetres**, row-major, rowStride in elements (mm). */
    val depthMm: ShortArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    /** ARCore confidence, 0..255 — null when the device only offers DEPTH (not RAW_DEPTH). */
    val confidence: ByteArray? = null,
    val confidenceWidth: Int = 0,
    val confidenceHeight: Int = 0,
    val confidenceRowStride: Int = 0,
    /** Depth-image intrinsics, in depth-image pixels (NOT display pixels). */
    val intrinsics: DepthIntrinsics = DepthIntrinsics(0f, 0f, 0f, 0f),
    /** Camera→world for this frame, column-major. */
    val cameraToWorld: FloatArray = FloatArray(16),
    /** Frame timestamp, nanoseconds (`Frame.timestamp`). */
    val timestampNs: Long = 0L,
    /** Display rotation at capture; lets the UI map a screen tap back to depth pixels. */
    val displayRotationDegrees: Int = 0,
    /** Minimum depth the sensor reports, millimetres (ARCore: ~0.2 m on most devices). */
    val minDepthMm: Int = 200,
    /**
     * What happens when the last reference is released — bound by `DepthCapture` to its pools,
     * which is where **both** of this frame's arrays belong. Null for a snapshot whose arrays
     * were not pooled (tests, and any future caller that owns its own buffers).
     */
    private val onLastRelease: (() -> Unit)? = null,
) {
    val pixelCount: Int get() = width * height

    private val references = AtomicInteger(1)

    /**
     * How many holders the snapshot has: one to start with, one per [retain]. Exposed for
     * `DepthBufferLifetimeTest`; no production path needs the number.
     */
    val referenceCount: Int get() = references.get()

    /**
     * Takes one more reference. Every call needs a matching [release] — hand this to the
     * thread that is about to read the pixels and let it release when it is done.
     */
    fun retain(): DepthFrameSnapshot {
        references.incrementAndGet()
        return this
    }

    /**
     * Drops one reference and returns whether this was the last one.
     *
     * Only that call hands the array back to the pool, so a frame can never be recycled while
     * a worker is still integrating it (finding 168). A release beyond the last one is
     * ignored: it returns `false` and the array is not handed out twice.
     */
    fun release(): Boolean {
        if (references.decrementAndGet() != 0) return false
        onLastRelease?.invoke()
        return true
    }

    /** Depth at (x, y) in metres, or null when unobserved/invalid. */
    fun depthMetersAt(x: Int, y: Int): Float? {
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        val raw = depthMm[y * rowStride + x].toInt() and 0xFFFF
        if (raw <= 0) return null
        return raw / 1000f
    }

    /** Confidence (0..255) at depth pixel (x, y), or 255 when the device has no confidence image. */
    fun confidenceAt(x: Int, y: Int): Int {
        val bytes = confidence ?: return 255
        if (confidenceWidth == 0 || confidenceHeight == 0 || confidenceRowStride == 0) return 255
        // The confidence image is 160×120 on every ARCore device; map proportionally.
        val cx = (x.toFloat() / width * confidenceWidth).toInt().coerceIn(0, confidenceWidth - 1)
        val cy = (y.toFloat() / height * confidenceHeight).toInt().coerceIn(0, confidenceHeight - 1)
        return bytes[cy * confidenceRowStride + cx].toInt() and 0xFF
    }

    /** Convenience: is the pixel usable for measuring? */
    fun isUsable(x: Int, y: Int, minConfidence: Int): Boolean {
        val depth = depthMetersAt(x, y) ?: return false
        if (depth * 1000f < minDepthMm) return false
        return confidenceAt(x, y) >= minConfidence
    }

    companion object {
        /** Builds the camera ray through depth pixel (x, y) in world space. */
        fun worldRay(snapshot: DepthFrameSnapshot, x: Float, y: Float): Ray? =
            DepthImageMath.worldRay(snapshot.intrinsics, snapshot.cameraToWorld, x, y)
    }
}

/** Pixel-space helper maths. Everything is pure, so it is host-testable. */
object DepthImageMath {

    /**
     * Unprojects a depth pixel to camera space (metres). Camera convention matches
     * ARCore/Filament: +X right, +Y up, **−Z forward**.
     */
    fun unproject(intrinsics: DepthIntrinsics, x: Float, y: Float, depthMeters: Float): Vec3 =
        Vec3(
            (x - intrinsics.cx) / intrinsics.fx * depthMeters,
            -(y - intrinsics.cy) / intrinsics.fy * depthMeters,
            -depthMeters,
        )

    /** Applies a column-major 4×4 rigid transform to a point. */
    fun transformPoint(matrix: FloatArray, p: Vec3): Vec3 {
        if (matrix.size < 16) return p
        val x = matrix[0] * p.x + matrix[4] * p.y + matrix[8] * p.z + matrix[12]
        val y = matrix[1] * p.x + matrix[5] * p.y + matrix[9] * p.z + matrix[13]
        val z = matrix[2] * p.x + matrix[6] * p.y + matrix[10] * p.z + matrix[14]
        return Vec3(x, y, z)
    }

    /** Applies a column-major 4×4 rotation (no translation) to a direction. */
    fun transformDirection(matrix: FloatArray, d: Vec3): Vec3 {
        if (matrix.size < 16) return d
        return Vec3(
            matrix[0] * d.x + matrix[4] * d.y + matrix[8] * d.z,
            matrix[1] * d.x + matrix[5] * d.y + matrix[9] * d.z,
            matrix[2] * d.x + matrix[6] * d.y + matrix[10] * d.z,
        )
    }

    /** World-space ray through a depth pixel. */
    fun worldRay(intrinsics: DepthIntrinsics, cameraToWorld: FloatArray, x: Float, y: Float): Ray? {
        if (!intrinsics.isValid()) return null
        // Direction in camera space (unit): ((x−cx)/fx, −(y−cy)/fy, −1), normalised.
        val dx = (x - intrinsics.cx) / intrinsics.fx
        val dy = -(y - intrinsics.cy) / intrinsics.fy
        val dir = Vec3(dx, dy, -1f).normalized()
        val origin = transformPoint(cameraToWorld, Vec3.ZERO)
        val worldDir = transformDirection(cameraToWorld, dir).normalized()
        return Ray(origin, worldDir)
    }

    /**
     * Marches a camera ray through the depth image and returns the first surface crossing.
     *
     * This is tier 3 of the hit cascade. A naïve per-pixel walk misses thin geometry, so the
     * march steps ~1 pixel at the surface distance and accepts the hit when the ray's z
     * passes the sampled depth. [maxRangeMeters] bounds the search to keep the cost ~0.3 ms.
     */
    fun raycast(
        snapshot: DepthFrameSnapshot,
        intrinsics: DepthIntrinsics,
        cameraToWorld: FloatArray,
        screenX: Float,
        screenY: Float,
        minConfidence: Int = 96,
        maxRangeMeters: Float = 6f,
        stepPixels: Float = 1.5f,
        depthToleranceMeters: Float = 0.05f,
    ): DepthRaycastHit? {
        if (!intrinsics.isValid()) return null
        val worldRay = worldRay(intrinsics, cameraToWorld, screenX, screenY) ?: return null

        // March in *camera* space: the pixel walk is a simple projective division there.
        val dirCam = Vec3(
            (screenX - intrinsics.cx) / intrinsics.fx,
            -(screenY - intrinsics.cy) / intrinsics.fy,
            -1f,
        ).normalized()

        var travelled = 0.10f          // skip the phone's own housing / very near field
        var previousTravelled = travelled
        var step = 0
        while (travelled < maxRangeMeters && step < MAX_MARCH_STEPS) {
            step++
            val sample = dirCam * travelled
            val sampleDepth = -sample.z
            val px = intrinsics.fx * sample.x / sampleDepth + intrinsics.cx
            val py = -intrinsics.fy * sample.y / sampleDepth + intrinsics.cy
            val ix = px.toInt()
            val iy = py.toInt()
            val measured = snapshot.depthMetersAt(ix, iy)

            if (measured != null && measured > 0f &&
                snapshot.confidenceAt(ix, iy) >= minConfidence &&
                sampleDepth >= measured - depthToleranceMeters
            ) {
                // The surface lies between the previous sample and this one: linearly
                // interpolate on the (sampleDepth − measuredDepth) crossing.
                val previousSample = dirCam * previousTravelled
                val previousDepth = -previousSample.z
                val previousMeasured = snapshot.depthMetersAt(
                    (intrinsics.fx * previousSample.x / previousDepth + intrinsics.cx).toInt(),
                    (-intrinsics.fy * previousSample.y / previousDepth + intrinsics.cy).toInt(),
                )
                val t = if (previousMeasured != null && previousMeasured > 0f &&
                    previousDepth - previousMeasured < 0f
                ) {
                    val span = travelled - previousTravelled
                    val f = ((previousMeasured - previousDepth) /
                        ((sampleDepth - measured) - (previousDepth - previousMeasured)))
                        .coerceIn(0f, 1f)
                    previousTravelled + span * f
                } else {
                    travelled
                }
                val point = transformPoint(cameraToWorld, dirCam * t)
                val normal = normalAt(snapshot, intrinsics, cameraToWorld, ix, iy)
                return DepthRaycastHit(
                    point = point,
                    normal = normal,
                    confidence = snapshot.confidenceAt(ix, iy) / 255f,
                    distanceMeters = worldRay.origin.distanceTo(point),
                    pixelX = ix,
                    pixelY = iy,
                    measuredDepthMeters = measured,
                )
            }
            previousTravelled = travelled
            travelled += stepMeters(sampleDepth, intrinsics, stepPixels)
        }
        return null
    }

    private const val MAX_MARCH_STEPS = 512

    /** Metric step that advances roughly [pixels] pixels at [depth]. */
    private fun stepMeters(depth: Float, intrinsics: DepthIntrinsics, pixels: Float): Float {
        val safeDepth = depth.coerceAtLeast(0.2f)
        return (pixels * safeDepth / intrinsics.fx).coerceIn(0.005f, 0.25f)
    }

    /**
     * Surface normal from a 5×5 depth patch around (x, y), robust to depth noise: the patch is
     * unprojected, least-squares fitted, and oriented towards the camera. Returns +Z-ish
     * fallback when the patch is degenerate (a blank wall at grazing angle).
     */
    fun normalAt(
        snapshot: DepthFrameSnapshot,
        intrinsics: DepthIntrinsics,
        cameraToWorld: FloatArray,
        x: Int,
        y: Int,
        half: Int = 2,
    ): Vec3 {
        val points = ArrayList<Vec3>((2 * half + 1) * (2 * half + 1))
        for (dy in -half..half) {
            for (dx in -half..half) {
                val d = snapshot.depthMetersAt(x + dx, y + dy) ?: continue
                points += transformPoint(cameraToWorld, unproject(intrinsics, (x + dx).toFloat(), (y + dy).toFloat(), d))
            }
        }
        val fit = com.constructar.core.math.Plane3.fitLeastSquares(points)
            ?: return transformDirection(cameraToWorld, Vec3(0f, 0f, 1f))
        val normal = fit.plane.normal
        // Face the camera: a normal pointing away from the viewer would place the conduit
        // inside the wall.
        val camera = transformPoint(cameraToWorld, Vec3.ZERO)
        return if ((normal dot (camera - fit.centroid)) < 0f) -normal else normal
    }
}

/** A successful tier-3 depth raycast. */
data class DepthRaycastHit(
    val point: Vec3,
    val normal: Vec3,
    val confidence: Float,
    val distanceMeters: Float,
    val pixelX: Int,
    val pixelY: Int,
    val measuredDepthMeters: Float,
)

/**
 * Fixed-size pools of the two planes of one ARCore depth frame: the depth plane (640×480×2 B,
 * 600 kB) and the confidence plane (160×120 B, 19 kB).
 *
 * Both are acquired when a frame is captured on the render thread, handed to the integration
 * worker and published to the hit cascade, and go back when the last holder releases the
 * snapshot (see [DepthFrameSnapshot]). Pooling them avoids a guaranteed GC pause every few
 * seconds in the one thread the app cannot afford to stall.
 */
class DepthBufferPool(private val capacity: Int, private val initialPixels: Int = 640 * 480 * 2) {
    private val depthPlanes = ArrayDeque<ShortArray>(capacity)
    private val confidencePlanes = ArrayDeque<ByteArray>(capacity)

    init {
        repeat(capacity) { depthPlanes.addLast(ShortArray(initialPixels)) }
        // The confidence planes start empty rather than pre-allocated: their size comes from the
        // image ARCore hands over (160×120 on every device, but not a constant this module owns),
        // so the first few frames create them.
    }

    @Synchronized
    fun acquire(size: Int): ShortArray {
        val buffer = depthPlanes.removeFirstOrNull()
        if (buffer == null || buffer.size < size) return ShortArray(size)
        return buffer
    }

    @Synchronized
    fun release(buffer: ShortArray) {
        if (depthPlanes.size < capacity) depthPlanes.addLast(buffer)
    }

    /** A confidence plane of at least [size] bytes, reused once one has been returned. */
    @Synchronized
    fun acquireConfidence(size: Int): ByteArray {
        val buffer = confidencePlanes.removeFirstOrNull()
        if (buffer == null || buffer.size < size) return ByteArray(size)
        return buffer
    }

    @Synchronized
    fun releaseConfidence(buffer: ByteArray) {
        if (confidencePlanes.size < capacity) confidencePlanes.addLast(buffer)
    }
}

/** Small helper used by the depth tests and by `MetricDepthCalibrator`. */
internal fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
