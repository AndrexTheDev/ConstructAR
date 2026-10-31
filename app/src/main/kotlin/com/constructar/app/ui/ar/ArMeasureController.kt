/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.constructar.arengine.hit.ArInteraction
import com.constructar.arengine.hit.HitResolution
import com.constructar.arengine.render.RouteSceneController
import com.constructar.arengine.render.SceneMeshRenderer
import com.constructar.arengine.render.SceneViewMaterialProvider
import com.constructar.arengine.route.AnchorFactory
import com.constructar.arengine.route.AnchorHandle
import com.constructar.arengine.route.ArCoreAnchorFactory
import com.constructar.arengine.route.RouteSession
import com.constructar.arengine.scan.TsdfScanPipeline
import com.constructar.arengine.session.ArReadiness
import com.constructar.arengine.session.ArSessionController
import com.constructar.arengine.session.TorchController
import com.constructar.arengine.tracking.TrackingMonitor
import com.constructar.arengine.tracking.TrackingQuality
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.ScanSummary
import com.google.android.filament.Engine
import com.google.android.filament.Scene
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.loaders.MaterialLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Anchor factory that can be created before the ARCore session exists. */
class DelegatingAnchorFactory : AnchorFactory {
    @Volatile
    var delegate: AnchorFactory? = null

    override fun create(position: Vec3, normal: Vec3): AnchorHandle? = delegate?.create(position, normal)
}

/**
 * Owns everything that lives as long as the AR screen is open.
 *
 * Created once per screen (via `remember`), *not* per recomposition: it holds the native TSDF
 * volume, ARCore anchors and GPU buffers, all of which are expensive to recreate and
 * catastrophic to leak. The Compose layer only reads its [StateFlow]s.
 *
 * Threading: [onFrame] is called from SceneView's render thread and does nothing but copy
 * depth, enqueue work, and update monotonic counters. Everything else is main-thread only.
 */
class ArMeasureController(private val context: Context) {

    private val tsdfConfig = com.constructar.nativebridge.TsdfConfig()
    val pipeline = TsdfScanPipeline(config = tsdfConfig)
    val tracking = TrackingMonitor()
    private val sessionController = ArSessionController(context)
    private val anchors = DelegatingAnchorFactory()
    val routeSession = RouteSession(anchors)

    private var interaction: ArInteraction? = null
    private var renderer: SceneMeshRenderer? = null
    private var sceneController: RouteSceneController? = null
    private var torchController: TorchController? = null
    private var monocularDepth: com.constructar.arengine.ai.MonocularDepthSource? = null
    private var powerManager: PowerManager? = null

    /**
     * Scratch for ARCore's colour correction, whose 4th component is the average pixel
     * intensity. Reused because this runs once per frame on the render thread.
     */
    private val colorCorrection = FloatArray(4)

    val torchOn = MutableStateFlow(false)

    /**
     * Screen position (pixels) of the floating length label, or null when there is nothing to
     * label. Computed on the render thread from the camera matrices and published for the HUD.
     */
    val hudScreenPixels = MutableStateFlow<Pair<Float, Float>?>(null)
    val lastHit: MutableStateFlow<HitResolution?> = MutableStateFlow(null)

    /**
     * Set from the render thread when a tap has been resolved, consumed on the main thread.
     * The hand-off exists because `Session.createAnchor` belongs to the UI thread while the
     * `Frame` that produced the hit belongs to the render thread.
     */
    private val mutableHitEvents = MutableStateFlow<HitResolution?>(null)
    val hitEvents: StateFlow<HitResolution?> = mutableHitEvents

    @Volatile
    private var session: Session? = null

    /**
     * ARCore policy hook handed to SceneView's typed `sessionConfiguration`.
     *
     * SceneView applies it immediately before `Session.configure()`, which is the only moment
     * ARCore accepts new settings. This must stay free of Compose and of any `configure()` call.
     */
    fun applySessionPolicy(session: Session, config: Config) = sessionController.applyPolicy(session, config)

    /** True when the live session has depth (drives the "plane only" warning). */
    val depthAvailable: Boolean get() = sessionController.depthAvailable

    /** ARCore readiness check, surfaced as a localised blocking message when it is not [ArReadiness.Ready]. */
    suspend fun checkReadiness(activity: android.app.Activity?, hasCameraPermission: Boolean): ArReadiness =
        sessionController.checkReadiness(activity, hasCameraPermission)

