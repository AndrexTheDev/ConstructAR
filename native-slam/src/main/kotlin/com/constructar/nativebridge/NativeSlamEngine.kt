/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.constructar.core.geo.DepthIntrinsics
import java.nio.ByteBuffer

/**
 * Kotlin façade over `libconstructar_slam.so` (see `jni/slam_jni.cpp`).
 *
 * Ownership & threading contract:
 *  * One instance == one TSDF volume + one pose graph. Create it when the scanning screen
 *    opens, [close] it when it closes. It is **not** thread-safe from Kotlin's point of
 *    view: integrate/extract/pose-graph calls must be serialised by [TsdfScanPipeline]
 *    (which owns a single-threaded dispatcher).
 *  * Depth buffers passed to [integrateDepth] must be **direct** ByteBuffers whose bytes
 *    stay valid for the duration of the call. ARCore's `Image.Plane.buffer` qualifies
 *    inside the `Image` lifetime, which is why the copy happens before `Image.close()`.
 *
 * Every method degrades gracefully: if the library fails to load, [isAvailable] is false
 * and the AR UI still runs (route measuring does not depend on TSDF meshing).
 *
 * [close] frees the native context and **zeroes the handle**, which is what makes the guards in
 * every other method mean something. Until finding 182 the handle was a `val`: `close()` kept it,
 * so a second `close()` handed the same `EngineContext*` to `delete` twice - a double free - and
 * every call after the first close passed a dangling pointer straight to the JNI layer, where
 * `contextFrom()` cannot tell freed memory from a live context. The class read as if it degraded
 * after close; it did the opposite.
 */
