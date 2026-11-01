/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constructar.app.R
import com.constructar.app.ui.ActionTone
import com.constructar.app.ui.GloveButton
import com.constructar.app.ui.GloveButtonRow
import com.constructar.app.ui.formatTimeLabel
import com.constructar.app.ui.label
import com.constructar.app.ui.message
import com.constructar.app.ui.routingLabel
import com.constructar.app.ui.theme.SiteColors
import com.constructar.app.ui.tierLabel
import com.constructar.arengine.render.ArScene
import com.constructar.arengine.session.ArReadiness
import com.constructar.arengine.tracking.TrackingQuality
import com.google.ar.core.Config
import com.constructar.core.model.ConduitPresets
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteDraft
import com.constructar.core.model.RouteLabels

/**
 * The AR measuring screen.
 *
 * Layout mirrors how the tool is actually held: the camera fills the screen, the *length* and
 * the *status* sit in a bar at the top (readable without moving the phone), and the action bar
 * with glove-sized buttons is pinned to the bottom edge. Everything else (spec chips, hit
 * diagnostics) is deliberately secondary.
 *
 * ## Language
 *
 * Not one user-visible character is hardcoded here. Lengths come from
 * [com.constructar.core.units.LengthFormatter] or from the `hud_length` format resource, and
 * every state the engines can report arrives as a *code* ([com.constructar.arengine.tracking.Guidance],
 * [com.constructar.arengine.route.RouteWarning], `SurfaceKind`, `HitSource`) that
 * [com.constructar.app.ui.labels] maps to `stringResource`. That is what makes the six shipped
 * languages complete rather than aspirational.
 */