    /**
     * Called once when ARCore has a live session (also after a relocalisation).
     *
     * Synchronised because SceneView reports a new session both from its typed
     * `onSessionCreated` (main thread) and, defensively, from `onSessionUpdated` (render
     * thread): the guard inside must not be able to run twice concurrently.
     */
    @Synchronized
    fun onSessionCreated(session: Session) {
        if (this.session === session && interaction != null) return
        this.session = session
        anchors.delegate = ArCoreAnchorFactory(session)
        interaction = ArInteraction(session).also {
            it.previousNode = routeSession.state.value.nodes.lastOrNull()
            it.onTapResolved = { resolution -> mutableHitEvents.value = resolution }
        }
        if (powerManager == null) {
            powerManager = context.getSystemService(PowerManager::class.java)
        }
        if (monocularDepth == null) {
            // Optional: silently inactive when no model is bundled or calibration is refused.
            monocularDepth = com.constructar.arengine.ai.MonocularDepthSource(
                context = context,
                onFused = { depth, width, height, _ -> pipeline.submitFusedDepth(depth, width, height) },
            ).also { it.warmUp() }
        }
        // The torch is applied by SceneView through the typed `flashMode` parameter; this
        // controller only decides *when* it should be on (hysteresis, battery, manual override).
        torchController = TorchController(
            switch = { enabled ->
                torchOn.value = enabled
                true
            },
        )
    }

    /**
     * Called from [com.constructar.arengine.render.ArScene] once the Filament scene exists.
     *
     * Runs on the main thread, which is a hard requirement: `MaterialLoader`'s colour
     * factories are `@MainThread`, and the provider pre-warms the whole palette here so a
     * frame never allocates a material.
     */
    fun onSceneReady(scene: Scene, engine: Engine, materialLoader: MaterialLoader) {
        val meshRenderer = SceneMeshRenderer(engine, scene, SceneViewMaterialProvider(materialLoader))
        renderer = meshRenderer
        sceneController = RouteSceneController(meshRenderer)
    }

    fun onSceneReleased() {
        renderer?.release()
        renderer = null
        sceneController = null
    }

    /**
     * Render-thread entry point. Order matters:
     *  1. capture depth (cheap copy) and hand it to the TSDF worker;
     *  2. resolve any pending tap against the *current* frame;
     *  3. refresh anchors and the tracking verdict.
     */
    fun onFrame(session: Session, frame: Frame, viewWidth: Int, viewHeight: Int) {
        onSessionCreated(session)
        val thermalStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        pipeline.onFrame(frame, thermalStatus)

        val activeInteraction = interaction
        if (activeInteraction != null) {
            activeInteraction.latestSnapshot = pipeline.latestSnapshot
            // Aim the reticle at the screen centre: the operator holds the phone in front of
            // the wall, so the centre pixel is what they are pointing at.
            if (activeInteraction.reticlePoint == null && viewWidth > 0) {
                activeInteraction.reticlePoint = (viewWidth / 2f) to (viewHeight / 2f)
            }
            activeInteraction.onFrameEnter(frame, viewWidth, viewHeight, SystemClock.uptimeMillis())
            // Live preview follows the reticle (throttled inside ArInteraction to 12 Hz).
            routeSession.setLivePoint(activeInteraction.reticle.value)
        }

        routeSession.onFrame()
        updateHudPosition(frame, viewWidth, viewHeight)

        // Monocular depth: throttled internally to ~2 Hz; copies what it needs immediately.
        pipeline.latestSnapshot?.let { snapshot -> monocularDepth?.onFrame(frame, snapshot) }

        val camera = frame.camera
        // ARCore's TrackingState has exactly three values. PAUSED covers both the warm-up
        // before the first tracked frame and a backgrounded session, so it maps to LIMITED
        // ("hold still") rather than to a loss the operator has to react to.
        val quality = when (camera.trackingState) {
            TrackingState.TRACKING -> TrackingQuality.GOOD
            TrackingState.PAUSED -> TrackingQuality.LIMITED
            TrackingState.STOPPED -> TrackingQuality.NOT_TRACKING
        }
        val stats = pipeline.depthStats
        val ambientLux = ambientLuxOf(frame.lightEstimate, colorCorrection)
        tracking.onFrame(
            tracking = quality,
            // `trackingFailureReason` is non-null (NONE while tracking is fine); it only
            // becomes interesting once quality has dropped.
            failureReason = if (quality == TrackingQuality.GOOD) {
                null
            } else {
                camera.trackingFailureReason.takeIf { it != TrackingFailureReason.NONE }?.name
            },
            depthConfidence = stats.meanConfidence,
            depthSupported = pipeline.state.value.ready,
            ambientLux = ambientLux,
            torchOn = torchOn.value,
            thermallyThrottled = pipeline.state.value.throttleMode == TsdfScanPipeline.ThrottleMode.THROTTLED,
            scanCoverage = stats.usableRatio,
        )
        torchController?.onAmbientIntensity(ambientLux, batteryPercent())
    }