class NativeSlamEngine internal constructor(
    initialHandle: Long,
    /**
     * Frees the native context.
     *
     * Injectable because the JVM test suite has no `libconstructar_slam.so`: the double-close this
     * class has to survive is exactly the thing that cannot be tested through the real library
     * (finding 182). Production always uses the JNI entry point.
     */
    private val destroy: (Long) -> Unit,
) : AutoCloseable {

    /**
     * The native context, or 0 once [close] has freed it.
     *
     * Volatile: `close()` arrives from the thread that closes the screen while the scan worker is
     * the one calling into the volume, so the zeroing has to be visible to the worker immediately.
     * Every entry point reads it **once** into a local and uses that value for the whole call - a
     * second read could pass the guard and then hand the native layer a handle that was just
     * zeroed.
     */
    @Volatile
    private var handle: Long = initialHandle

    /**
     * Integrates one raw-depth frame from a pooled `ShortArray` (the normal path).
     *
     * @param depthMm     16-bit millimetres, tightly packed (rowStride may still be wider).
     * @param confidence  optional ARCore confidence image, 0..255.
     */
    fun integrateDepth(
        depthMm: ShortArray,
        width: Int,
        height: Int,
        rowStrideElements: Int,
        confidence: ByteArray?,
        confidenceWidth: Int,
        confidenceHeight: Int,
        confidenceRowStride: Int,
        intrinsics: DepthIntrinsics,
        cameraToWorld: FloatArray,
        timestampNs: Long,
        pixelStep: Int,
    ): IntegrationResult? {
        val active = handle
        if (active == 0L) return null
        require(cameraToWorld.size == 16) { "cameraToWorld must be a column-major 4×4 matrix" }
        if (width <= 0 || height <= 0) return null
        val raw = nativeIntegrateDepth(
            active,
            depthMm,
            width,
            height,
            rowStrideElements,
            confidence,
            confidenceWidth,
            confidenceHeight,
            confidenceRowStride,
            intrinsics.fx,
            intrinsics.fy,
            intrinsics.cx,
            intrinsics.cy,
            cameraToWorld,
            timestampNs,
            pixelStep,
        ) ?: return null
        return IntegrationResult.from(raw)
    }

    /**
     * Zero-copy variant when the depth plane is already a **direct** `ByteBuffer`
     * (16-bit little-endian millimetres). Cheaper than [integrateDepth] by one `memcpy`,
     * but only usable while the ARCore `Image` is alive.
     */
    fun integrateDepthBuffer(
        depthMm: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideElements: Int,
        confidence: ByteBuffer?,
        confidenceWidth: Int,
        confidenceHeight: Int,
        confidenceRowStride: Int,
        intrinsics: DepthIntrinsics,
        cameraToWorld: FloatArray,
        timestampNs: Long,
        pixelStep: Int,
    ): IntegrationResult? {
        val active = handle
        if (active == 0L) return null
        if (!depthMm.isDirect) {
            Log.w(TAG, "integrateDepthBuffer needs a direct ByteBuffer")
            return null
        }
        val raw = nativeIntegrateDepthBuffer(
            active,
            depthMm,
            width,
            height,
            rowStrideElements,
            confidence,
            confidenceWidth,
            confidenceHeight,
            confidenceRowStride,
            intrinsics.fx,
            intrinsics.fy,
            intrinsics.cx,
            intrinsics.cy,
            cameraToWorld,
            timestampNs,
            pixelStep,
        ) ?: return null
        return IntegrationResult.from(raw)
    }

    /**
     * Zero-copy variant for API 26+: locks ARCore's `AHardwareBuffer` instead of copying the
     * depth plane at all. Returns null when the buffer cannot be locked (some devices expose
     * it as a PRIVATE format) — callers then fall back to [integrateDepth].
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun integrateDepthHardwareBuffer(
        hardwareBuffer: android.hardware.HardwareBuffer,
        width: Int,
        height: Int,
        confidence: ByteBuffer?,
        confidenceWidth: Int,
        confidenceHeight: Int,
        confidenceRowStride: Int,
        intrinsics: DepthIntrinsics,
        cameraToWorld: FloatArray,
        timestampNs: Long,
        pixelStep: Int,
    ): IntegrationResult? {
        val active = handle
        if (active == 0L) return null
        val raw = nativeIntegrateDepthHardwareBuffer(
            active,
            hardwareBuffer,
            width,
            height,
            confidence,
            confidenceWidth,
            confidenceHeight,
            confidenceRowStride,
            intrinsics.fx,
            intrinsics.fy,
            intrinsics.cx,
            intrinsics.cy,
            cameraToWorld,
            timestampNs,
            pixelStep,
        ) ?: return null
        return IntegrationResult.from(raw)
    }

    /** Runs Surface Nets. Returns null when nothing has been scanned yet. */
    fun extractMesh(isoLevel: Float = 0f): ScanMesh? {
        val active = handle
        if (active == 0L) return null
        val payload = nativeExtractMesh(active, isoLevel) ?: return null
        val vertices = payload[0] as? FloatArray ?: return null
        val indices = payload[1] as? IntArray ?: return null
        return ScanMesh.fromPayload(vertices, indices)
    }

    /** Axis-aligned bounds of the last extracted mesh: `[minX, minY, minZ, maxX, maxY, maxZ]`. */
    fun lastBounds(): FloatArray? {
        val active = handle
        if (active == 0L) return null
        return nativeLastBounds(active)
    }

    /**
     * The room's floorplan: a slice at [heightMetres], the horizontal surfaces dropped, the rest
     * vectorised into wall runs and an outline.
     *
     * **Call this on the scan worker, not from a screen.** It walks the whole volume (that is what
     * a slice is) and the native side holds the volume lock for the duration, so a call from the
     * UI thread would stall integration and drop frames. The pipeline's single-threaded dispatcher
     * is the only correct caller, which is also why nothing in `:app` calls it yet.
     *
     * Defaults are `FloorplanOptions`' own (`cellMetres` 5 cm, `rdpEpsilonMetres` 8 cm,
     * `minSegmentMetres` 25 cm); the native side refuses implausible values by returning null
     * rather than drawing a room out of a division by zero.
     *
     * Returns null when the library is unavailable, the native side refused the parameters, or the
     * payload is truncated - see [FloorplanPayload.parse] for what "truncated" means and why a
     * short payload is not read into zeros.
     */
    fun floorplan(
        heightMetres: Float = 1f,
        bandMetres: Float = 0.1f,
        cellMetres: Float = 0.05f,
        rdpEpsilonMetres: Float = 0.08f,
        minSegmentMetres: Float = 0.25f,
        /**
         * How far a wall run may sit off the room's own axes and still be squared onto them.
         *
         * 0 leaves the room exactly as measured, which is the setting for a report that has to
         * show the scan and not the assumption; 10 degrees is drift plus the slice's own noise.
         * The native side refuses anything outside [0, 45].
         */
        manhattanToleranceDegrees: Float = 10f,
    ): FloorplanPayload? {
        val active = handle
        if (active == 0L) return null
        val payload = nativeFloorplan(
            active,
            heightMetres,
            bandMetres,
            cellMetres,
            rdpEpsilonMetres,
            minSegmentMetres,
            manhattanToleranceDegrees,
        )
        return FloorplanPayload.parse(payload)
    }

    /**
     * What the last [floorplan] call's Manhattan step did, or `null` when the library cannot say.
     *
     * Read straight after [floorplan] and on the same worker: the context keeps only the newest
     * fit, so a caller that asks later is asking about a different room.
     */
    /**
     * Reads tag36h11 markers out of one 8-bit greyscale frame, in metres.
     *
     * The decoder is vendored (AprilTag 3.1.0, BSD-2-Clause) and the arithmetic is proved in the
     * host suite against rendered tags, so a result here is a measurement and not a promise. What
     * `:app` does not have yet is a path that captures a luminance frame and calls this: nothing in
     * the app feeds the marker tier (docs/CALIBRATION.md, tier 2).
     *
     * `null` when the arguments are not a frame or not a camera, an empty report when the frame
     * held no tag. `rowStride` is in bytes; `gray` must hold at least `height * rowStride` bytes.
     */
    fun detectAprilTagScale(
        gray: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        tagSizeMetres: Float,
    ): AprilTagReport? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (gray.size < height * rowStride) return null
        if (fx <= 0f || fy <= 0f || tagSizeMetres <= 0f) return null
        return AprilTagReport.from(
            nativeDetectAprilTagScale(gray, width, height, rowStride, fx, fy, cx, cy, tagSizeMetres),
        )
    }

    /**
     * The frame the last [floorplan] call was squared onto, fitted from the room's own walls.
     *
     * `null` when the library is unavailable or cannot say. The fitting happens inside
     * [floorplan] - that is where the volume is sliced and locked - so this is a read, not a second
     * fit: two fits of one room would be two answers to one question, and the plan on the card is
     * the one that matters.
     */
    fun alignManhattanFrame(): ManhattanFrameReport? {
        val active = handle
        if (active == 0L) return null
        return ManhattanFrameReport.from(nativeAlignManhattanFrame(active))
    }

    /**
     * The two knobs the marker path shares with the native layout path, straight out of
     * `TagScaleOptions`: a baseline floor and a relative-deviation filter.
     *
     * `null` when the library is unavailable. There is deliberately no Kotlin fallback: a second
     * pair of numbers here would be a second definition of what counts as evidence, and the two
     * would drift the first time one of them is tuned.
     */
    fun tagScaleDefaults(): Pair<Float, Float>? {
        val values = nativeTagScaleDefaults() ?: return null
        if (values.size != 2) return null
        val baseline = values[0]
        val deviation = values[1]
        if (!(baseline > 0f) || !(deviation > 0f)) return null
        if (!baseline.isFinite() || !deviation.isFinite()) return null
        return baseline to deviation
    }

    fun lastManhattan(): ManhattanReport? {
        val active = handle
        if (active == 0L) return null
        return ManhattanReport.from(nativeLastManhattan(active))
    }

    fun reset() {
        val active = handle
        if (active != 0L) nativeReset(active)
    }

    // ── Pose graph (loop closure) ────────────────────────────────────────────────────────
    // Fed since finding 243: `KeyframeFeed` accepts a keyframe, `KeyframeQueue` carries it across
    // to the pipeline worker, and `NativePoseGraphSink` makes the node and its odometry edge here.
    // Since finding 245 the optimiser runs too - when, and only when, the operator confirms a
    // closure the proximity detector proposed - and its corrected poses are reported, never
    // written back into the measured runs. See docs/SETUP.md 5.2.

    fun resetPoseGraph() {
        val active = handle
        if (active != 0L) nativePoseGraphReset(active)
    }

    /** Adds a keyframe pose (column-major, camera→world). Returns the node index. */
    fun addPoseNode(pose: FloatArray, fixed: Boolean = false): Int {
        val active = handle
        if (active == 0L) return -1
        return nativePoseGraphAddNode(active, pose, fixed)
    }

    fun addPoseEdge(
        from: Int,
        to: Int,
        measurement: FloatArray,
        rotationWeight: Double = 1.0,
        translationWeight: Double = 1.0,
        loopClosure: Boolean = false,
    ) {
        val active = handle
        if (active == 0L) return
        nativePoseGraphAddEdge(active, from, to, measurement, rotationWeight, translationWeight, loopClosure)
    }

    /**
     * Runs the robust optimiser after an operator-confirmed closure and returns diagnostic poses.
     * Native GNC/TLS reweights loop edges only; a failed or zero-weight new loop restores all poses,
     * removes that edge and reports `converged=false`. Corrected poses never re-anchor measured runs.
     */
    fun optimizePoseGraph(iterations: Int = 20): PoseGraphResult? {
        val active = handle
        if (active == 0L) return null
        val raw = nativePoseGraphOptimize(active, iterations) ?: return null
        return PoseGraphResult.from(raw)
    }

    /** Proximity loop-closure candidates `[from, to, distance]` — proposals only. */
    fun loopClosureCandidates(): List<LoopCandidate> {
        val active = handle
        if (active == 0L) return emptyList()
        val raw = nativeLoopCandidates(active) ?: return emptyList()
        return raw.toList().chunked(3).mapNotNull { triple ->
            if (triple.size < 3) null else LoopCandidate(triple[0].toInt(), triple[1].toInt(), triple[2])
        }
    }

    /** True when this build was compiled with the RTAB-Map back-end. */
    fun isRtabMapAvailable(): Boolean {
        val active = handle
        return active != 0L && nativeRtabMapAvailable(active)
    }

    /**
     * Frees the native context. Idempotent: the handle is claimed and zeroed under one lock, so
     * two closes free one context, and every call after the first returns null without touching
     * the native layer (finding 182).
     */
    override fun close() {
        val claimed = synchronized(this) {
            val current = handle
            handle = 0L
            current
        }
        if (claimed != 0L) destroy(claimed)
    }

    companion object {
        private const val TAG = "NativeSlamEngine"

        /** True when `libconstructar_slam.so` loaded successfully. */
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("constructar_slam")
                true
            } catch (error: UnsatisfiedLinkError) {
                Log.e(TAG, "constructar_slam unavailable — TSDF scanning disabled", error)
                false
            }
        }

        /**
         * Creates an engine, or null when the native library is missing (e.g. an x86 CI
         * emulator without the ABI) so the AR screen can still run in measuring-only mode.
         */
        fun create(config: TsdfConfig = TsdfConfig()): NativeSlamEngine? {
            if (!isAvailable) return null
            val handle = try {
                nativeCreate(
                    config.voxelSizeMeters,
                    config.truncationVoxels,
                    config.maxBlocks,
                    config.workerThreads,
                    config.minConfidence.toFloat(),
                )
            } catch (error: Throwable) {
                Log.e(TAG, "nativeCreate failed", error)
                return null
            }
            return if (handle == 0L) null else NativeSlamEngine(handle) { nativeDestroy(it) }
        }

        @JvmStatic
        private external fun nativeCreate(
            voxelSize: Float,
            truncationVoxels: Float,
            maxBlocks: Int,
            workerThreads: Int,
            minConfidence: Float,
        ): Long

        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeReset(handle: Long)

        @JvmStatic
        private external fun nativeIntegrateDepth(
            handle: Long,
            depthMm: ShortArray,
            width: Int,
            height: Int,
            rowStrideElements: Int,
            confidence: ByteArray?,
            confidenceWidth: Int,
            confidenceHeight: Int,
            confidenceRowStride: Int,
            fx: Float,
            fy: Float,
            cx: Float,
            cy: Float,
            cameraToWorld: FloatArray,
            timestampNs: Long,
            pixelStep: Int,
        ): IntArray?

        @JvmStatic
        private external fun nativeIntegrateDepthBuffer(
            handle: Long,
            depthMm: ByteBuffer,
            width: Int,
            height: Int,
            rowStrideElements: Int,
            confidence: ByteBuffer?,
            confidenceWidth: Int,
            confidenceHeight: Int,
            confidenceRowStride: Int,
            fx: Float,
            fy: Float,
            cx: Float,
            cy: Float,
            cameraToWorld: FloatArray,
            timestampNs: Long,
            pixelStep: Int,
        ): IntArray?

        @JvmStatic
        private external fun nativeIntegrateDepthHardwareBuffer(
            handle: Long,
            hardwareBuffer: android.hardware.HardwareBuffer,
            width: Int,
            height: Int,
            confidence: ByteBuffer?,
            confidenceWidth: Int,
            confidenceHeight: Int,
            confidenceRowStride: Int,
            fx: Float,
            fy: Float,
            cx: Float,
            cy: Float,
            cameraToWorld: FloatArray,
            timestampNs: Long,
            pixelStep: Int,
        ): IntArray?

        @JvmStatic private external fun nativeExtractMesh(handle: Long, isoLevel: Float): Array<Any>?
        @JvmStatic private external fun nativeLastBounds(handle: Long): FloatArray?
        @JvmStatic private external fun nativeFloorplan(
            handle: Long,
            heightMetres: Float,
            bandMetres: Float,
            cellMetres: Float,
            rdpEpsilonMetres: Float,
            minSegmentMetres: Float,
            manhattanToleranceDegrees: Float,
        ): FloatArray?

        @JvmStatic private external fun nativeLastManhattan(handle: Long): FloatArray?

        @JvmStatic private external fun nativeAlignManhattanFrame(handle: Long): FloatArray?

        @JvmStatic private external fun nativeTagScaleDefaults(): FloatArray?

        @JvmStatic private external fun nativeDetectAprilTagScale(
            gray: ByteArray,
            width: Int,
            height: Int,
            rowStride: Int,
            fx: Float,
            fy: Float,
            cx: Float,
            cy: Float,
            tagSizeMetres: Float,
        ): FloatArray?
        @JvmStatic private external fun nativePoseGraphReset(handle: Long)
        @JvmStatic private external fun nativePoseGraphAddNode(handle: Long, pose: FloatArray, fixed: Boolean): Int

        @JvmStatic
        private external fun nativePoseGraphAddEdge(
            handle: Long,
            from: Int,
            to: Int,
            measurement: FloatArray,
            rotationWeight: Double,
            translationWeight: Double,
            loopClosure: Boolean,
        )

        @JvmStatic private external fun nativePoseGraphOptimize(handle: Long, iterations: Int): FloatArray?
        @JvmStatic private external fun nativeLoopCandidates(handle: Long): FloatArray?
        @JvmStatic private external fun nativeRtabMapAvailable(handle: Long): Boolean
    }
}
