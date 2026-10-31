/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.google.android.filament.Engine
import com.google.android.filament.Scene
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberScene

/**
 * The AR surface: SceneView 4.63.0's typed `ARSceneView` wired to ConstructAR's renderer, TSDF
 * pipeline, hit-testing and tracking monitor.
 *
 * ## Why everything goes through this one composable
 *
 * ARCore's lifecycle (session creation, resume/pause, `Frame` delivery) is inseparable from the
 * rendering surface. Keeping it in one place means the rest of the app deals with plain state
 * objects, and there is exactly one spot to audit for "is this on the render thread?".
 *
 * ## Typed SceneView contract (no reflection)
 *
 * Every object below comes from a typed SceneView Compose factory in v4.63.0:
 *
 * | object | factory |
 * | --- | --- |
 * | `Engine` | `rememberEngine()` |
 * | `ModelLoader` | `rememberModelLoader(engine)` |
 * | `MaterialLoader` | `rememberMaterialLoader(engine)` |
 * | `EnvironmentLoader` | `rememberEnvironmentLoader(engine)` |
 * | `Scene` | `rememberScene(engine)` |
 *
 * The [Scene] is created here and handed to [onSceneReady], so ConstructAR owns its own
 * Filament entities while SceneView owns their destruction — no `SceneScope` internals, no
 * reflective access to a private scene field, and no dependency on an implicit scope.
 *
 * ## Threading
 *
 * [onFrame] runs on SceneView's render thread. It must only *copy* data (depth + pose) and hand
 * it to a worker; anything heavier belongs to
 * [com.constructar.arengine.scan.TsdfScanPipeline]. Compose state is never written from there:
 * the callbacks forward to thread-safe `StateFlow`s owned by the caller.
 */
@Composable
fun ArScene(
    modifier: Modifier = Modifier,
    depthEnabled: Boolean = true,
    flashMode: Config.FlashMode = Config.FlashMode.OFF,
    planeFindingMode: Config.PlaneFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
    /**
     * Applied by SceneView *before* `Session.configure()`, which is the only safe place to
     * change ARCore settings (configuring a resumed session throws). ConstructAR's whole
     * offline policy — raw depth, ambient light estimation, no cloud anchors — lives here.
     */
    sessionConfiguration: ((Session, Config) -> Unit)? = null,
    onSessionCreated: (Session) -> Unit = {},
    onFrame: (Session, Frame, Int, Int) -> Unit = { _, _, _, _ -> },
    onTrackingFailureChanged: (TrackingFailureReason?) -> Unit = {},
    onSessionFailed: (Exception) -> Unit = {},
    onSceneReady: (Scene, Engine, MaterialLoader) -> Unit = { _, _, _ -> },
    onSceneReleased: () -> Unit = {},
    overlay: @Composable () -> Unit = {},
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val scene = rememberScene(engine)

    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentOnSessionCreated by rememberUpdatedState(onSessionCreated)
    val currentOnTrackingFailure by rememberUpdatedState(onTrackingFailureChanged)
    val currentOnSessionFailed by rememberUpdatedState(onSessionFailed)
    // The scene callbacks outlive a recomposition (the effect is keyed on the scene), so they
    // are read through `rememberUpdatedState` too — otherwise the first lambdas captured here
    // would keep driving the renderer even after the caller supplied new ones.
    val currentOnSceneReady by rememberUpdatedState(onSceneReady)
    val currentOnSceneReleased by rememberUpdatedState(onSceneReleased)

    // The viewport in *pixels* is what the hit cascade needs to unproject a tap; it is read
    // from layout rather than from the display metrics so it stays correct in split screen.
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    // Entities are added once the Filament scene exists and released with it. This runs after
    // composition, never inside a frame callback.
    DisposableEffect(scene, engine, materialLoader) {
        currentOnSceneReady(scene, engine, materialLoader)
        onDispose { currentOnSceneReleased() }
    }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            scene = scene,
            // ARCore planes are reconstructed by our own TSDF; drawing both would double the
            // surface and make the scan unreadable.
            planeRenderer = false,
            planeFindingMode = planeFindingMode,
            depthMode = if (depthEnabled) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED,
            // The torch is a session *config* field: SceneView re-applies it on every session
            // (re)creation and on a live flashMode change, which is exactly what we need after a
            // pause/resume.
            flashMode = flashMode,
            // 100 % offline: no cloud anchors, no instant placement, no geospatial/streetscape.
            cloudAnchorMode = Config.CloudAnchorMode.DISABLED,
            instantPlacementMode = Config.InstantPlacementMode.DISABLED,
            geospatialMode = Config.GeospatialMode.DISABLED,
            streetscapeGeometryMode = Config.StreetscapeGeometryMode.DISABLED,
            augmentedFaceMode = Config.AugmentedFaceMode.DISABLED,
            sessionConfiguration = sessionConfiguration,
            onSessionCreated = { session -> currentOnSessionCreated(session) },
            onSessionUpdated = { session, frame ->
                // Render thread. Nothing here may touch Compose state, mutate the route
                // session or run ARCore session calls — it only copies and forwards.
                currentOnSessionCreated(session)
                currentOnFrame(session, frame, viewport.width, viewport.height)
            },
            onTrackingFailureChanged = { reason -> currentOnTrackingFailure(reason) },
            onSessionFailed = { error -> currentOnSessionFailed(error) },
        )

        // Overlays (HUD, coaching, controls) belong to the caller: they are Compose state and
        // must not be evaluated from the render thread's callback.
        overlay()
    }
}
