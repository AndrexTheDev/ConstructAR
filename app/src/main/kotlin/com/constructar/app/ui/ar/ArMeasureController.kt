/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.constructar.app.diagnostics.Diagnostics
import com.constructar.arengine.hit.ArInteraction
import com.constructar.arengine.hit.HitResolution
import com.constructar.arengine.hit.HitStabilizer
import com.constructar.arengine.keyframe.ClosureState
import com.constructar.arengine.keyframe.PoseGraphStatus
import com.constructar.arengine.render.RouteSceneController
import com.constructar.arengine.render.SceneMeshRenderer
import com.constructar.arengine.render.SceneViewMaterialProvider
import com.constructar.arengine.route.AnchorFactory
import com.constructar.arengine.route.AnchorHandle
import com.constructar.arengine.route.ArCoreAnchorFactory
import com.constructar.arengine.route.RouteSession
import com.constructar.arengine.scan.CameraPlanes
import com.constructar.arengine.scan.CameraIntrinsics
import com.constructar.arengine.scan.MarkerProbe
import com.constructar.arengine.scan.TsdfScanPipeline
import com.constructar.arengine.session.ArReadiness
import com.constructar.arengine.session.ArSessionController
import com.constructar.arengine.session.TorchController
import com.constructar.arengine.tracking.TrackingMonitor
import com.constructar.arengine.tracking.TrackingQuality
import com.constructar.core.calib.MarkerScaleProposal
import com.constructar.core.calib.MarkerSweepState
import com.constructar.core.math.Pose
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.ScanClosure
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
import kotlinx.coroutines.flow.asStateFlow

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

    /**
     * The marker target probe: the operator aims at a printed tag and walks a metre, and the sweep
     * it feeds answers whether this phone's metre agrees with the printed size
     * (`docs/CALIBRATION.md`, tier 2).
     *
     * Until finding 235 the whole path existed and nothing drove it - a reader with no caller, a
     * sweep nobody held. This is the caller, and the state it publishes is what a status line
     * shows. It proposes a factor and never applies one: the write stays with the project, on the
     * reporting layer, like the physical-reference sweep beside it.
     */
    private val markerState = MutableStateFlow<MarkerSweepState>(MarkerSweepState.Off)

    /** `Off`, `Watching` or `Ready` - the three things a marker status line can say. */
    val markerProbeState: StateFlow<MarkerSweepState> = markerState.asStateFlow()

    /**
     * What the pose graph has taken and what it lost (finding 244).
     *
     * Passed through rather than recomputed: the counters belong to the pipeline's worker, and the
     * screen only has to say them.
     */
    val poseGraphStatus: StateFlow<PoseGraphStatus> get() = pipeline.poseGraphStatus

    /** The loop-closure conversation, as the worker publishes it (finding 245). */
    val closureState: StateFlow<ClosureState> get() = pipeline.closureState

    /** Ask whether the walk has come back somewhere it has been. */
    fun lookForClosure() {
        // The four closure actions are the ones a test report is about: they are the only place
        // where a human changes the graph, so each one is written down (finding 256).
        Diagnostics.event("closure", "look requested")
        pipeline.lookForClosure()
    }

    /** The operator said "same place": close the loop and optimise. */
    fun confirmClosure() {
        Diagnostics.event("closure", "confirmed by the operator")
        pipeline.confirmClosure()
    }

    /** The operator said no. */
    fun dismissClosure() {
        Diagnostics.event("closure", "refused by the operator")
        pipeline.dismissClosure()
    }

    @Volatile
    private var markerProbe: MarkerProbe? = null

    /**
     * Frames between marker reads.
     *
     * The depth path integrates every frame; a marker does not need that. A tag read costs a full
     * luminance pack plus a decode, and a sweep is answered by a walk, not by 30 pictures a second -
     * so one frame in fifteen (about 2 Hz) is enough and keeps the render thread's budget.
     */
    private val markerFrameInterval = 15

    @Volatile
    private var markerFrames = 0

    /**
     * One stamp per ARCore session this screen has seen.
     *
     * A new ARCore session is a new world origin, so a baseline across two of them is a subtraction
     * of two different rooms (finding 217). The sweep refuses sightings that carry a different
     * stamp; this counter is what gives each session its own.
     */
    @Volatile
    private var sessionEpoch = 0L

    /**
     * Runs finished in *this* AR session - the only ones whose world coordinates mean anything
     * here. See [SessionRuns] and finding 129.
     */
    private val sessionRouteIds = LinkedHashSet<String>()
    private val sessionController = ArSessionController(context)
    private val anchors = DelegatingAnchorFactory()

    /**
     * The id of *this* AR session - the coordinate system its nodes live in.
     *
     * ARCore starts a new world origin every time a session begins, and the app keeps the floor it
     * measured against that origin. A node is only comparable to a floor from the same id; a fresh
     * uuid per controller is what makes that checkable instead of assumed (finding 217).
     */
    val sessionId: String = java.util.UUID.randomUUID().toString()
    val routeSession = RouteSession(anchors, sessionId = sessionId)

    private var interaction: ArInteraction? = null
    private var renderer: SceneMeshRenderer? = null
    private var sceneController: RouteSceneController? = null
    private var torchController: TorchController? = null
    private var monocularDepth: com.constructar.arengine.ai.MonocularDepthSource? = null

    /**
     * The monocular model's asset path, from the build variant rather than from a second literal.
     *
     * `MonocularDepthEstimator.Config` carries a default for the tests and for a caller that has
     * no BuildConfig; the app must not rely on that default, or a build that ships the model under
     * another name (a smaller int8 export, a Pro variant) would look for the old file, find
     * nothing, and switch itself off without a compile error anywhere (finding 221).
     */
    private val depthConfig = com.constructar.arengine.ai.MonocularDepthEstimator.Config(
        assetPath = com.constructar.app.BuildConfig.DEPTH_MODEL_ASSET,
    )

    /** True when this APK carries the model file - i.e. the fallback could run at all. */
    val depthModelBundled: Boolean by lazy {
        com.constructar.arengine.ai.MonocularDepthEstimator.isModelPresent(context, depthConfig)
    }

    /**
     * True once the model has loaded and produced a frame; flipped on the loader's thread.
     *
     * A flow rather than a plain boolean because the screen *is* the reader: a value that changes
     * on a worker thread and is read once during composition would never show up.
     */
    private val aiDepthRunningState = MutableStateFlow(false)
    val aiDepthRunning: StateFlow<Boolean> = aiDepthRunningState.asStateFlow()

    /**
     * True once the model's load attempt has answered - either way.
     *
     * A build without the model answers immediately; one with it answers after a file read and an
     * interpreter build, which is a second of a cold start. Until then the HUD says nothing about
     * depth, because "no depth" is not yet a fact (finding 224).
     */
    private val aiDepthSettledState = MutableStateFlow(false)
    val aiDepthSettled: StateFlow<Boolean> = aiDepthSettledState.asStateFlow()
    private var powerManager: PowerManager? = null

    /**
     * Scratch for ARCore's colour correction, whose 4th component is the average pixel
     * intensity. Reused because this runs once per frame on the render thread.
     */
    private val colorCorrection = FloatArray(4)

    val torchOn = MutableStateFlow(false)

    /** True when the camera refused the torch — the button is hidden rather than left dead. */
    val torchUnavailable = MutableStateFlow(false)

    /**
     * Screen position (pixels) of the floating length label, or null when there is nothing to
     * label. Computed on the render thread from the camera matrices and published for the HUD.
     */
    val hudScreenPixels = MutableStateFlow<Pair<Float, Float>?>(null)
    val lastHit: MutableStateFlow<HitResolution?> = MutableStateFlow(null)

    /**
     * What the session has decided about the floor on the latest resolution.
     *
     * A tap into a doorway is not evidence that the floor went away, so the last *decision* is
     * kept; the status travels with the height because an uncertain floor may not be used to
     * state a mounting height (findings 214 and 218).
     */
    val floor: MutableStateFlow<com.constructar.arengine.hit.FloorDecision?> = MutableStateFlow(null)

    /**
     * State of the reticle's sample window: whether the point under the crosshair has settled
     * to a centimetre yet. The HUD says "hold steady" until it has, and the committed node is
     * the window's median rather than one noisy frame.
     */
    val stability: MutableStateFlow<HitStabilizer.Reading?> = MutableStateFlow(null)
    private val hitStabilizer = HitStabilizer()

    /** Keeps the reticle at the centre of a view that may have been rotated or resized. */
    private val reticleCentre = ReticleCentre()

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

    /**
     * ARCore readiness check, surfaced as a localised blocking message when it is not [ArReadiness.Ready].
     *
     * `requestInstallFromPlay = false` for the re-check the screen runs when it resumes: that one
     * must not put the Play install dialog in front of the operator again (finding 146).
     */
    suspend fun checkReadiness(
        activity: android.app.Activity?,
        hasCameraPermission: Boolean,
        requestInstallFromPlay: Boolean = true,
    ): ArReadiness =
        sessionController.checkReadiness(activity, hasCameraPermission, requestInstallFromPlay)

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
        // New origin: any sweep still open belongs to the world that just ended, and continuing it
        // would pair a camera position from one room with a tag vector from another.
        sessionEpoch += 1
        markerProbe = null
        markerFrames = 0
        markerState.value = MarkerSweepState.Off
        anchors.delegate = ArCoreAnchorFactory(session)
        interaction = ArInteraction(session).also {
            it.previousNode = routeSession.state.value.nodes.lastOrNull()
            it.onTapResolved = { resolution -> mutableHitEvents.value = resolution }
            // The floor travels on its own path, because the reticle sees it without a tap and the
            // HUD has to warn about an uncertain one *before* the operator commits a point to it
            // (findings 218 and 226).
            it.onFloorResolved = { decision ->
                // A wall, a chair or the ceiling is no evidence that the floor went away. Keeping
                // the last real decision is finding 214's rule ("the last height a tracked plane
                // produced"), now that the *status* travels with it - and it is what stops the
                // HUD's warning from flickering on every frame the reticle leaves the floor.
                if (decision.status != com.constructar.arengine.hit.FloorReferenceStatus.MISSING) {
                    floor.value = decision
                }
            }
        }
        if (powerManager == null) {
            powerManager = context.getSystemService(PowerManager::class.java)
        }
        if (monocularDepth == null) {
            // Optional: silently inactive when no model is bundled or calibration is refused.
            monocularDepth = com.constructar.arengine.ai.MonocularDepthSource(
                context = context,
                config = depthConfig,
                onFused = { depth, width, height, _ -> pipeline.submitFusedDepth(depth, width, height) },
                onModelState = { loaded ->
                    aiDepthRunningState.value = loaded
                    aiDepthSettledState.value = true
                },
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
     * Opens a marker sweep for the session that is running now.
     *
     * `false` means the device cannot measure markers at all - the native library is missing or its
     * `TagScaleOptions` are not usable - and a screen must say so rather than show a sweep that
     * will never fill.
     */
    fun startMarkerProbe(): Boolean {
        val opened = pipeline.openMarkerProbe(sessionEpoch) ?: return false
        markerProbe = opened
        markerFrames = 0
        markerState.value = opened.state
        return true
    }

    /** Ends the sweep. A factor already read stays with the caller; this forgets the evidence. */
    fun stopMarkerProbe() {
        markerProbe?.reset()
        markerProbe = null
        markerState.value = MarkerSweepState.Off
    }

    /**
     * Whether this device can measure markers at all.
     *
     * Asked once by the screen so that a button which cannot work is greyed out instead of opening
     * a dialog that will never fill - the same rule the torch button follows.
     */
    fun isMarkerProbeAvailable(): Boolean = pipeline.markerKnobs() != null

    /** The factor the sweep has published, or `null` while it is still watching. */
    fun markerProposal(): MarkerScaleProposal? =
        (markerState.value as? MarkerSweepState.Ready)?.proposal

    /**
     * One marker frame, every [markerFrameInterval]th.
     *
     * The image is closed on every path, including the ones that return early: `acquireCameraImage`
     * hands out a buffer from a buffer queue of depth 2, so a leaked image stalls the camera within
     * a second (`YuvFrame.capture` closes its own for the same reason).
     */
    private fun probeMarker(frame: Frame) {
        val probe = markerProbe ?: return
        if (markerFrames++ % markerFrameInterval != 0) return
        // "Not yet available" is normal in the first frames of a session, and the documented
        // recovery is to ask again on the next one.
        val image = try {
            frame.acquireCameraImage()
        } catch (error: Throwable) {
            return
        }
        try {
            val plane = CameraPlanes.luminance(image) ?: return
            val intrinsics = imageIntrinsics(frame, plane.plane.width) ?: return
            markerState.value = probe.onFrame(plane.source, plane.plane, intrinsics, cameraPose(frame))
        } finally {
            image.close()
        }
    }

    /**
     * The CPU image's intrinsics, scaled to the plane that was actually handed over.
     *
     * ARCore reports the intrinsics for its own image size; a device that crops or rescales the
     * image would otherwise get a focal length in the wrong pixels, which is a distance that is
     * wrong by exactly that ratio.
     */
    private fun imageIntrinsics(frame: Frame, width: Int): CameraIntrinsics? {
        val base = try {
            frame.camera.imageIntrinsics
        } catch (error: Throwable) {
            return null
        }
        val dimensions = base.imageDimensions
        if (dimensions.size < 2 || dimensions[0] <= 0) return null
        val scale = width.toFloat() / dimensions[0].toFloat()
        val focal = base.focalLength
        val principal = base.principalPoint
        if (focal.size < 2 || principal.size < 2) return null
        val intrinsics = CameraIntrinsics(
            fx = focal[0] * scale,
            fy = focal[1] * scale,
            cx = principal[0] * scale,
            cy = principal[1] * scale,
        )
        return if (intrinsics.isPlausible) intrinsics else null
    }

    /** ARCore's pose in this module's terms - quaternion order is x, y, z, w there. */
    private fun cameraPose(frame: Frame): Pose {
        val pose = frame.camera.pose
        val quaternion = pose.rotationQuaternion
        val translation = pose.translation
        return Pose(
            rotation = Quat(quaternion[3], quaternion[0], quaternion[1], quaternion[2]),
            translation = Vec3(translation[0], translation[1], translation[2]),
        )
    }

    /**
     * Offers this frame to the keyframe policy; the pipeline queues and its worker writes.
     *
     * The `Frame` never leaves this call: the pose is copied into a value, the feature count into
     * an `Int`, and nothing here touches the native graph - see `KeyframeFeed` and
     * `TsdfScanPipeline.keyframeFeed`.
     */
    private fun feedKeyframe(frame: Frame) {
        val feed = pipeline.keyframeFeed ?: return
        feed.onFrame(
            pose = cameraPose(frame),
            timestampMillis = SystemClock.uptimeMillis(),
            trackingValid = frame.camera.trackingState == TrackingState.TRACKING,
            featurePoints = featurePoints(frame),
        )
    }

    /**
     * How many feature points ARCore sees, sampled at 2 Hz.
     *
     * The point cloud is the only ARCore source for this number, and acquiring it is a JNI round
     * trip plus a buffer map on the render thread - so it is sampled on a timer rather than per
     * frame. The count is evidence of the *scene*, which changes at the speed a person walks, not
     * at 30 Hz; a stale count can only make the policy more conservative, because a scene that had
     * features a second ago either still has them or has lost them (finding 243).
     */
    private fun featurePoints(frame: Frame): Int {
        val now = SystemClock.uptimeMillis()
        if (now - featureSampleAtMillis < FEATURE_SAMPLE_INTERVAL_MS) return featureCount
        featureSampleAtMillis = now
        featureCount = try {
            val cloud = frame.acquirePointCloud()
            try {
                cloud.points.remaining() / FLOATS_PER_POINT
            } finally {
                cloud.release()
            }
        } catch (expected: IllegalStateException) {
            // Point clouds are off in this session, or this frame is not tracking: no evidence,
            // and the policy reads 0 as "too few features" instead of guessing a number.
            Log.w(TAG, "point cloud unavailable - no keyframe evidence from this frame", expected)
            0
        }
        return featureCount
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

        val camera = frame.camera
        // ARCore's TrackingState has exactly three values. PAUSED covers both the warm-up
        // before the first tracked frame and a backgrounded session, so it maps to LIMITED
        // ("hold still") rather than to a loss the operator has to react to.
        val quality = when (camera.trackingState) {
            TrackingState.TRACKING -> TrackingQuality.GOOD
            TrackingState.PAUSED -> TrackingQuality.LIMITED
            TrackingState.STOPPED -> TrackingQuality.NOT_TRACKING
        }
        // Nothing is placed with a frame that has no pose of its own (finding 163). This used to
        // run for every frame: the depth was integrated at the last good camera position while
        // the operator kept walking - a smear of wherever the phone was pointing when the tracker
        // gave up - and a tap was answered against that same frozen pose, putting a node on a
        // wall the phone was no longer looking at. `requiresRelocalisation()` was written to
        // express this and was read by nobody; the condition is simply "this frame has a pose".
        val poseUsable = tracking.canPlaceFrame(quality)
        if (poseUsable) {
            pipeline.onFrame(frame, thermalStatus)
            probeMarker(frame)
            // The pose graph is fed from the same placed frames, and only from them: a keyframe
            // taken from a frame the tracker cannot place is a node at a position nobody measured
            // (finding 243).
            feedKeyframe(frame)
        } else {
            // A tap made now must not wait for the next live frame to be answered: the pixel it
            // names is meaningless by then, and `ArInteraction` would resolve it against whatever
            // the camera has found in the meantime.
            interaction?.clearTap()
            hitStabilizer.reset()
            stability.value = null
        }

        val activeInteraction = interaction
        if (activeInteraction != null) {
            activeInteraction.latestSnapshot = pipeline.latestSnapshot
            // Aim the reticle at the screen centre: the operator holds the phone in front of
            // the wall, so the centre pixel is what they are pointing at. Recomputed whenever the
            // view changes size - `configChanges` keeps this screen alive across a rotation, so a
            // centre computed once left the reticle sampling a pixel that had moved off the screen
            // (finding 117).
            reticleCentre.update(viewWidth, viewHeight)?.let { centre ->
                activeInteraction.reticlePoint = centre
            }
            if (poseUsable) {
                activeInteraction.onFrameEnter(frame, viewWidth, viewHeight, SystemClock.uptimeMillis())
            }
            // Live preview follows the reticle (throttled inside ArInteraction to 12 Hz).
            val reticleHit = activeInteraction.reticle.value
            routeSession.setLivePoint(reticleHit)
            if (poseUsable) {
                stability.value = hitStabilizer.sample(reticleHit, SystemClock.uptimeMillis())
            }
        }

        routeSession.onFrame()
        updateHudPosition(frame, viewWidth, viewHeight)

        // Monocular depth: throttled internally to ~2 Hz; copies what it needs immediately. Also
        // only for a frame that can be placed - it exists to densify depth *for integration*, and
        // a frame that will not be integrated has nothing to densify (finding 163).
        if (poseUsable) {
            pipeline.latestSnapshot?.let { snapshot -> monocularDepth?.onFrame(frame, snapshot) }
        }

        val stats = pipeline.depthStats
        val ambientLux = ambientLuxOf(frame.lightEstimate, colorCorrection)
        // The automatic torch path can switch the light on and can be refused by the camera, so
        // the UI has to hear about both - and the tracking status should see the torch as it is
        // now, not as it was on the previous frame (finding 106).
        torchController?.let { torch ->
            torch.onAmbientIntensity(ambientLux, batteryPercent())
            torchOn.value = torch.isOn
            torchUnavailable.value = torch.unavailable
        }
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
        )
    }

    /** Called from the UI thread when the operator taps the AR view. */
    fun requestTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val current = interaction
        if (current == null) {
            // Before the scene is ready - or after it was released - a tap used to vanish without
            // a word, which from the outside is exactly "the button does nothing" (finding 257).
            Diagnostics.event(
                "tap",
                "ignored: no scene (${x.toInt()},${y.toInt()} of ${viewWidth}x$viewHeight)",
            )
            return
        }
        Diagnostics.event("tap", "requested ${x.toInt()},${y.toInt()} of ${viewWidth}x$viewHeight")
        current.requestTap(x, y, viewWidth, viewHeight)
    }

    /** Applies the tap that was resolved on the render thread (main thread only). */
    fun applyPendingHit(): HitResolution? {
        val resolution = mutableHitEvents.value ?: return null
        mutableHitEvents.value = null
        lastHit.value = resolution
        val hit = resolution.hit
        // The other half of "nothing happens": the tap did arrive, and the hit test found no
        // surface under the crosshair - in a dark room ARCore has no plane to give (finding 257).
        Diagnostics.event(
            "tap",
            if (hit == null) {
                "resolved: no surface"
            } else {
                "resolved: ${hit.surfaceKind} / ${hit.source} at ${hit.point.x},${hit.point.y},${hit.point.z}"
            },
        )
        if (hit == null) {
            // Tap into nothing: the window is stale, and keeping it would let a later tap
            // commit a point the operator has already walked away from.
            hitStabilizer.reset()
            stability.value = null
            return resolution
        }
        if (routeSession.addNode(hitStabilizer.stabilize(hit, SystemClock.uptimeMillis()))) {
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

    /**
     * Draws the run being measured: the nodes placed so far plus the segment to the reticle.
     *
     * `RouteSession` has built this mesh on every node and every reticle move since the routing
     * state was written, and `RouteSceneController.syncLiveRoute` has existed to upload it - but
     * nothing ever called it, so the only thing the operator saw while measuring was the crosshair
     * and the numbers: the conduit appeared in one piece when the run was finished, and the
     * preview mesh was rebuilt up to twelve times a second for nobody (finding 162). The upload is
     * deduped on the mesh identity inside the controller, so a still reticle costs nothing.
     */
    fun syncLiveRoute() {
        val state = routeSession.state.value
        sceneController?.syncLiveRoute(
            nodes = state.nodes,
            spec = state.spec,
            previewGeometry = state.preview?.mesh,
        )
        // The points themselves, as dots on the wall (finding 269). The tube needs two nodes, so
        // until this line existed the first tap of every run - and every tap after a save - drew
        // nothing at all, and the wall the operator had just measured looked untouched.
        sceneController?.syncNodeMarkers(
            points = state.nodes.map { it.position },
            argb = SceneViewMaterialProvider.NODE_MARKER_ARGB,
        )
    }

    fun setSpec(spec: ConduitSpec) = routeSession.setSpec(spec)

    fun undo() {
        routeSession.undo()
        hitStabilizer.reset()
        stability.value = null
        interaction?.previousNode = routeSession.state.value.nodes.lastOrNull()
    }

    fun finish(label: String): ConduitRoute? {
        val route = routeSession.finish(label) ?: return null
        sessionRouteIds += route.id
        // `finish()` has already released this run's anchors: the committed geometry is drawn
        // from the node positions it was measured with, and nothing refines those afterwards
        // (finding 164).
        routeSession.startNewRun()
        hitStabilizer.reset()
        stability.value = null
        interaction?.previousNode = null
        return route
    }

    /**
     * Uploads/refreshes the committed runs that can be placed in this session.
     *
     * A run measured in another session is not drawn: its coordinates belong to a world origin
     * that no longer exists, and a tube in the wrong spot is worse than no tube at all
     * (finding 129).
     */
    fun syncCommittedRoutes(routes: List<ConduitRoute>) {
        sceneController?.syncCommittedRoutes(SessionRuns.placeable(sessionRouteIds, routes))
    }

    fun toggleTorch(): Boolean {
        val controller = torchController ?: return false
        val result = controller.toggle()
        torchOn.value = controller.isOn
        torchUnavailable.value = controller.unavailable
        return result
    }

    fun resetScan() = pipeline.reset()

    fun release() {
        Diagnostics.event("scan", "session released")
        sessionRouteIds.clear()
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

    /**
     * The room's card picture, taken while the volume is still alive.
     *
     * Called when a run is committed, because that is the moment the operator has finished the room
     * and the volume still holds the walls he walked; after `release()` the volume is gone and the
     * picture could only be invented. Delegates to the scan worker (see
     * `TsdfScanPipeline.floorplan`), which is the only thread allowed to touch the volume.
     */
    fun finalFloorplan(): com.constructar.nativebridge.FloorplanPayload? = pipeline.floorplan()

    /** Scan statistics for the protocol header: two numbers, both measured (finding 103). */
    fun scanSummary(): ScanSummary {
        val state = pipeline.state.value
        val graph = pipeline.poseGraphStatus.value
        val correction = graph.lastCorrection
        // A closure the operator confirmed is part of what this scan *is*, not a detail of the
        // session: the protocol has to say the geometry was folded back on someone's word
        // (finding 251). No closure means no record - never a record of zeros (finding 103).
        val closure = if (graph.closures > 0) {
            ScanClosure(
                closures = graph.closures,
                movedNodes = correction?.movedNodes ?: 0,
                nodes = correction?.nodes ?: 0,
                maxShiftMetres = correction?.maxShiftMetres ?: 0f,
                // The document must not read "0 of 0 nodes" for a run whose solver gave up: the
                // absence of a report is not a report of no movement (findings 247, 252).
                converged = graph.lastClosureConverged != false,
            )
        } else {
            null
        }
        return ScanSummary(
            meshTriangleCount = state.triangleCount,
            scanLimited = state.hitBlockBudget,
            closures = closure,
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

    /** Last point count ARCore reported, and when it was asked (see [featurePoints]). */
    private var featureCount = 0
    private var featureSampleAtMillis = 0L

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

        /** How often the point cloud is asked for a feature count - see [featurePoints]. */
        private const val FEATURE_SAMPLE_INTERVAL_MS = 500L

        /** `PointCloud.points` is x, y, z, confidence per point. */
        private const val FLOATS_PER_POINT = 4
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