    /** Called from the UI thread when the operator taps the AR view. */
    fun requestTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        interaction?.requestTap(x, y, viewWidth, viewHeight)
    }

    /** Applies the tap that was resolved on the render thread (main thread only). */
    fun applyPendingHit(): HitResolution? {
        val resolution = mutableHitEvents.value ?: return null
        mutableHitEvents.value = null
        lastHit.value = resolution
        val hit = resolution.hit ?: return resolution
        if (routeSession.addNode(hit)) {
            interaction?.previousNode = routeSession.state.value.nodes.lastOrNull()
        }
        return resolution
    }

    /**
     * Uploads the latest TSDF mesh so the operator sees the room being reconstructed.
     *
     * This re-uploads the whole mesh whenever extraction produced a new one (≤ 3.3 Hz by
     * design). A future optimisation is an incremental buffer update, but at 30 k triangles
     * the upload costs ~2 ms and never blocks the render thread.
     */
    fun syncScanMesh(visible: Boolean) {
        val meshRenderer = renderer ?: return
        if (!visible) {
            meshRenderer.setVisible(SCAN_MESH_KEY, false)
            return
        }
        val scan = pipeline.mesh.value ?: return
        // Unlit + translucent: the reconstruction must never be mistaken for installed hardware
        // and must not hide the routed services behind it.
        meshRenderer.upload(SCAN_MESH_KEY, scan.mesh, SceneViewMaterialProvider.SCAN_OVERLAY_ARGB, unlit = true)
        meshRenderer.setVisible(SCAN_MESH_KEY, true)
    }

    fun setLivePoint(hit: com.constructar.arengine.hit.SurfaceHit?) {
        routeSession.setLivePoint(hit)
    }

    fun setSpec(spec: ConduitSpec) = routeSession.setSpec(spec)

    fun undo() {
        routeSession.undo()
        interaction?.previousNode = routeSession.state.value.nodes.lastOrNull()
    }

    fun finish(label: String): ConduitRoute? {
        val route = routeSession.finish(label) ?: return null
        // Keep the anchors alive: the committed run keeps following ARCore's refinement.
        routeSession.startNewRun()
        interaction?.previousNode = null
        return route
    }

    /** Uploads/refreshes every committed run of the project. */
    fun syncCommittedRoutes(routes: List<ConduitRoute>) {
        sceneController?.syncCommittedRoutes(routes)
    }

    fun toggleTorch(): Boolean {
        val controller = torchController ?: return false
        val result = controller.toggle()
        torchOn.value = controller.isOn
        return result
    }

    fun resetScan() = pipeline.reset()

    fun release() {
        routeSession.release()
        (anchors.delegate as? ArCoreAnchorFactory)?.detachAll()
        monocularDepth?.close()
        monocularDepth = null
        pipeline.stop()
        // The ARCore session belongs to SceneView: it is paused and closed by ARSceneView's own
        // disposal. Closing it here would pull the camera out from under the renderer.
        renderer?.release()
        renderer = null
        sceneController = null
    }

    /** Scan statistics for the protocol header and the BIM export. */
    fun scanSummary(): ScanSummary {
        val state = pipeline.state.value
        val bounds = pipeline.mesh.value?.bounds
        return ScanSummary.fromBounds(bounds, tsdfConfig.voxelSizeMeters).copy(
            meshVertexCount = state.vertexCount,
            meshTriangleCount = state.triangleCount,
            depthCoverage = tracking.status.value.scanCoverage,
        )
    }

    /**
     * Projects the last committed node into screen space for the floating length label.
     *
     * Done with plain matrix maths rather than `Frame.transformCoordinates3d` so the label and
     * the hit cascade agree exactly: both use the same `P · V` the tap path inverts.
     */
    private fun updateHudPosition(frame: Frame, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        // Throttled to 20 Hz and 8 px: a HUD label that jitters with every frame is harder to
        // read than one that moves in small steps, and it would recompose the screen at 60 Hz.
        val now = SystemClock.elapsedRealtime()
        if (now - lastHudUpdateMillis < HUD_INTERVAL_MS) return
        lastHudUpdateMillis = now
        val target = routeSession.state.value.nodes.lastOrNull()?.position
        if (target == null) {
            hudScreenPixels.value = null
            return
        }
        runCatching {
            val camera = frame.camera
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.05f, 100f)
            // clip = P · (V · p)
            val vx = viewMatrix[0] * target.x + viewMatrix[4] * target.y + viewMatrix[8] * target.z + viewMatrix[12]
            val vy = viewMatrix[1] * target.x + viewMatrix[5] * target.y + viewMatrix[9] * target.z + viewMatrix[13]
            val vz = viewMatrix[2] * target.x + viewMatrix[6] * target.y + viewMatrix[10] * target.z + viewMatrix[14]
            val vw = viewMatrix[3] * target.x + viewMatrix[7] * target.y + viewMatrix[11] * target.z + viewMatrix[15]
            val cx = projectionMatrix[0] * vx + projectionMatrix[4] * vy + projectionMatrix[8] * vz + projectionMatrix[12] * vw
            val cy = projectionMatrix[1] * vx + projectionMatrix[5] * vy + projectionMatrix[9] * vz + projectionMatrix[13] * vw
            val cw = projectionMatrix[3] * vx + projectionMatrix[7] * vy + projectionMatrix[11] * vz + projectionMatrix[15] * vw
            if (cw <= 1e-4f) {
                hudScreenPixels.value = null // behind the camera
                return@runCatching
            }
            val ndcX = cx / cw
            val ndcY = cy / cw
            hudScreenPixels.value = ((ndcX * 0.5f + 0.5f) * viewWidth) to ((0.5f - ndcY * 0.5f) * viewHeight)
        }.onFailure { hudScreenPixels.value = null }
    }

    private var lastHudUpdateMillis = 0L
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    private fun batteryPercent(): Int = runCatching {
        val manager = context.getSystemService(android.os.BatteryManager::class.java)
        manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(100)

    companion object {
        private const val TAG = "ArMeasureController"

        /** Renderer key of the room-scan overlay (separate from the runs). */
        const val SCAN_MESH_KEY = "scan:room"

        /** HUD label refresh period (render thread → Compose state). */
        private const val HUD_INTERVAL_MS = 50L
    }
}