@Composable
fun ArMeasureScreen(
    project: ProjectRecord,
    onBack: () -> Unit,
    onRouteFinished: (com.constructar.core.model.ConduitRoute) -> Unit,
    onScanSummary: (com.constructar.core.model.ScanSummary) -> Unit,
    onOpenExport: () -> Unit,
    /** A run a killed process left behind, loaded by the caller. Offered back once per entry. */
    draft: RouteDraft? = null,
    /** `null` clears the stored draft; anything else is the run as it stands. */
    onDraftChanged: (RouteDraft?) -> Unit = {},
    onDraftRecovered: (RouteDraft) -> Unit = {},
    onDraftDiscarded: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val controller = remember { ArMeasureController(context) }
    val activity = remember(context) { context.findActivity() }

    fun cameraGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    var hasCameraPermission by remember { mutableStateOf(cameraGranted()) }
    // Whether this screen has already asked. Before the first ask the system reports
    // "no rationale" for a first request too, so the flag cannot be inferred.
    var permissionAsked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionAsked = true
        hasCameraPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    // Coming back from the settings app has to be noticed: the launcher callback only fires for
    // a dialog, and there is none once the system has stopped showing it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasCameraPermission = cameraGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionState = cameraPermissionState(
        granted = hasCameraPermission,
        asked = permissionAsked,
        rationaleKnown = activity != null,
        showRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA),
    )
    val openAppSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A device without that activity must not crash on the one screen that is already broken.
        runCatching { context.startActivity(intent) }
    }

    // ARCore readiness (installed / supported / up to date) — checked before the surface is
    // composed so a device without AR gets a sentence instead of a black screen.
    var readiness by remember { mutableStateOf<ArReadiness?>(null) }
    LaunchedEffect(hasCameraPermission) {
        readiness = if (hasCameraPermission) controller.checkReadiness(activity, true) else null
    }
    var sessionError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    val routeState by controller.routeSession.state.collectAsStateWithLifecycle()
    val tracking by controller.tracking.status.collectAsStateWithLifecycle()
    val scanState by controller.pipeline.state.collectAsStateWithLifecycle()
    val torchOn by controller.torchOn.collectAsStateWithLifecycle()
    val hudPosition by controller.hudScreenPixels.collectAsStateWithLifecycle()
    val lastHit by controller.lastHit.collectAsStateWithLifecycle()
    val stability by controller.stability.collectAsStateWithLifecycle()

    var spec by remember { mutableStateOf(ConduitPresets.all.first()) }
    var showScanner by remember { mutableStateOf(true) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    /**
     * A run of two points or more is the operator's work, and leaving the screen used to throw
     * it away without a word: the on-screen close button and the system back gesture both popped
     * the screen, `release()` dropped the anchors, and eight taps along a wall were gone. The
     * system gesture had no handler at all, so the *quietest* way out was the one that lost the
     * most. Two points is the same threshold as `ConduitRoute.isRoutable` - below it there is no
     * run to save, above it there is.
     */
    var confirmLeave by remember { mutableStateOf(false) }

    /**
     * The interrupted run, offered once per entry.
     *
     * Only while nothing has been tapped in this session: a draft that arrives from disk a moment
     * after the operator has started measuring must never replace what is on screen.
     */
    var recoverable by remember(project.id) { mutableStateOf<RouteDraft?>(null) }
    LaunchedEffect(project.id, draft) {
        if (draft != null && draft.isRecoverable && routeState.nodeCount == 0) recoverable = draft
    }
    // Whether *this* screen has written a draft, so the first composition (zero points) does not
    // delete the draft it is about to offer.
    var draftWritten by remember(project.id) { mutableStateOf(false) }
    LaunchedEffect(project.id, routeState.nodeCount, routeState.closedLoop) {
        val state = routeState
        if (state.nodeCount >= LeaveGuard.MIN_NODES_FOR_A_RUN) {
            onDraftChanged(
                RouteDraft(
                    projectId = project.id,
                    spec = state.spec,
                    nodes = state.nodes,
                    closedLoop = state.closedLoop,
                    savedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            draftWritten = true
        } else if (draftWritten) {
            // Everything was undone: there is no run left to recover.
            onDraftChanged(null)
            draftWritten = false
        }
    }
    val commitRun: () -> Unit = {
        // Persisted label: locale-independent and stable across devices, so a run reads the same
        // in the CSV, the PDF and the project file. One implementation, in `RouteLabels`.
        val runLabel = RouteLabels.next(spec, project.routes.size)
        controller.finish(label = runLabel)?.let { route ->
            onRouteFinished(route)
            // Snapshot the scan statistics with the first finished run: the protocol header must
            // describe the room as it was measured.
            onScanSummary(controller.scanSummary())
        }
    }
    val leave: () -> Unit = {
        if (LeaveGuard.needsConfirmation(routeState.nodeCount)) confirmLeave = true else onBack()
    }
    // Back closes the dialog before it closes the screen.
    BackHandler(enabled = LeaveGuard.needsConfirmation(routeState.nodeCount)) {
        confirmLeave = !confirmLeave
    }
    // Drops the failed session and composes the surface again: ARScene, its engine and its
    // ARCore session are all torn down by the gate, so a fresh one starts from nothing.
    val retrySession: () -> Unit = { sessionError = null }

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
        // A recovered run's coordinates belong to a session that no longer exists: drawing it
        // would put a length of conduit in a random spot of this room.
        controller.syncCommittedRoutes(project.routes.filterNot { it.recovered })
    }

    val lengthText = stringResource(R.string.hud_length, routeState.lengthMeters)
    // The segment under the crosshair, while walking: the number that tells the operator when
    // to stop and tap, rather than tapping and then reading the result.
    val liveSegmentText = if (routeState.nodeCount > 0 && routeState.liveSegmentMeters >= 0.05f) {
        stringResource(R.string.hud_segment, routeState.liveSegmentMeters)
    } else {
        null
    }
    val guidanceText = tracking.guidance.message(tracking)
    val warningText = routeState.warning.message()
    val weakPointText = RunConfidence
        .weakPointPercent(routeState.nodeCount, routeState.worstNodeConfidence)
        ?.let { percent -> stringResource(R.string.ar_confidence_weak, percent) }
    val readinessIssue = readiness
    val readinessText = if (readinessIssue != null && !readinessIssue.canStartSession) {
        readinessIssue.message()
    } else {
        null
    }
    // Every string is resolved here, at composition, never inside `buildString`.
    val routingText = spec.kind.routingLabel()
    val sizeText = spec.sizeLabel
    val pointsText = pluralStringResource(R.plurals.ar_points, routeState.nodeCount, routeState.nodeCount)
    val closedLoopText = if (routeState.closedLoop) stringResource(R.string.ar_closed_loop) else null
    val scanProgressText = stringResource(
        R.string.ar_scan_progress,
        (scanState.coverage * 100).toInt(),
        scanState.triangleCount / 1000,
    )
    val scanInstructionText = stringResource(R.string.scan_instruction)
    val scanUnavailableText = stringResource(R.string.ar_scan_unavailable)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        when (arGate(hasCameraPermission, readinessText != null, sessionError != null)) {
            // The system stops showing its dialog after two denials: a button that re-launches
            // the request then does nothing at all. Say what actually has to happen instead.
            ArGate.PERMISSION -> if (permissionState == CameraPermissionState.BLOCKED) {
                GateMessage(
                    text = stringResource(R.string.ar_camera_blocked),
                    action = stringResource(R.string.ar_open_settings),
                    onAction = openAppSettings,
                )
            } else {
                GateMessage(
                    text = stringResource(R.string.ar_camera_permission),
                    action = stringResource(R.string.ar_grant_permission),
                    onAction = {
                        permissionAsked = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                )
            }

            ArGate.READINESS -> GateMessage(
                text = readinessText.orEmpty(),
                action = stringResource(R.string.export_back),
                onAction = onBack,
            )

            // A session that failed is not a note in the corner: no frame arrives, so no point
            // can be placed and every control is inert. Take the surface away and say so.
            ArGate.SESSION_FAILED -> {
                val retry = canRetrySession(routeState.nodeCount)
                GateMessage(
                    text = stringResource(R.string.ar_session_failed, sessionError.orEmpty()),
                    action = if (retry) {
                        stringResource(R.string.ar_session_retry)
                    } else {
                        stringResource(R.string.ar_leave_save)
                    },
                    onAction = if (retry) {
                        retrySession
                    } else {
                        {
                            commitRun()
                            onBack()
                        }
                    },
                )
            }

            ArGate.NONE -> {
                ArScene(
                    modifier = Modifier.fillMaxSize(),
                    depthEnabled = true,
                    flashMode = if (torchOn) Config.FlashMode.TORCH else Config.FlashMode.OFF,
                    // ConstructAR's offline policy is applied by SceneView immediately before
                    // Session.configure() — the only safe moment for it.
                    sessionConfiguration = { session, config -> controller.applySessionPolicy(session, config) },
                    onSessionCreated = { controller.onSessionCreated(it) },
                    onFrame = { session, frame, width, height ->
                        controller.onFrame(session, frame, width, height)
                    },
                    onSessionFailed = { error ->
                        sessionError = error.message ?: error.javaClass.simpleName
                    },
                    onSceneReady = { scene, engine, materialLoader ->
                        controller.onSceneReady(scene, engine, materialLoader)
                        controller.syncScanMesh(showScanner)
                    },
                    onSceneReleased = { controller.onSceneReleased() },
                )

                // Full-surface tap target. Sits above the AR view so SceneView's own gesture
                // handling cannot swallow a tap, and below the controls.
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
        }

        // ── Floating length label anchored to the last measured point ───────────────────
        hudPosition?.let { (x, y) ->
            if (routeState.nodeCount > 0 && viewSize.width > 0) {
                val labelWidthPx = with(density) { 190.dp.toPx() }
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.offset {
                        IntOffset(
                            (x - with(density) { 95.dp.toPx() }).toInt()
                                .coerceIn(0, (viewSize.width - labelWidthPx.toInt()).coerceAtLeast(0)),
                            (y + with(density) { 24.dp.toPx() }).toInt()
                                .coerceIn(0, (viewSize.height - 40).coerceAtLeast(0)),
                        )
                    },
                ) {
                    Text(
                        text = lengthText,
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
                            text = lengthText,
                            style = MaterialTheme.typography.displaySmall,
                            color = SiteColors.cable,
                        )
                        // Total above, the segment under the crosshair below it: while walking,
                        // the second number is the one being decided.
                        liveSegmentText?.let { segment ->
                            Text(
                                text = segment,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                        Text(
                            text = buildString {
                                append(routingText)
                                append(' ')
                                append(sizeText)
                                append(" · ")
                                append(pointsText)
                                if (closedLoopText != null) {
                                    append(" · ")
                                    append(closedLoopText)
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                    // Offline badge: the operator must be able to see at a glance that
                    // nothing on this screen talks to a network.
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.ar_offline_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    IconButton(onClick = onOpenExport, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.home_export),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = leave, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.ar_close),
                            tint = Color.White,
                        )
                    }
                }

                // Coaching: tracking verdict first, then the scan state.
                val statusLine = guidanceText ?: when {
                    !showScanner -> null
                    !scanState.ready -> scanUnavailableText
                    scanState.meshVersion == 0 -> scanInstructionText
                    else -> scanProgressText
                }
                statusLine?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            warningText != null -> SiteColors.warning
                            tracking.quality == TrackingQuality.NOT_TRACKING -> SiteColors.danger
                            else -> Color.White.copy(alpha = 0.75f)
                        },
                    )
                }
                warningText?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = SiteColors.warning)
                }
                // The weakest point of the run, not the last one: one guessed point makes the
                // whole length a guess, and this is the only moment it can still be re-tapped.
                weakPointText?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = SiteColors.warning)
                }
                // Whether the point under the crosshair has settled. Until it has, a tap would
                // commit one noisy frame; the stabiliser says when it is worth tapping.
                stability?.let { reading ->
                    if (reading.hasPoint) {
                        Text(
                            text = stringResource(
                                if (reading.settled) R.string.ar_point_locked else R.string.ar_hold_steady,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (reading.settled) {
                                SiteColors.accent
                            } else {
                                Color.White.copy(alpha = 0.7f)
                            },
                        )
                    }
                }
                // Hit diagnostics, in the operator's language: which surface and which cascade
                // tier produced the last node.
                lastHit?.let { resolution ->
                    val hit = resolution.hit
                    Text(
                        text = if (hit != null) {
                            hit.surfaceKind.label() + " · " + hit.source.tierLabel()
                        } else {
                            stringResource(R.string.ar_no_surface)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hit != null) Color.White.copy(alpha = 0.6f) else SiteColors.danger,
                    )
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
                    label = stringResource(R.string.ar_undo),
                    icon = Icons.AutoMirrored.Filled.Undo,
                    onClick = { controller.undo() },
                    tone = ActionTone.SECONDARY,
                    enabled = routeState.nodeCount > 0,
                    modifier = Modifier.weight(1f),
                )
                GloveButton(
                    label = stringResource(R.string.ar_torch),
                    icon = Icons.Default.FlashlightOn,
                    onClick = { controller.toggleTorch() },
                    tone = ActionTone.SECONDARY,
                    selected = torchOn,
                    modifier = Modifier.weight(1f),
                )
                GloveButton(
                    label = stringResource(if (showScanner) R.string.ar_stop_scan else R.string.ar_scan),
                    icon = Icons.Default.Add,
                    onClick = { showScanner = !showScanner },
                    tone = ActionTone.SECONDARY,
                    selected = showScanner,
                    modifier = Modifier.weight(1f),
                )
            }

            GloveButtonRow {
                GloveButton(
                    label = stringResource(R.string.ar_close_loop),
                    icon = Icons.Default.Loop,
                    onClick = { controller.routeSession.closeLoop() },
                    tone = ActionTone.SECONDARY,
                    enabled = routeState.nodeCount >= 3,
                    modifier = Modifier.weight(1f),
                )
                GloveButton(
                    label = stringResource(R.string.ar_finish),
                    icon = Icons.Default.Save,
                    onClick = commitRun,
                    tone = ActionTone.PRIMARY,
                    enabled = routeState.canFinish,
                    modifier = Modifier.weight(2f),
                )
            }

            GloveButton(
                label = stringResource(R.string.ar_add_point),
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

    if (confirmLeave) {
        Dialog(onDismissRequest = { confirmLeave = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF10140C),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ar_leave_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    // Same two strings the HUD already resolved: no second formatting site.
                    Text(
                        text = stringResource(R.string.ar_leave_body, pointsText + " · " + lengthText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    GloveButton(
                        label = stringResource(R.string.ar_leave_save),
                        icon = Icons.Default.Save,
                        onClick = {
                            confirmLeave = false
                            commitRun()
                            onDraftChanged(null)
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GloveButton(
                        label = stringResource(R.string.ar_leave_discard),
                        icon = Icons.Default.Close,
                        onClick = {
                            confirmLeave = false
                            // A deliberate discard must not come back as an offer next time.
                            onDraftChanged(null)
                            onBack()
                        },
                        tone = ActionTone.DANGER,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { confirmLeave = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.home_cancel), color = Color.White.copy(alpha = 0.75f))
                    }
                }
            }
        }
    }

    // The run the last process did not finish. Drawn over the viewfinder so it cannot be
    // scrolled past, and not dismissible by tapping outside: the operator has to decide.
    recoverable?.let { pending ->
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF10140C),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.draft_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        text = stringResource(
                            R.string.draft_body,
                            pluralStringResource(
                                R.plurals.ar_points,
                                pending.nodes.size,
                                pending.nodes.size,
                            ) + " · " + stringResource(R.string.hud_length, pending.lengthMeters),
                            formatTimeLabel(pending.savedAtEpochMs),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    GloveButton(
                        label = stringResource(R.string.draft_save),
                        icon = Icons.Default.Save,
                        onClick = {
                            recoverable = null
                            onDraftRecovered(pending)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GloveButton(
                        label = stringResource(R.string.draft_discard),
                        icon = Icons.Default.Close,
                        onClick = {
                            recoverable = null
                            onDraftDiscarded()
                        },
                        tone = ActionTone.DANGER,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Blocking message with one action: missing permission, unsupported device, failed session. */
@Composable
private fun GateMessage(text: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        GloveButton(
            label = action,
            icon = Icons.Default.Add,
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        )
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
        colors = ButtonDefaults.buttonColors(
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

/** Unwraps the hosting [Activity] from a (possibly themed) Compose context. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
