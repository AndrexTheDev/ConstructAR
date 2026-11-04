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

    fun reset() {
        val active = handle
        if (active != 0L) nativeReset(active)
    }

    // ── Pose graph (loop closure) ────────────────────────────────────────────────────────
    // Not wired into the scan pipeline yet (finding 98): no keyframe reaches this graph, so the
    // app measures on ARCore's own tracking. See docs/SETUP.md §5.2 before quoting a closure.

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
     * Runs the optimiser and returns the refreshed world poses.
     *
     * Not called by the scan pipeline yet (finding 98): the graph is empty in the shipped app,
     * so this returns a report that carries no poses. See docs/SETUP.md §5.2.
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
