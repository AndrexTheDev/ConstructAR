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
import com.constructar.arengine.keyframe.KeyframeApplier
import com.constructar.arengine.keyframe.KeyframeFeed
import com.constructar.arengine.keyframe.KeyframeQueue
import com.constructar.arengine.keyframe.NativePoseGraphSink
import com.constructar.arengine.keyframe.PoseGraphStatus
import com.constructar.arengine.keyframe.ClosureState
import com.constructar.arengine.keyframe.NativePoseGraphSource
import com.constructar.arengine.keyframe.PoseGraphCloser
import com.constructar.core.calib.MarkerSweep
import com.constructar.core.calib.MarkerSweepOptions
import com.constructar.core.slam.CorrectionReport
import com.constructar.nativebridge.FloorplanPayload
import com.constructar.nativebridge.IntegrationResult
import com.constructar.nativebridge.NativeSlamEngine
import com.constructar.nativebridge.ScanMesh
import com.constructar.nativebridge.TsdfConfig
import com.google.ar.core.Frame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
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
 * The channel holds one frame: if integration is slower than the camera, the frame that
 * arrives while one is queued is refused rather than queuing stale geometry. Dropped frames
 * are counted and surfaced in [ScanState], because a scan that silently integrates 8 frames
 * per second instead of 30 looks the same to the user but has half the coverage.
 *
 * The channel used to be `DROP_OLDEST`, which kept the *newest* frame by discarding the queued
 * one — and, because a discarded channel element is never handed to anybody, its 600 kB depth
 * array never went back to the pool: every dropped frame cost a fresh allocation in the render
 * loop, in the thermal case where the pool exists for (finding 168). Refusing the new frame
 * costs the same one frame of coverage and returns the buffer on the spot.
 *
 * ## Thermal / battery
 *
 * Extraction is throttled to **300 ms** (≈3.3 Hz) as required. When the platform reports
 * `THERMAL_STATUS_SEVERE`/`CRITICAL`, the pipeline drops to 700 ms and integrates every other
 * frame — counted by [ThermalFrameGate], because the frame *timestamps* cannot answer that
 * question (finding 169) — at a coarser pixel step. Measurements are never affected — only mesh
 * refresh rate.
 */
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
    /**
     * Live state for the HUD: the detail budget, the frame queue, the volume limit, the thermal
     * mode.
     *
     * Everything here is read by a screen, a document or a test — that is the whole point of a
     * state object. Until finding 171 it also carried six fields that were none of those:
     * `integrating` (only ever assigned `false`, so a spinner bound to it would never appear),
     * `vertexCount` and `blockCount` (nothing read them; the renderer and the exporters ask the
     * mesh, and the flag that matters is [hitBlockBudget]), and `error` (an English sentence
     * that neither a screen printed nor the log recorded — the screen keys off [ready] and says
     * its own localized sentence). A field in the HUD's contract that nothing can see is a
     * promise, and the operator was paying for it in copies of the state on every frame.
     */
    data class ScanState(
        val ready: Boolean = false,
        val framesIntegrated: Int = 0,
        val framesDropped: Int = 0,
        /** How long the last integration call took. Instrumentation — no screen shows it yet. */
        val lastIntegrationMillis: Int = 0,
        /** How long the last mesh extraction took. Instrumentation — no screen shows it yet. */
        val lastExtractionMillis: Int = 0,
        val triangleCount: Int = 0,
        val hitBlockBudget: Boolean = false,
        val throttleMode: ThrottleMode = ThrottleMode.NOMINAL,
        val meshVersion: Int = 0,
    ) {
        /**
         * How much of the reference **detail** budget the mesh fills, 0..1 (68 k triangles ≈ a
         * busy room).
         *
         * Deliberately not called coverage any more, and the HUD no longer calls it a room
         * (finding 102). It is a triangle budget ratio: a cluttered cellar reaches 100 % with a
         * third of its walls scanned, an empty hall may never get past 40 % with every wall
         * captured. The old label promised a fraction of the room, which this number cannot know -
         * the app never computes the room's total surface. What it does say is how much geometry
         * the mesh holds, which is what the operator needs to judge whether the detail is enough
         * to pick cables out of.
         */
        val detailFraction: Float
            get() = (triangleCount / 68_000f).coerceIn(0f, 1f)

        /**
         * Share of the frames that were offered to the integrator and never reached it, 0..1.
         *
         * The denominator is integrated **plus** dropped, not every camera frame: a frame the
         * thermal gate skips (finding 169) never joined the queue and is a deliberate reduction,
         * while a frame the queue refused is geometry the room will be missing. Until finding
         * 170 the pipeline counted both and no screen read either, so the documents promising
         * that "the scan looks sparse" is distinguishable from "the device is overloaded"
         * described a number nobody could see.
         */
        val droppedFraction: Float
            get() {
                val offered = framesIntegrated + framesDropped
                return if (offered <= 0) 0f else framesDropped.toFloat() / offered
            }

        /**
         * The state after one integration call, however that call went.
         *
         * A `null` result means the call failed or the engine was already gone — nothing
         * *known*, not "the volume is fine". The flag is therefore sticky: a spent block budget
         * never comes back (the volume has no deallocation), and the old `?: false` cleared it on
         * the first failed call, which silently took the "scan limit reached" sentence off the
         * screen and out of the protocol's `scanLimited` note (findings 97, 171).
         *
         * [framesIntegrated] counts frames that were *offered* to the integrator, not frames
         * that succeeded, because that is the denominator of [droppedFraction].
         */
        fun afterIntegration(result: IntegrationResult?, millis: Int): ScanState = copy(
            framesIntegrated = framesIntegrated + 1,
            lastIntegrationMillis = millis,
            hitBlockBudget = hitBlockBudget || result?.hitBlockBudget == true,
        )
    }

    enum class ThrottleMode { NOMINAL, WARM, THROTTLED }

    private val mutableState = MutableStateFlow(ScanState(ready = NativeSlamEngine.isAvailable))
    val state: StateFlow<ScanState> = mutableState.asStateFlow()

    val mesh = MutableStateFlow<ScanMesh?>(null)

    /**
     * The most recently captured depth frame.
     *
     * Kept alive for the hit-test cascade (tier 3) and the AI-depth calibration. The pipeline
     * holds one reference to it and drops that reference when a newer frame replaces it; the
     * buffer itself goes back to the pool only when the integration worker has also let go
     * (`DepthFrameSnapshot.release`, finding 168). Readers on the render thread — the hit
     * cascade, the AI pass, which copies what it needs immediately — may sample it for as long
     * as it is published.
     */
    @Volatile
    var latestSnapshot: DepthFrameSnapshot? = null
        private set

    /** Latest depth statistics (confidence, usable ratio) for the tracking monitor. */
    val depthStats: DepthCapture.Stats get() = capture.stats

    private val frames = Channel<DepthFrameSnapshot>(capacity = 1)

    /** Picks every other frame while the device is hot — see [ThermalFrameGate]. */
    private val thermalGate = ThermalFrameGate()
    private val capture = DepthCapture()
    private var scope: CoroutineScope? = null

    @Volatile
    private var engine: NativeSlamEngine? = null

    /**
     * Keyframes the render thread accepted, waiting for the thread that may touch the graph.
     *
     * `NativeSlamEngine`'s contract says integrate, extract and pose-graph calls are serialised by
     * this pipeline's single worker; the decision to keep a keyframe is made on the frame that
     * produced the pose. So the decision stays on the render thread and enqueues, and only an
     * accepted keyframe crosses over (finding 243).
     */
    @Volatile
    private var keyframeQueue = KeyframeQueue()

    /** Applies [keyframeQueue] to the native graph. Worker thread only; null after [stop]. */
    @Volatile
    private var keyframeApplier: KeyframeApplier? = null

    private val mutablePoseGraphStatus = MutableStateFlow(PoseGraphStatus())

    /**
     * How many keyframes the graph took and how many were lost on the way (finding 244).
     *
     * Published from the worker, which is the only place that knows both halves: the queue's own
     * drops (produced on the render thread) and the applier's refusals (counted here). Until
     * finding 244 every one of those counters was read by a unit test and by nothing else, so on a
     * device "the graph is empty" could not be told apart from "the queue overflowed".
     */
    val poseGraphStatus: StateFlow<PoseGraphStatus> = mutablePoseGraphStatus.asStateFlow()

    /**
     * What the loop-closure conversation is saying (finding 245).
     *
     * Published from the worker, where the graph is safe to touch; the screen only displays it and
     * the three buttons below enqueue.
     */
    private val mutableClosureState = MutableStateFlow<ClosureState>(ClosureState.Idle)
    val closureState: StateFlow<ClosureState> = mutableClosureState.asStateFlow()

    /** Asks arriving from the UI. Applied on the worker, never on the thread that tapped. */
    private val closureActions = Channel<ClosureAction>(capacity = 4)

    /**
     * How many closures this session's operator has confirmed, and what the last one moved
     * (finding 250). Kept here rather than in the closer because they outlive the conversation:
     * the dialog closes, the numbers stay on the graph line.
     */
    private var closedLoops = 0
    private var lastCorrection: CorrectionReport? = null

    /** `null` before the first closure, then the last one's verdict (finding 252). */
    private var lastClosureConverged: Boolean? = null

    private enum class ClosureAction { LOOK, CONFIRM, DISMISS }

    /** Runs the conversation on the worker. Null until the engine exists. */
    @Volatile
    private var poseGraphCloser: PoseGraphCloser? = null

    /**
     * What the render thread offers every placed frame to, or null until the engine exists.
     *
     * Public because the AR screen drives it: it is the one piece of the pose graph the frame loop
     * may touch, and it can only enqueue.
     */
    @Volatile
    var keyframeFeed: KeyframeFeed? = null
        private set

    private var workerJob: kotlinx.coroutines.Job? = null

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
            // The screen says its own localized sentence off `ready`; the reason belongs in the
            // log, where a support call can find it (finding 171).
            Log.w(TAG, "native SLAM library unavailable on this ABI — scanning disabled, measuring still works")
            mutableState.value = mutableState.value.copy(ready = false)
            return
        }
        mutableState.value = mutableState.value.copy(ready = true)
        // A queue per engine: a restart after `stop()` gets a fresh queue rather than a closed one,
        // so the second session cannot silently lose every keyframe.
        keyframeQueue = KeyframeQueue()
        val applier = KeyframeApplier(NativePoseGraphSink(created))
        keyframeApplier = applier
        keyframeFeed = KeyframeFeed(keyframeQueue)
        // The applier is also the node history: it is the only component that knows which pose
        // each native node was created with, and a closure report without it would be a sentence
        // about nothing (finding 246).
        poseGraphCloser = PoseGraphCloser(NativePoseGraphSource(created), applier)
        workerJob = scope.launch(dispatcher) {
            try {
                // Ends when the channel is closed and drained — see `stop()`.
                for (snapshot in frames) {
                    integrate(snapshot)
                }
            } finally {
                // The worker owns the free: it is the only thread that can still be inside the
                // native volume, and `stop()` must never delete the context under it. `close()` is
                // idempotent, so the path where `stop()` also closes costs one branch (finding 182).
                created.close()
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

        // Down-sample in time as well as in space when the device is hot. Counted, not read off
        // the clock: the parity of `frame.timestamp` is the camera HAL's business and an even
        // frame period makes it constant, which silently stopped the mesh from updating at all
        // (finding 169).
        if (mutableState.value.throttleMode == ThrottleMode.THROTTLED && !thermalGate.admits()) {
            // Nobody else ever saw this frame, so this is the last reference.
            snapshot.release()
            return
        }

        // Remember the stride the worker should use. Assigned before the send so the worker
        // can never observe a stride from a *newer* frame than the one it is integrating.
        pendingPixelStep = pixelStep
        // Publish the new frame and drop the previous publication. This releases *one*
        // reference: if the worker is still integrating the old frame it holds the other, and
        // its buffer stays out of the pool until it is done (finding 168).
        val previous = latestSnapshot
        latestSnapshot = snapshot
        previous?.release()

        // Hand the worker its own reference; it releases when integration returns. A refused
        // send means the worker still has a frame queued — that is the dropped frame the
        // operator deserves to see, since a scan that integrates 15 of 30 frames/second looks
        // the same on screen but has half the coverage.
        snapshot.retain()
        if (frames.trySend(snapshot).isFailure) {
            mutableState.value = mutableState.value.copy(framesDropped = mutableState.value.framesDropped + 1)
            snapshot.release()
        }
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
        // Read once: `stop()` clears the field, and a frame still in the queue must then be
        // released rather than integrated — a reference nobody drops keeps 600 kB out of the pool.
        // The worker is also the owner of the engine's lifetime (see the launch above and
        // `stop()`), so this call cannot outlive the volume (finding 182).
        val active = engine
        try {
            if (active != null) integrateLocked(active, snapshot)
        } finally {
            // The worker's reference. If the frame is still the published one the UI is
            // sampling, this only decrements; the array goes back to the pool when the render
            // thread replaces it (or immediately, if it already has).
            snapshot.release()
        }
    }

    private fun integrateLocked(active: NativeSlamEngine, snapshot: DepthFrameSnapshot) {
        // Every keyframe the render thread accepted goes in here: this is the only thread that may
        // touch the pose graph, and a keyframe is worth less than a frame - so the drain is
        // bounded and the rest waits for the next one.
        drainKeyframes()
        applyClosureActions()
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
        mutableState.value = mutableState.value.afterIntegration(result, integrationMillis)

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

    /**
     * One floorplan of the volume as it stands, taken on the scan worker.
     *
     * The volume is sliced on the worker and nowhere else: `nativeFloorplan` holds the volume lock
     * for the whole walk, so calling it from the main thread would block the next integration and
     * drop frames - and calling it from a *third* thread would be the very race the lock exists to
     * prevent. `withContext(dispatcher)` puts it in the queue behind the frame being integrated.
     *
     * The wait is bounded and short. A floorplan is a picture for a card, not a measurement: if the
     * worker is busy with a large extraction, the caller gets `null` and the room simply has no
     * picture, which is better than a screen that hangs when a run is committed. Returns `null`
     * too when the library never loaded, and when the parameters the native side refuses come back
     * as a null payload.
     */
    fun floorplan(
        heightMetres: Float = FLOORPLAN_HEIGHT_METRES,
        bandMetres: Float = FLOORPLAN_BAND_METRES,
        /**
         * How far a wall run may sit off the room's own axes and still be squared onto them, in
         * degrees; 0 turns the step off.
         *
         * The default is on because the outline this returns is not a diagnostic: it is stored as
         * the room's sketch, drawn as the card's picture, and measured again by the wall screen
         * (wall lengths, areas, board order). A 5-degree shear in that outline is a wall 20 cm
         * longer on a 4 m run, on every wall, in the purchase order.
         */
        manhattanToleranceDegrees: Float = FLOORPLAN_MANHATTAN_TOLERANCE_DEGREES,
    ): FloorplanPayload? {
        if (engine == null) return null
        return kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(FLOORPLAN_TIMEOUT_MS) {
                kotlinx.coroutines.withContext(dispatcher) {
                    engine?.floorplan(heightMetres, bandMetres, manhattanToleranceDegrees = manhattanToleranceDegrees)
                }
            }
        }
    }

    /**
     * What the last floorplan's Manhattan step did - the residual and how much of the outline it
     * moved.
     *
     * A diagnostic for the same caller that took the plan, and the reason it exists: "the card
     * shows a rectangle" and "the card shows a rectangle because the app squared it" are two
     * different claims, and only one of them is a measurement. Must be asked straight after
     * [floorplan] - the native context keeps only the newest fit.
     */
    fun lastManhattan(): com.constructar.nativebridge.ManhattanReport? = engine?.lastManhattan()

    /**
     * The two knobs the marker sweep shares with the native layout path, straight out of
     * `TagScaleOptions`, or `null` when the native layer is unavailable.
     *
     * The screen must not restate them: a baseline floor invented in `:app` would be a second
     * definition of "a distance worth measuring" that disagrees with the one already in C++ the
     * first time either is tuned (finding 235).
     */
    fun markerKnobs(): Pair<Float, Float>? = engine?.tagScaleDefaults()

    /**
     * Opens a marker sweep wired to the vendored decoder, or `null` when the marker path is not
     * available on this device.
     *
     * The composition is here rather than in `:app` on purpose: the knobs come from the native
     * `TagScaleOptions`, the decoder is the engine's, and the session id is the caller's. A screen
     * that built its own would have to name `EngineTagDecoder`, and would then own the lifetime of
     * an engine it does not own.
     */
    fun openMarkerProbe(sessionId: Long): MarkerProbe? {
        val active = engine ?: return null
        val knobs = active.tagScaleDefaults() ?: return null
        val sweep = MarkerSweep.start(
            MarkerSweepOptions(
                sessionId = sessionId,
                minBaselineMetres = knobs.first,
                maxRelativeDeviation = knobs.second,
            ),
        ) ?: return null
        return MarkerProbe(MarkerFrameReader(EngineTagDecoder(active)), sweep)
    }

    /**
     * The frame the last [floorplan] was squared onto, fitted from the room's walls.
     *
     * Read on the same worker and straight after the plan: the native context keeps the newest
     * frame only, and a frame from the previous room would be a true statement about another job.
     */
    fun manhattanFrame(): com.constructar.nativebridge.ManhattanFrameReport? =
        engine?.alignManhattanFrame()

    /** Clears the volume and pose graph (new room). */
    fun reset() {
        engine?.reset()
        latestSnapshot?.release()
        latestSnapshot = null
        mesh.value = null
        mutableState.value = mutableState.value.copy(
            framesIntegrated = 0,
            framesDropped = 0,
            triangleCount = 0,
            meshVersion = 0,
        )
    }

    /**
     * Stops the worker and releases the native volume.
     *
     * Closing the channel ends the worker's loop after the frame it is on, and **the worker frees
     * the engine** when that loop ends (`finally`, in the launch above): it is the only thread that
     * can still be inside the native volume, so it is the only one that may delete the context.
     *
     * Until finding 182 this function did the opposite - it waited for the loop with a bounded
     * `withTimeoutOrNull(500)`, and then called `engine?.close()` whether or not the wait had
     * finished. Half a second is not a guarantee, it is a timeout: a mesh extraction on a large
     * volume can take longer on a hot phone (the pipeline itself allows 700 ms between
     * extractions when `THROTTLED`), so the context could be deleted while `gatherBlockGrid` was
     * walking its shards - a native crash at teardown, on exactly the device class the thermal
     * handling exists for. The wait remains, because the caller wants the screen torn down, but it
     * no longer decides when the memory is freed.
     */
    fun stop() {
        latestSnapshot?.release()
        latestSnapshot = null
        frames.close()
        // Order matters: no new keyframes, then no applier (the worker reads it per command), and
        // the engine is closed by the worker itself - so nothing is applied to a freed context.
        keyframeFeed = null
        keyframeApplier = null
        keyframeQueue.close()
        mutablePoseGraphStatus.value = PoseGraphStatus()
        closedLoops = 0
        lastCorrection = null
        lastClosureConverged = null
        poseGraphCloser = null
        mutableClosureState.value = ClosureState.Idle
        val job = workerJob
        workerJob = null
        val active = engine
        engine = null
        scope = null
        if (job == null) {
            // No worker was ever started (the library was missing, or `start` failed before it
            // launched): this thread is the only user of the volume, so it frees it itself.
            active?.close()
            return
        }
        // Bounded so a wedged device cannot hang teardown - and no longer a licence to free: if
        // the worker never returns, the engine stays alive until it does, which is the safe
        // direction (a leaked volume versus a use-after-free).
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(WORKER_JOIN_TIMEOUT_MS) { job.join() }
        }
    }

    // ── Loop closure ────────────────────────────────────────────────────────────────────

    /** Ask the detector whether the walk has come back somewhere it has been. */
    fun lookForClosure() {
        closureActions.trySend(ClosureAction.LOOK)
    }

    /** The operator confirmed: apply the closure and optimise, on the worker. */
    fun confirmClosure() {
        closureActions.trySend(ClosureAction.CONFIRM)
    }

    /** The operator refused: remembered, and the next question waits for the cooldown. */
    fun dismissClosure() {
        closureActions.trySend(ClosureAction.DISMISS)
    }

    /**
     * Runs the queued ask/confirm/refuse on the worker.
     *
     * The node count is the applier's, not the graph's: it is the number of keyframes this session
     * actually fed in, which is the number the policy's floor is written against.
     */
    private fun applyClosureActions() {
        val closer = poseGraphCloser ?: return
        while (true) {
            val action = closureActions.tryReceive().getOrNull() ?: return
            val now = android.os.SystemClock.uptimeMillis()
            val next = when (action) {
                ClosureAction.LOOK -> closer.look(keyframeApplier?.appliedNodes ?: 0, now)
                ClosureAction.CONFIRM -> closer.confirm(now)
                ClosureAction.DISMISS -> closer.dismiss(now)
            }
            if (next is ClosureState.Closed) {
                // The edge is in the graph whether or not the solver finished, so the count is
                // every confirmation - but only a converged run has a report to keep, and the
                // report of an *earlier* closure must not stand in for this one's (finding 252:
                // assigning through `let` left the previous numbers on screen as if they were
                // the last closure's).
                closedLoops += 1
                lastClosureConverged = next.optimisation.converged
                lastCorrection = next.correction
            }
            mutableClosureState.value = next
        }
    }

    /**
     * Applies what the render thread queued. Caller must be the worker: see [keyframeApplier].
     */
    private fun drainKeyframes() {
        val applier = keyframeApplier ?: return
        var drained = 0
        while (drained < MAX_KEYFRAMES_PER_INTEGRATION) {
            val command = keyframeQueue.poll() ?: break
            applier.apply(command)
            drained += 1
        }
        // Published on every integrated frame, not only when something was drained: "0 nodes and
        // 0 lost" is itself the answer a support call needs, and StateFlow drops the repeats.
        mutablePoseGraphStatus.value = PoseGraphStatus.of(
            applier = applier,
            droppedByQueue = keyframeQueue.dropped,
            closures = closedLoops,
            lastCorrection = lastCorrection,
            lastClosureConverged = lastClosureConverged,
        )
    }

    companion object {
        private const val TAG = "TsdfScanPipeline"

        /**
         * At 30 integrated frames a second this is eight times the fastest rate the keyframe
         * policy allows, so the queue drains in practice; the bound only exists so that a keyframe
         * cannot delay the frame that pays for the walls.
         */
        private const val MAX_KEYFRAMES_PER_INTEGRATION = 8

        /** A fused frame older than this is stale: the camera has moved on. */
        private const val FUSION_TTL_MS = 800L

        /** Required by the specification: mesh refresh at ≈3.3 Hz, not per frame. */
        const val EXTRACT_INTERVAL_MS = 300L

        /** How long `stop()` waits for the worker before returning; never how long it waits to free. */
        private const val WORKER_JOIN_TIMEOUT_MS = 500L

        /**
         * Waist height: above worktops and below door frames, so the cut meets walls and nothing a
         * fitter would call furniture. The same 1.0 m the native slice is documented with.
         */
        private const val FLOORPLAN_HEIGHT_METRES = 1.0f

        /** A 10 cm band: one TSDF voxel plus slack around the cut. */
        private const val FLOORPLAN_BAND_METRES = 0.10f

/**
 * Default Manhattan tolerance for the floorplan: drift plus the slice's own noise, in degrees.
 *
 * Ten degrees is wide enough for the shear a hand-held scan produces and narrow enough that a
 * chamfer or a bay window - the runs that are deliberately not square - stays where it is.
 */
const val FLOORPLAN_MANHATTAN_TOLERANCE_DEGREES = 10f

        /**
         * How long a card picture may cost the caller.
         *
         * A slice of a scanned room walks every shard; measured on the host suite it is well under
         * a frame's worth of work for a room, so this is a generous ceiling that only a wedged
         * worker or a very large volume reaches - and in that case the picture is what is dropped.
         */
        private const val FLOORPLAN_TIMEOUT_MS = 400L
    }
}
