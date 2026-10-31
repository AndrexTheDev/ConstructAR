package com.constructar.app.ui.ar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constructar.app.ui.ActionTone
import com.constructar.app.ui.GloveButton
import com.constructar.app.ui.GloveButtonRow
import com.constructar.app.ui.theme.SiteColors
import com.constructar.arengine.render.ArScene
import com.constructar.arengine.tracking.TrackingQuality
import com.constructar.core.model.ConduitPresets
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.ProjectRecord
import com.constructar.core.units.LengthFormatter
import com.google.ar.core.Config

/**
 * The AR measuring screen.
 *
 * Layout mirrors how the tool is actually held: the camera fills the screen, the *length* and
 * the *status* sit in a bar at the top (readable without moving the phone), and the action bar
 * with three glove-sized buttons is pinned to the bottom edge. Everything else (spec chips,
 * diagnostics) is deliberately secondary.
 */
@Composable
fun ArMeasureScreen(
    project: ProjectRecord,
    onBack: () -> Unit,
    onRouteFinished: (com.constructar.core.model.ConduitRoute) -> Unit,
    onScanSummary: (com.constructar.core.model.ScanSummary) -> Unit,
    onOpenExport: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val controller = remember { ArMeasureController(context) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    val routeState by controller.routeSession.state.collectAsStateWithLifecycle()
    val tracking by controller.tracking.status.collectAsStateWithLifecycle()
    val scanState by controller.pipeline.state.collectAsStateWithLifecycle()
    val torchOn by controller.torchOn.collectAsStateWithLifecycle()
    val hudPosition by controller.hudScreenPixels.collectAsStateWithLifecycle()
    val lastHit by controller.lastHit.collectAsStateWithLifecycle()

    var spec by remember { mutableStateOf(ConduitPresets.all.first()) }
    var showScanner by remember { mutableStateOf(true) }
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // Tap results are produced on the render thread; they are applied here, on the main
    // thread, where anchor creation belongs.
    LaunchedEffect(Unit) {
        controller.pipeline.start(scope)
        controller.hitEvents.collect { event ->
            if (event != null) controller.applyPendingHit()
        }
    }
    // Re-upload the room scan whenever the (300 ms-throttled) mesh changes.
    LaunchedEffect(scanState.meshVersion, showScanner) {
        controller.syncScanMesh(showScanner)
    }
    // Committed runs are re-meshed when a new one is added (and at most once per second).
    LaunchedEffect(project.routes.size) {
        controller.syncCommittedRoutes(project.routes)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        if (hasCameraPermission) {
            ArScene(
                modifier = Modifier.fillMaxSize(),
                depthEnabled = true,
                flashMode = if (torchOn) Config.FlashMode.TORCH else Config.FlashMode.OFF,
                onSessionCreated = { controller.onSessionCreated(it) },
                onFrame = { session, frame, width, height ->
                    controller.onFrame(session, frame, width, height)
                },
                onSceneReady = { scene, engine, materialLoader ->
                    controller.onSceneReady(scene, engine, materialLoader)
                    controller.syncScanMesh(showScanner)
                },
                onSceneReleased = { controller.onSceneReleased() },
            )
        }

        // Full-surface tap target. Sits above the AR view so SceneView's own gesture handling
        // cannot swallow a tap, and below the controls.
        if (hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            controller.requestTap(offset.x, offset.y, size.width, size.height)
                        }
                    },
            )
        }

        // ── Floating length label anchored to the last measured point ───────────────────
        hudPosition?.let { (x, y) ->
            if (routeState.nodeCount > 0 && viewSize.width > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.offset {
                        androidx.compose.ui.unit.IntOffset(
                            (x - with(density) { 90.dp.toPx() }).toInt().coerceIn(0, (viewSize.width - with(density) { 180.dp.toPx() }.toInt()).coerceAtLeast(0)),
                            (y + with(density) { 24.dp.toPx() }).toInt().coerceIn(0, (viewSize.height - 40).coerceAtLeast(0)),
                        )
                    },
                ) {
                    Text(
                        text = routeState.lengthLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = SiteColors.cable,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // ── Top status bar ───────────────────────────────────────────────────────────────
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = routeState.lengthLabel,
                            style = MaterialTheme.typography.displaySmall,
                            color = SiteColors.cable,
                        )
                        Text(
                            text = buildString {
                                append(spec.label)
                                append(" · ")
                                append(routeState.nodeCount)
                                append(if (routeState.nodeCount == 1) " point" else " points")
                                if (routeState.closedLoop) append(" · closed")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                    IconButton(onClick = onOpenExport, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "Export", tint = Color.White)
                    }
                    IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                val statusLine = tracking.guidance.ifBlank {
                    if (scanState.ready) {
                        "Scan ${(scanState.coverage * 100).toInt()} % · ${scanState.triangleCount / 1000} k tris"
                    } else {
                        "Mesh scanning unavailable — measuring still works"
                    }
                }
                if (statusLine.isNotBlank()) {
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            routeState.warning != null -> SiteColors.warning
                            tracking.quality == TrackingQuality.NOT_TRACKING -> SiteColors.danger
                            else -> Color.White.copy(alpha = 0.75f)
                        },
                    )
                }
                routeState.warning?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = SiteColors.warning)
                }
                lastHit?.let { resolution ->
                    if (!resolution.accepted) {
                        Text(
                            text = "No surface found — aim at a wall or floor.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SiteColors.danger,
                        )
                    }
                }
            }
        }

        // ── Bottom controls ──────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConduitPresets.all.forEach { preset ->
                    SpecChip(
                        spec = preset,
                        selected = preset == spec,
                        onClick = {
                            spec = preset
                            controller.setSpec(preset)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            GloveButtonRow {
                GloveButton(
                    label = "Undo",
                    icon = Icons.Default.Undo,
                    onClick = { controller.undo() },
                    tone = ActionTone.SECONDARY,
                    enabled = routeState.nodeCount > 0,
                    modifier = Modifier.weight(1f),
                )
                GloveButton(
                    label = "Torch",
                    icon = Icons.Default.FlashlightOn,
                    onClick = { controller.toggleTorch() },
                    tone = ActionTone.SECONDARY,
                    selected = torchOn,
                    modifier = Modifier.weight(1f),
                )
                GloveButton(
                    label = if (showScanner) "Mask" else "Scan",
                    icon = Icons.Default.Add,
                    onClick = { showScanner = !showScanner },
                    tone = ActionTone.SECONDARY,
                    selected = showScanner,
                    modifier = Modifier.weight(1f),
                )
            }

            GloveButtonRow {
                GloveButton(
                    label = "Close loop",
                    icon = Icons.Default.Loop,
                    onClick = { controller.routeSession.closeLoop() },
                    tone = ActionTone.SECONDARY,
                    enabled = routeState.nodeCount >= 3,
                    modifier = Modifier.weight(1f),
                )
                GloveButton(
                    label = "Finish",
                    icon = Icons.Default.Save,
                    onClick = {
                        controller.finish(label = "${spec.label} run ${project.routes.size + 1}")?.let { route ->
                            onRouteFinished(route)
                            // Snapshot the scan statistics with the first finished run: the
                            // protocol header must describe the room as it was measured.
                            onScanSummary(controller.scanSummary())
                        }
                    },
                    tone = ActionTone.PRIMARY,
                    enabled = routeState.canFinish,
                    modifier = Modifier.weight(2f),
                )
            }

            GloveButton(
                label = "Add point at centre",
                icon = Icons.Default.Add,
                onClick = {
                    // Convenience for gloved hands: a big button that adds the reticle point
                    // without requiring a precise tap in the middle of the screen.
                    controller.requestTap(
                        viewSize.width / 2f,
                        viewSize.height / 2f,
                        viewSize.width,
                        viewSize.height,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SpecChip(
    spec: ConduitSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.padding(vertical = 2.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(spec.colorArgb).copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White,
        ),
    ) {
        Text(
            text = spec.sizeLabel,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