/**
 * ARCore reports brightness as an **average pixel intensity in [0,1]**, not in lux
 * (`LightEstimate` has no `ambientIntensity`; its `State` carries a validity flag only).
 *
 * [TorchController] and [TrackingMonitor] reason in lux - torch on below 40 lux, off above
 * 120 lux, low-light warning below 15 lux - so the intensity is mapped onto that scale with a
 * quadratic curve normalised at ~1000 lux for a fully exposed frame:
 *
 * | pixel intensity | lux  | meaning                     |
 * | --- | --- | --- |
 * | 0.05 | ~3   | night site, torch essential |
 * | 0.12 | ~15  | low-light warning threshold |
 * | 0.20 | ~40  | torch turns on              |
 * | 0.35 | ~120 | torch turns off             |
 *
 * The curve is an estimate, and the app says so: the HUD shows a coaching code, never a lux
 * number pretending to be a measurement.
 */
private fun ambientLuxOf(estimate: LightEstimate, scratch: FloatArray): Float = runCatching {
    if (estimate.state != LightEstimate.State.VALID) return@runCatching 0f
    estimate.getColorCorrection(scratch, 0)
    val intensity = scratch[3].coerceIn(0f, 1f)
    intensity * intensity * LUX_AT_FULL_INTENSITY
}.getOrDefault(0f)

/** Lux corresponding to a fully bright ARCore light estimate (see [ambientLuxOf]). */
private const val LUX_AT_FULL_INTENSITY = 1000f
