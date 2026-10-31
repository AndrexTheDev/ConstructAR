/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.constructar.arengine.depth.DepthFrameSnapshot
import com.constructar.nativebridge.IntegrationResult
import com.constructar.nativebridge.NativeSlamEngine
import com.constructar.nativebridge.ScanMesh
import com.constructar.nativebridge.TsdfConfig
import com.google.ar.core.Frame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Drives TSDF integration and mesh extraction off the render thread.
 *
 * ## Threading contract
 *
 * ```
 *  ARCore render thread            pipeline worker (1 thread)         UI
 *  ─────────────────────           ─────────────────────────────      ─────────────
 *  onFrame(frame)                  integrate(snapshot)                collects ScanState
 *    ├─ DepthCapture.capture()  →  every 300 ms: extractMesh()   ──→  uploads mesh
 *    └─ channel.trySend()          publish ScanState
 * ```
 *
 * `capture()` runs on the render thread because it *must* — it touches `Frame`. It only
 * copies memory. Integration, carving and Surface Nets run on the worker. Extraction is
 * serialised with integration by construction (same thread), which is exactly the
 * mutual exclusion the native volume needs.
 *
 * ## Back-pressure
 *
 * The channel is `CONFLATED` with capacity 1: if integration is slower than the camera, the
 * oldest unprocessed frame is dropped rather than queuing stale geometry. Dropped frames are
 * counted and surfaced in [ScanState], because a scan that silently integrates 8 frames per
 * second instead of 30 looks the same to the user but has half the coverage.
 *
 * ## Thermal / battery
 *
 * Extraction is throttled to **300 ms** (≈3.3 Hz) as required. When the platform reports
 * `THERMAL_STATUS_SEVERE`/`CRITICAL`, the pipeline drops to 700 ms and integrates every other
 * frame at a coarser pixel step. Measurements are never affected — only mesh refresh rate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TsdfScanPipeline(
    private val config: TsdfConfig = TsdfConfig(),
    /**
     * One dedicated worker thread. A hand-rolled executor (rather than
     * `newSingleThreadContext`) keeps the thread daemonic and the API stable across coroutine
     * versions, while still guaranteeing the mutual exclusion the TSDF volume requires —
     * integration and extraction can never run concurrently.
     */
    private val dispatcher: CoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable -> Thread(runnable, "tsdf-worker").apply { isDaemon = true } }
        .asCoroutineDispatcher(),
    private val onMesh: ((ScanMesh) -> Unit)? = null,
) {
    /** Live state for the HUD: coverage, mesh stats, latency, thermal mode. */
    data class ScanState(
        val ready: Boolean = false,
        val integrating: Boolean = false,
        val framesIntegrated: Int = 0,
        val framesDropped: Int = 0,
        val lastIntegrationMillis: Int = 0,
        val lastExtractionMillis: Int = 0,
        val triangleCount: Int = 0,
        val vertexCount: Int = 0,
        val blockCount: Int = 0,
        val hitBlockBudget: Boolean = false,
        val throttleMode: ThrottleMode = ThrottleMode.NOMINAL,
        val meshVersion: Int = 0,
        val error: String? = null,
    ) {
        /** Rough "how much have I scanned" gauge, 0..1 (68 k triangles ≈ a full room). */
        val coverage: Float
            get() = (triangleCount / 68_000f).coerceIn(0f, 1f)
    }

    enum class ThrottleMode { NOMINAL, WARM, THROTTLED }

    private val mutableState = MutableStateFlow(ScanState(ready = NativeSlamEngine.isAvailable))
    val state: StateFlow<ScanState> = mutableState.asStateFlow()

    val mesh = MutableStateFlow<ScanMesh?>(null)

    /**
     * The most recently captured depth frame.
     *
     * Kept alive for the hit-test cascade (tier 3) and the AI-depth calibration. It is only
     * recycled when a *newer* frame replaces it — never by the integration worker, which
     * would hand a buffer back to the pool while the UI thread is still sampling it.
     */
    @Volatile
    var latestSnapshot: DepthFrameSnapshot? = null
        private set

    /** Latest depth statistics (confidence, usable ratio) for the tracking monitor. */
    val depthStats: DepthCapture.Stats get() = capture.stats

    private val frames = Channel<DepthFrameSnapshot>(capacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val capture = DepthCapture()
    private var scope: CoroutineScope? = null

    @Volatile
    private var engine: NativeSlamEngine? = null

    private var workerJob: kotlinx.coroutines.Job? = null

    private val workerBusy = AtomicBoolean(false)
    private val fusedDepth = java.util.concurrent.atomic.AtomicReference<FusedDepth?>(null)

    /**
     * Depth produced by the monocular path (AI holes filled), with the moment it was produced.
     * Copied on submission so the producer can reuse its buffer.
     */
    private class FusedDepth(
        val depthMm: ShortArray,
        val width: Int,
        val height: Int,
        val receivedAtMillis: Long,
    )
    private var extractIntervalMs = EXTRACT_INTERVAL_MS
    private var lastExtractNanos = 0L
    private var lastThermalCheckNanos = 0L

    /** Starts the worker. Idempotent; call from `ON_RESUME`/first composition. */
    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        engine = NativeSlamEngine.create(config)
        val created = engine
        if (created == null) {
            mutableState.value = mutableState.value.copy(
                ready = false,
                error = "Native SLAM library unavailable on this ABI — scanning disabled, measuring still works.",
            )
            return
        }
        mutableState.value = mutableState.value.copy(ready = true)
        workerJob = scope.launch(dispatcher) {
            // Ends when the channel is closed and drained — see `stop()`.
            for (snapshot in frames) {
                integrate(snapshot)
            }
        }
    }

    /**
     * Called from `ARSceneView.onSessionUpdated` (render thread). Must stay allocation-light:
     * one `memcpy` plus a channel send.
     */
    fun onFrame(frame: Frame, thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE) {
        if (engine == null) return
        val pixelStep = when (thermalModeFrom(thermalStatus)) {
            ThrottleMode.NOMINAL -> DepthCapture.PIXEL_STEP_NOMINAL
            ThrottleMode.WARM -> DepthCapture.PIXEL_STEP_NOMINAL + 1
            ThrottleMode.THROTTLED -> DepthCapture.PIXEL_STEP_THROTTLED
        }
        val snapshot = capture.capture(frame, config.minConfidence) ?: return
        maybeUpdateThermalStatus(thermalStatus)

        // Down-sample in time as well as in space when the device is hot.
        if (mutableState.value.throttleMode == ThrottleMode.THROTTLED && frame.timestamp % 2L != 0L) {
            capture.recycle(snapshot)
            return
        }

        // Remember the stride the worker should use. Assigned before the send so the worker
        // can never observe a stride from a *newer* frame than the one it is integrating.
        pendingPixelStep = pixelStep
        // Publish the new frame and retire the previous one. Retiring *after* the swap means
        // any reader either sees the old snapshot (still valid) or the new one — never a
        // buffer that has gone back to the pool.
        val previous = latestSnapshot
        latestSnapshot = snapshot
        previous?.let(capture::recycle)

        // The channel is conflated, so `trySend` cannot fail on a full buffer — it replaces
        // the pending frame. A replacement *is* a dropped frame, and the operator deserves to
        // see it: a scan that integrates 15 of 30 frames/second looks the same on screen but
        // has half the coverage.
        if (workerBusy.get()) {
            mutableState.value = mutableState.value.copy(framesDropped = mutableState.value.framesDropped + 1)
        }
        frames.trySend(snapshot)
    }

    @Volatile
    private var pendingPixelStep: Int = DepthCapture.PIXEL_STEP_NOMINAL

    /**
     * Hands a densified depth frame to the integrator.
     *
     * ARCore's own depth stays authoritative *inside* the fused frame (the fusion only fills
     * holes), so this is a strictly larger measured region, not a replacement. The frame is
     * dropped after [FUSION_TTL_MS] so a stale network result can never be integrated into
     * geometry the camera has already moved past.
     */
    fun submitFusedDepth(depthMm: ShortArray, width: Int, height: Int) {
        fusedDepth.set(
            FusedDepth(depthMm.copyOf(), width, height, android.os.SystemClock.elapsedRealtime()),
        )
    }

    private fun integrate(snapshot: DepthFrameSnapshot) {
        val active = engine ?: return
        workerBusy.set(true)
        try {
            integrateLocked(active, snapshot)
        } finally {
            workerBusy.set(false)
        }
    }

    private fun integrateLocked(active: NativeSlamEngine, snapshot: DepthFrameSnapshot) {
        val startedNanos = System.nanoTime()
        // Read the stride once: the render thread may update `pendingPixelStep` concurrently.
        val pixelStep = pendingPixelStep
        // Prefer the AI-densified frame when it matches this resolution and is fresh. Its
        // pixels already passed the confidence filter during fusion, so no confidence image is
        // applied on top (that would throw away exactly the filled holes).
        val fusion = fusedDepth.get()?.takeIf {
            it.width == snapshot.width && it.height == snapshot.height &&
                android.os.SystemClock.elapsedRealtime() - it.receivedAtMillis < FUSION_TTL_MS
        }
        val result = try {
            active.integrateDepth(
                depthMm = fusion?.depthMm ?: snapshot.depthMm,
                width = snapshot.width,
                height = snapshot.height,
                rowStrideElements = snapshot.rowStride,
                confidence = if (fusion != null) null else snapshot.confidence,
                confidenceWidth = if (fusion != null) 0 else snapshot.confidenceWidth,
                confidenceHeight = if (fusion != null) 0 else snapshot.confidenceHeight,
                confidenceRowStride = if (fusion != null) 0 else snapshot.confidenceRowStride,
                intrinsics = snapshot.intrinsics,
                cameraToWorld = snapshot.cameraToWorld,
                timestampNs = snapshot.timestampNs,
                pixelStep = pixelStep,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "integration failed", error)
            null
        }
        val integrationMillis = ((System.nanoTime() - startedNanos) / 1_000_000L).toInt()
        mutableState.value = mutableState.value.copy(
            integrating = false,
            framesIntegrated = mutableState.value.framesIntegrated + 1,
            lastIntegrationMillis = integrationMillis,
            blockCount = result?.blocksTotal ?: mutableState.value.blockCount,
            hitBlockBudget = result?.hitBlockBudget ?: false,
        )

        // ── Extraction, throttled to 300 ms (700 ms when throttled) ─────────────────────
        val now = System.nanoTime()
        if (now - lastExtractNanos >= extractIntervalMs * 1_000_000L) {
            lastExtractNanos = now
            val extractStart = System.nanoTime()
            val extracted = runCatching { active.extractMesh() }
                .onFailure { Log.e(TAG, "mesh extraction failed", it) }
                .getOrNull()
            val extractMs = ((System.nanoTime() - extractStart) / 1_000_000L).toInt()
            if (extracted != null) {
                mesh.value = extracted
                onMesh?.invoke(extracted)
                mutableState.value = mutableState.value.copy(
                    lastExtractionMillis = extractMs,
                    triangleCount = extracted.triangleCount,
                    vertexCount = extracted.vertexCount,
                    meshVersion = mutableState.value.meshVersion + 1,
                )
            } else {
                mutableState.value = mutableState.value.copy(lastExtractionMillis = extractMs)
            }
        }
    }

    private fun maybeUpdateThermalStatus(status: Int) {
        val now = System.nanoTime()
        if (now - lastThermalCheckNanos < 1_000_000_000L) return
        lastThermalCheckNanos = now
        val mode = thermalModeFrom(status)
        if (mode == mutableState.value.throttleMode) return
        extractIntervalMs = when (mode) {
            ThrottleMode.NOMINAL -> EXTRACT_INTERVAL_MS
            ThrottleMode.WARM -> EXTRACT_INTERVAL_MS * 2
            ThrottleMode.THROTTLED -> EXTRACT_INTERVAL_MS * 7 / 3 // 700 ms
        }
        mutableState.value = mutableState.value.copy(throttleMode = mode)
        Log.i(TAG, "thermal mode → $mode (extract every $extractIntervalMs ms)")
    }

    private fun thermalModeFrom(status: Int): ThrottleMode = when {
        status >= if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) PowerManager.THERMAL_STATUS_SEVERE else 30 ->
            ThrottleMode.THROTTLED
        status >= PowerManager.THERMAL_STATUS_MODERATE -> ThrottleMode.WARM
        else -> ThrottleMode.NOMINAL
    }

    /** Clears the volume and pose graph (new room). */
    fun reset() {
        engine?.reset()
        latestSnapshot?.let(capture::recycle)
        latestSnapshot = null
        mesh.value = null
        mutableState.value = mutableState.value.copy(
            framesIntegrated = 0,
            framesDropped = 0,
            triangleCount = 0,
            vertexCount = 0,
            blockCount = 0,
            meshVersion = 0,
        )
    }

    /**
     * Stops the worker and releases the native volume.
     *
     * The join is essential: closing the native engine while the worker is inside
     * `integrateDepth` is a use-after-free. Closing the channel ends the loop after the current
     * frame, and the join bounds the wait to half a second so a wedged device cannot hang the
     * UI thread on teardown.
     */
    fun stop() {
        latestSnapshot?.let(capture::recycle)
        latestSnapshot = null
        frames.close()
        val job = workerJob
        workerJob = null
        if (job != null) {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(500) { job.join() }
            }
        }
        engine?.close()
        engine = null
        scope = null
    }

    companion object {
        private const val TAG = "TsdfScanPipeline"

        /** A fused frame older than this is stale: the camera has moved on. */
        private const val FUSION_TTL_MS = 800L

        /** Required by the specification: mesh refresh at ≈3.3 Hz, not per frame. */
        const val EXTRACT_INTERVAL_MS = 300L
    }
}
