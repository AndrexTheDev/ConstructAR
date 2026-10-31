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
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * The AR surface: SceneView's `ARSceneView` wired to ConstructAR's renderer, TSDF pipeline,
 * hit-testing and tracking monitor.
 *
 * ## Why everything goes through this one composable
 *
 * ARCore's lifecycle (session creation, resume/pause, camera permission, `Frame` delivery) is
 * inseparable from the rendering surface. Keeping it in one place means the rest of the app
 * deals with plain state objects, and there is exactly one spot to audit for "is this on the
 * render thread?".
 *
 * ## Threading
 *
 * [onFrame] runs on SceneView's render thread. It must only *copy* data (depth + pose) and
 * hand it to a worker; anything heavier belongs to [com.constructar.arengine.scan.TsdfScanPipeline].
 *
 * ## SceneView integration points
 *
 * Three facts this file relies on (checked against SceneView 4.60):
 *  1. `ARSceneView(engine, modelLoader, materialLoader, environmentLoader, planeFindingMode,
 *     depthMode, flashMode, planeRenderer, onSessionUpdated, content)` — the Compose surface.
 *  2. the content lambda receives a `SceneScope` exposing the Filament [Scene] and [Engine];
 *  3. nodes/entities added directly to that [Scene] are rendered by SceneView's view.
 *
 * If a SceneView upgrade changes (1) or (2), this is the only file to touch.
 */
@Composable
fun ArScene(
    modifier: Modifier = Modifier,
    depthEnabled: Boolean = true,
    flashMode: Config.FlashMode = Config.FlashMode.OFF,
    planeFindingMode: Config.PlaneFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
    onSessionCreated: (Session) -> Unit = {},
    onFrame: (Session, Frame, Int, Int) -> Unit = { _, _, _, _ -> },
    onSceneReady: (Scene, Engine, MaterialLoader) -> Unit = { _, _, _ -> },
    onSceneReleased: () -> Unit = {},
    overlay: @Composable () -> Unit = {},
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentOnSessionCreated by rememberUpdatedState(onSessionCreated)

    // The viewport in *pixels* is what the hit cascade needs to unproject a tap; it is read
    // from layout rather than from the display metrics so it stays correct in split screen.
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            planeRenderer = false,
            planeFindingMode = planeFindingMode,
            depthMode = if (depthEnabled) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED,
            // The torch is a session *config* field: SceneView re-applies it on every session
            // (re)creation, which is exactly what we need after a pause/resume.
            flashMode = flashMode,
            onSessionUpdated = { session, frame ->
                // Render thread. Nothing here may touch Compose state, mutate the route
                // session or run ARCore session calls — it only copies and forwards.
                currentOnSessionCreated(session)
                currentOnFrame(session, frame, viewport.width, viewport.height)
            },
        ) {
            // `scene`/`engine` come from SceneScope (see the integration note above).
            val filamentScene: Scene = scene
            val filamentEngine: Engine = engine

            DisposableEffect(filamentScene) {
                onSceneReady(filamentScene, filamentEngine, materialLoader)
                onDispose { onSceneReleased() }
            }

        }

        // Overlays (HUD, coaching, controls) belong to the caller: they are Compose state and
        // must not be evaluated from the render thread's callback.
        overlay()
    }
}
