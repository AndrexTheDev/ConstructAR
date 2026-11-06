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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.constructar.app.ui.LocalDecimalMark
import com.constructar.app.ui.shownLength
import com.constructar.app.diagnostics.Diagnostics
import com.constructar.app.ui.shownLengthWithUnit
import com.constructar.app.ui.formatTimeLabel
import com.constructar.app.ui.rememberTimePattern
import com.constructar.app.ui.label
import com.constructar.app.ui.message
import com.constructar.app.ui.routingLabel
import com.constructar.app.ui.snapMovedRes
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.app.ui.tierLabel
import com.constructar.arengine.hit.FloorHudLine
import com.constructar.arengine.hit.floorHudLine
import com.constructar.arengine.keyframe.ClosureState
import com.constructar.arengine.keyframe.PoseGraphStanding
import com.constructar.arengine.render.ArScene
import com.constructar.arengine.session.ArReadiness
import com.constructar.arengine.tracking.TrackingQuality
import com.google.ar.core.Config
import com.constructar.core.calib.CalibrationOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.constructar.core.calib.CalibrationReference
import com.constructar.core.calib.CalibrationSweep
import com.constructar.core.calib.ReferenceCalibration
import com.constructar.core.model.ConduitPresets
import com.constructar.core.model.ProjectRecord
import com.constructar.core.units.LengthUnit
import com.constructar.core.units.PercentText
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
 * [com.constructar.core.units.NumberText] or from the `hud_length` format resource, and
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
    /**
     * The vectorised plan of the room, handed over once per committed run.
     *
     * The screen does not write the picture: the plan is data the caller stores (the card thumbnail
     * is derived from it), and the screen has no repository. It is taken here because *this* is
     * where the volume is still alive.
     */
    onFloorplan: (com.constructar.nativebridge.FloorplanPayload) -> Unit = {},
    onOpenExport: () -> Unit,
    /** A run a killed process left behind, loaded by the caller. Offered back once per entry. */
    draft: RouteDraft? = null,
    /** `null` clears the stored draft; anything else is the run as it stands. */
    onDraftChanged: (RouteDraft?) -> Unit = {},
    onDraftRecovered: (RouteDraft) -> Unit = {},
    onDraftDiscarded: () -> Unit = {},
    /**
     * Stores the factor a reference calibration produced, or refuses it (the view model asks
     * `ReferenceCalibration.accepted` before it writes anything).
     *
     * The screen does not keep the result: a factor that lived only in this composable would be
     * forgotten the moment the operator leaves the AR view, and the metres he takes to the
     * customer would be the uncorrected ones.
     */
    onCalibration: (Double) -> Unit = {},
    /**
     * Stores the floor's height, **with the session that measured it**, once a tracked plane is one.
     *
     * The reference every mounting height in the trade is measured from cannot be assumed: ARCore's
     * origin is wherever the session started, so DIN 18015's 30 cm socket would be printed at
     * whatever height the phone happened to be held. The planes only exist inside the hit source,
     * so this screen is the last place that can see one; the project keeps it (finding 214).
     *
     * The session id travels with the height because a floor is only a reference for nodes in
     * *its own* coordinate system. A room measured over two sessions has two origins and two
     * floors, and a project that stored only the last one would print yesterday's socket as if it
     * had been measured from today's floor (finding 217).
     */
    onFloorHeight: (String, Float) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // What this screen may spend on its two panels (finding 257). The first run on a phone showed
    // why a budget is needed: the action stack was laid out at its natural height, which is taller
    // than a phone, and because it is aligned to the bottom it grew upwards over the status bar -
    // over the export and close icons in it, which is why they stopped answering. The numbers come
    // from `HudLayout`, so they are tested without a device.
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.toFloat()
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val fontScale = configuration.fontScale
    val statusMaxHeight = HudLayout.statusMaxHeightDp(screenHeightDp, fontScale).dp
    val sheetMaxHeight = HudLayout.sheetMaxHeightDp(screenHeightDp, fontScale).dp
    val pairColumns = HudLayout.columns(screenWidthDp, 2, fontScale)
    val controller = remember { ArMeasureController(context) }
    val activity = remember(context) { context.findActivity() }
    // The two-tap calibration sweep (see `CalibrationSweep`): one value instead of three
    // nullables, so a third tap cannot move the factor the operator is reading. Not saved state:
    // a rotation ends the sweep, which costs two taps and never a stored number.
    var calibrationSweep by remember { mutableStateOf<CalibrationSweep>(CalibrationSweep.Idle) }
    var calibrationOpen by rememberSaveable { mutableStateOf(false) }
    // The marker sweep: unlike the two-tap calibration this one runs while the operator walks, so
    // whether it is open has to survive a recomposition - but not a rotation, because a rotation is
    // a new ARCore session and the sweep would be pairing two world origins (finding 235).
    var markerOpen by remember { mutableStateOf(false) }
    var closureOpen by remember { mutableStateOf(false) }
    val markerState by controller.markerProbeState.collectAsStateWithLifecycle()
    // Whether the marker sweep can be opened - as state, not as a snapshot (finding 271).
    //
    // The answer comes from the native engine, and `TsdfScanPipeline.start()` creates that engine
    // in a `LaunchedEffect`, i.e. *after* the first composition. `remember(controller)` therefore
    // froze "no" for the whole session on every phone, the button stayed grey, and the sentence
    // under it claimed the device could not read markers - a grey button that lies, which is the
    // exact failure this finding is about. It is re-read when the pipeline's readiness flips, and
    // only then: `tagScaleDefaults()` is a JNI call and the pipeline's state also emits per frame.
    val markerAvailable by produceState(initialValue = false) {
        controller.pipeline.state
            .map { it.ready }
            .distinctUntilChanged()
            .collect { ready -> value = ready && controller.isMarkerProbeAvailable() }
    }

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
    // a dialog, and there is none once the system has stopped showing it. The same resume also has
    // to invalidate the ARCore answer: an install or an update completed in the Play Store happens
    // while this screen is merely stopped, and readiness was fetched once per camera permission -
    // so the gate kept saying "ARCore needs an update" over a device that had it (finding 146).
    var readinessRecheck by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = cameraGranted()
                readinessRecheck++
            }
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
    // The first check on entry may send him to Play; every re-check after a resume asks the device
    // silently instead. Reset to false by the gate's own "Play Store" button, which is an explicit
    // request to be prompted again.
    var readinessMayPrompt by remember { mutableStateOf(true) }
    LaunchedEffect(hasCameraPermission, readinessRecheck) {
        if (!hasCameraPermission) {
            readiness = null
        } else {
            readiness = controller.checkReadiness(activity, true, requestInstallFromPlay = readinessMayPrompt)
            readinessMayPrompt = false
        }
    }
    var sessionError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    val routeState by controller.routeSession.state.collectAsStateWithLifecycle()
    val tracking by controller.tracking.status.collectAsStateWithLifecycle()
    val scanState by controller.pipeline.state.collectAsStateWithLifecycle()
    val torchOn by controller.torchOn.collectAsStateWithLifecycle()
    // A torch button that cannot switch anything on is not a button: on a device whose camera
    // refuses the torch (most tablets have no flash) the tap used to do nothing at all
    // (finding 106).
    val torchUnavailable by controller.torchUnavailable.collectAsStateWithLifecycle()
    val hudPosition by controller.hudScreenPixels.collectAsStateWithLifecycle()
    val lastHit by controller.lastHit.collectAsStateWithLifecycle()
    val stability by controller.stability.collectAsStateWithLifecycle()
    val floorDecision by controller.floor.collectAsStateWithLifecycle()
    // A table top is upward-facing, below the phone and higher than the floor: the one case
    // "highest plane wins" got wrong (finding 218). When the evaluator is not sure, the HUD says
    // so instead of the app quietly recording a floor it will later print mounting heights from -
    // and when it *is* sure it says that too, because "no line at all" used to mean both "nothing
    // decided yet" and "recorded" (finding 228).
    val floorLine = floorHudLine(floorDecision?.status)
    val graphStatus by controller.poseGraphStatus.collectAsStateWithLifecycle()
    val closureState by controller.closureState.collectAsStateWithLifecycle()
    // What the conversation ended up doing, with the numbers, in the file the tester sends
    // (finding 256). The dialog is gone in a second; the log keeps the sentence.
    LaunchedEffect(closureState) {
        when (val state = closureState) {
            is ClosureState.Offered -> Diagnostics.event(
                "closure",
                "offered ${state.offer.from}->${state.offer.to} at ${state.offer.distanceMetres} m",
            )
            is ClosureState.Closed -> {
                val correction = state.correction
                Diagnostics.event(
                    "closure",
                    "closed: residual ${state.optimisation.initialCost} -> ${state.optimisation.finalCost}, " +
                        "converged=${state.optimisation.converged}, " +
                        "moved=${correction?.movedNodes ?: 0}/${correction?.nodes ?: 0} nodes, " +
                        "max=${correction?.maxShiftMetres ?: 0f} m",
                )
            }
            ClosureState.Failed -> Diagnostics.event("closure", "optimisation failed")
            ClosureState.NothingFound -> Diagnostics.event("closure", "nothing found")
            ClosureState.Idle -> Unit
        }
    }
    val aiDepthRunning by controller.aiDepthRunning.collectAsStateWithLifecycle()
    val aiDepthSettled by controller.aiDepthSettled.collectAsStateWithLifecycle()
    // Which depth is actually in use. A phone with neither ARCore's depth API nor the bundled
    // model still measures - on the planes it has detected - and the operator has to know that,
    // because it changes where a tap can land (finding 221).
    val depthStatus = remember(
        aiDepthRunning,
        aiDepthSettled,
        tracking.depthSupported,
        controller.depthModelBundled,
    ) {
        com.constructar.arengine.ai.DepthAvailability.of(
            modelBundled = controller.depthModelBundled,
            arcoreDepthSupported = tracking.depthSupported,
            aiRunning = aiDepthRunning,
            aiSettled = aiDepthSettled,
        )
    }
    // Handed to the caller as soon as a floor is *decided* - by the reticle, not only by a tap -
    // and only when the decision is usable: an uncertain floor (a table top under the phone) may
    // be shown as a warning but may not become the reference every mounting height is measured
    // from (findings 218 and 226). The project's write path ignores a repeat within a centimetre
    // and coalesces the burst (findings 214 and 219).
    LaunchedEffect(floorDecision) {
        val decision = floorDecision ?: return@LaunchedEffect
        if (!decision.usable) return@LaunchedEffect
        decision.heightM?.let { if (it.isFinite()) onFloorHeight(controller.sessionId, it) }
    }

    var spec by remember { mutableStateOf(ConduitPresets.all.first()) }
    // Which of the two jobs this screen is doing (finding 267). Measuring points is the default:
    // it is what the app is bought for, and a walkthrough is an errand the operator decides on.
    var mode by rememberSaveable { mutableStateOf(MeasureMode.MEASURE) }
    // Two disclosures, both closed on entry (finding 268): "the tools" and "the numbers a person
    // can act on". Everything rare lives behind them, so the screen states one job at a time.
    var toolsOpen by rememberSaveable { mutableStateOf(SheetPlan.TOOLS_OPEN_ON_ENTRY) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var specOpen by rememberSaveable { mutableStateOf(false) }
    /**
     * The run that was just saved, until the next one starts.
     *
     * Saving used to be silent: the sheet went back to "no points" and the only proof the tap had
     * done anything was a number in the header and a line in the project list. This is the sentence
     * that answers "what did I just do", and it stays until the operator taps the next point - a
     * toast that vanishes after three seconds would be missed while the cable is still in his hand.
     */
    var savedRun by remember { mutableStateOf<Pair<String, Float>?>(null) }
    LaunchedEffect(routeState.nodeCount) {
        if (routeState.nodeCount > 0) savedRun = null
    }
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
    // The one place a point is asked for (finding 269). Both paths go through it - the big button,
    // which samples the reticle in the middle of the screen, and a free tap on the wall, which
    // samples the finger - so the ring, the haptic and the sentence can never disagree about
    // whether something happened.
    var tapEcho by remember { mutableStateOf<TapEcho?>(null) }
    // Two counters on purpose: the ring belongs to a tap on the *wall* (it grows out of the
    // finger that touched it), the slot belongs to any action that changed something - including
    // undo, which is a button and must not draw a ring at the last tap's position.
    var ringTick by remember { mutableIntStateOf(0) }
    var slotTick by remember { mutableIntStateOf(0) }
    var tapAt by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    val addPoint: (Offset?) -> Unit = { touched ->
        // A walkthrough sets no points - and until finding 273 it did: the tap surface never asked
        // which mode it was in, so a tap on a wall during a walkthrough added a node to the parked
        // run while the sheet's own sentence said that cannot happen. No ring either, because the
        // ring is the confirmation of a point that landed.
        if (!SheetPlan.tapPlacesPoint(mode)) {
            Diagnostics.event("tap", "ignored: a walkthrough sets no points")
        } else if (viewSize.width <= 0 || viewSize.height <= 0) {
            Diagnostics.event("tap", "ignored: the AR view has no size yet")
        } else {
            val x = touched?.x ?: (viewSize.width / 2f)
            val y = touched?.y ?: (viewSize.height / 2f)
            tapAt = Offset(x, y)
            ringTick++
            slotTick++
            // Logged, because "nothing happens" was the whole report: the log now says whether the
            // tap arrived and what the hit test made of it (finding 256).
            Diagnostics.event(
                "tap",
                "add point at ${x.toInt()},${y.toInt()} of ${viewSize.width}x${viewSize.height}",
            )
            controller.requestTap(x, y, viewSize.width, viewSize.height)
        }
    }
    val commitRun: () -> Unit = {
        // Persisted label: locale-independent and stable across devices, so a run reads the same
        // in the CSV, the PDF and the project file. One implementation, in `RouteLabels`.
        val runLabel = RouteLabels.next(spec, project.routes.map { it.label })
        controller.finish(label = runLabel)?.let { route ->
            savedRun = runLabel to route.polylineLengthMeters
            onRouteFinished(route)
            // Snapshot the scan statistics with the first finished run: the protocol header must
            // describe the room as it was measured.
            onScanSummary(controller.scanSummary())
            // The plan comes last: the summary is a measurement and the picture is a convenience,
            // so if the worker is too busy to answer within its bounded wait, the run still keeps
            // its statistics.
            controller.finalFloorplan()?.let(onFloorplan)
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
    // The decimal mark for the snap digits, resolved once: `SnapReading.moved` prints numbers, and
    // a lambda that runs in a coroutine cannot call `stringResource`-style composables.
    val decimalMarkForEcho = LocalDecimalMark.current
    LaunchedEffect(Unit) {
        controller.pipeline.start(scope)
        controller.hitEvents.collect { event ->
            if (event != null) {
                // What the tap actually did, read where the tap is applied (finding 269). The node
                // count before and after is the only honest answer: a resolution that found a
                // surface says nothing about whether the run accepted the point - `TOO_CLOSE` and
                // `UNPINNED` are refusals, and until now they were a line in a scrolling block
                // that the operator was not looking at.
                val before = controller.routeSession.state.value.nodeCount
                val resolution = controller.applyPendingHit()
                val hit = resolution?.hit
                val echo = TapFeedback.echo(
                    hasSurface = hit != null,
                    added = controller.routeSession.state.value.nodeCount > before,
                    snapped = SnapReading.moved(hit?.snap, decimalMarkForEcho) != null,
                )
                tapEcho = echo
                // One tick through a glove for the two answers that were a point placed. A miss
                // and a refusal stay silent to the hand and speak on the glass: a pattern the
                // operator learns in ten taps is worth more than a buzz on everything.
                if (echo == TapEcho.PLACED || echo == TapEcho.SNAPPED) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
    }
    // The result is an event, not a state: it leaves on its own, and a new tap restarts the clock
    // instead of stacking a second line (finding 269).
    LaunchedEffect(slotTick) {
        if (slotTick == 0) return@LaunchedEffect
        delay(TapFeedback.RESULT_VISIBLE_MS)
        tapEcho = null
    }
    // The run being measured follows the reticle (finding 162): `RouteSession` rebuilds the
    // preview whenever a node is added or the reticle moves, and the geometry controller skips the
    // upload when the signature has not changed, so this is at most one upload per geometry change
    // - and the key becomes `null` when the run is finished, which removes the mesh.
    LaunchedEffect(routeState.preview, routeState.nodeCount) {
        controller.syncLiveRoute()
    }
    // ...but only while a run is being measured (finding 273). In a walkthrough the recorded run
    // keeps the shape it was measured with, which is the whole difference between "a line I
    // measured" and "a line that follows my camera".
    LaunchedEffect(mode) {
        controller.setFollowReticle(SheetPlan.runFollowsReticle(mode))
    }
    // The room overlay is a property of the mode, not of a toggle buried among the tools: it is on
    // exactly in the walkthrough (finding 267). Re-uploaded whenever the (300 ms-throttled) mesh
    // changes, and explicitly hidden when the mode changes back to measuring.
    LaunchedEffect(scanState.meshVersion, mode) {
        controller.syncScanMesh(mode.showsRoomOverlay)
    }
    // Committed runs are re-meshed when a new one is added (and at most once per second).
    LaunchedEffect(project.routes.size) {
        // Only this session's runs can be placed: a recovered run - and one measured on an
        // earlier visit - belongs to a world origin that no longer exists, and drawing it would
        // put a length of conduit in a random spot of this room (finding 129).
        controller.syncCommittedRoutes(project.routes)
    }

    val lengthText = stringResource(R.string.hud_length, shownLength(routeState.lengthMeters))
    // The segment under the crosshair, while walking: the number that tells the operator when
    // to stop and tap, rather than tapping and then reading the result.
    val liveSegmentText = if (routeState.nodeCount > 0 && routeState.liveSegmentMeters >= 0.05f) {
        stringResource(R.string.hud_segment, routeState.liveSegmentMeters)
    } else {
        null
    }
    val guidanceText = tracking.guidance.message(tracking)
    val warningText = routeState.warning.message()
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
    // The room, not just the run: the operator's real question on site. It reuses the export
    // screen's own "N runs · X total" line, so the number on the phone and on the protocol come
    // from one sentence and one formatter.
    val roomTotals = RunSummary.of(
        committedMeters = project.routes.map { it.polylineLengthMeters },
        liveNodes = routeState.nodeCount,
        liveMeters = routeState.lengthMeters,
    )
    val roomTotalText = if (roomTotals.runs > 0) {
        stringResource(
            R.string.export_runs_total,
            roomTotals.runs,
            shownLengthWithUnit(roomTotals.meters),
        )
    } else {
        null
    }
    // The percentage is the share of the reference detail budget, not a fraction of the room:
    // the string says so since finding 102.
    val scanProgressText = stringResource(
        R.string.ar_scan_progress,
        PercentText.of(scanState.detailFraction),
        scanState.triangleCount / 1000,
    )
    val scanInstructionText = stringResource(R.string.scan_instruction)
    val scanUnavailableText = stringResource(R.string.ar_scan_unavailable)
    // The volume budget is spent: the mesh has stopped growing and the gauge will not move again.
    // Said out loud, because a stuck scan and a slow one look the same from the outside
    // (finding 97).
    val scanFullText = stringResource(R.string.ar_scan_full)
    // ...and the queue: the pipeline refuses frames it cannot integrate, which is the difference
    // between a room that is filling in slowly and a phone that is not keeping up at all
    // (finding 170).
    val scanBehindText = stringResource(
        R.string.ar_scan_behind,
        PercentText.of(scanState.droppedFraction),
    )

    // What the screen owes the operator, in order (finding 265). Everything below is resolved here
    // at composition, never inside a `buildString`, and the ordering itself is `MeasureGuide`'s -
    // testable without a phone, which is the only way a rule about "the gauge is never replaced by
    // an instruction" stays true after the next edit.
    val guideState = GuideState(
        mode = mode,
        nodeCount = routeState.nodeCount,
        canFinish = routeState.canFinish,
        closedLoop = routeState.closedLoop,
        coaching = guidanceText != null,
        // The room pipeline is offered to the screen exactly in the walkthrough; in measuring
        // mode there is no scan sentence and no gauge at all.
        scannerOn = mode.showsRoomOverlay,
        scanReady = scanState.ready,
        meshVersion = scanState.meshVersion,
        hitBlockBudget = scanState.hitBlockBudget,
        droppedShare = scanState.droppedFraction,
        // The weakest point of the run, as a percentage - the one number that says whether the
        // length about to be saved is a measurement or an estimate (finding 275).
        weakPointPercent = RunConfidence.weakPointPercent(
            routeState.nodeCount,
            routeState.worstNodeConfidence,
        ),
    )
    val guideStep = MeasureGuide.currentStep(guideState)
    // Where the operator is, in the report's own words (finding 266): "the app crashed" is not a
    // bug report. One line per step change, and the crash file's header carries the last one.
    LaunchedEffect(guideStep) {
        Diagnostics.place(
            when (guideStep) {
                MeasureStep.SCAN -> "ar-measure: capture - walking the room"
                MeasureStep.POINTS -> "ar-measure: points - setting points"
                MeasureStep.FINISH -> "ar-measure: points - run ready to save"
            },
        )
    }
    val firstPointText = stringResource(R.string.ar_guide_first)
    val secondPointText = stringResource(R.string.ar_guide_second)
    val distanceText = stringResource(R.string.ar_guide_distance, lengthText)
    val ringText = stringResource(R.string.ar_guide_ring, lengthText)
    val captureWalkText = stringResource(R.string.capture_walk)
    val changeCableText = stringResource(R.string.spec_change)
    val blockRunText = stringResource(R.string.ar_hint_run_needs, routeState.nodeCount)
    // The one slot under the strip (finding 269): the result of the last tap, or the one action the
    // reticle is asking for. It is the same slot the readiness instruction used to occupy - behind
    // the diagnostics toggle, where the operator never found it.
    val tapSlot = TapFeedback.slot(
        resultVisible = tapEcho != null,
        // Only a measurement places points. A walkthrough has nothing to steady: its reticle is not
        // being used, and a "hold steady" over a room being scanned is an instruction to nowhere.
        holding = mode.setsPoints && stability?.let { it.hasPoint && !it.settled } == true,
    )
    val snapEchoText = lastHit?.hit?.snap?.let { snap ->
        SnapReading.moved(snap, decimalMarkForEcho)?.let { stringResource(snap.kind.snapMovedRes(), it) }
    }
    val slotText: String? = when (tapSlot) {
        TapSlot.NONE -> null
        TapSlot.HOLD -> stringResource(R.string.ar_hold_steady)
        TapSlot.RESULT -> when (tapEcho) {
            TapEcho.PLACED -> stringResource(R.string.tap_placed)
            TapEcho.SNAPPED -> snapEchoText
            TapEcho.MISS -> stringResource(R.string.ar_no_surface)
            TapEcho.UNDONE -> stringResource(R.string.tap_undone)
            // The refusal prints the sentence the run already wrote - "too close to the previous
            // point", "that point could not be pinned". A generic "not possible" would hide the
            // one word that says what to do differently.
            TapEcho.REFUSED, null -> warningText
        }
    }
    // Lime for a point that exists, amber for everything that is asking something of the operator:
    // a miss, a refusal, and "hold steady" are all "act before the next tap".
    val slotColor = when (tapEcho) {
        TapEcho.PLACED, TapEcho.SNAPPED -> SiteColors.accent
        TapEcho.UNDONE, TapEcho.MISS, TapEcho.REFUSED, null -> SiteColors.warning
    }
    val guideRows = MeasureGuide.lines(guideState).mapNotNull { line ->
        val text = when (line) {
            GuideLine.COACHING -> guidanceText
            GuideLine.SCAN_EMPTY -> scanInstructionText
            GuideLine.SCAN_PROGRESS -> scanProgressText
            GuideLine.SCAN_FULL -> scanFullText
            GuideLine.SCAN_BEHIND -> scanBehindText
            GuideLine.SCAN_UNAVAILABLE -> scanUnavailableText
            GuideLine.FIRST_POINT -> firstPointText
            GuideLine.SECOND_POINT -> secondPointText
            GuideLine.DISTANCE -> distanceText
            GuideLine.RING -> ringText
            GuideLine.CAPTURE_WALK -> captureWalkText
            GuideLine.WEAK_POINT -> guideState.weakPointPercent?.let { percent ->
                stringResource(R.string.ar_confidence_weak, percent)
            }
            GuideLine.BLOCK_RUN -> blockRunText
        }
        text?.let { line to it }
    }
    // The refusal stays under the button it belongs to; everything else is the status block.
    val blockRow = guideRows.firstOrNull { (line, _) -> line == GuideLine.BLOCK_RUN }
    val statusRows = guideRows.filterNot { (line, _) -> line == GuideLine.BLOCK_RUN }

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

            // The three fixable answers (ARCore missing, too old, install declined) get the one
            // button that actually helps; "Back" stays for the answers that are not his to fix.
            ArGate.READINESS -> {
                val install = ArReadinessAction.offersInstall(readinessIssue)
                // Asking Play again is an explicit request to be prompted, so the dialog comes back.
                val askStore: () -> Unit = {
                    readinessMayPrompt = true
                    readinessRecheck++
                }
                GateMessage(
                    text = readinessText.orEmpty(),
                    action = stringResource(if (install) R.string.ar_install_arcore else R.string.export_back),
                    icon = if (install) Icons.Default.Refresh else Icons.Default.Close,
                    onAction = if (install) askStore else onBack,
                )
            }

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
                    // Deliberate raw detail (`raw-detail`): ARCore's own sentence is the
                    // diagnosis, and `ar_session_failed` exists to carry it - the operator pastes
                    // it into a support mail. Everything else says why in his language.
                    onSessionFailed = { error ->
                        sessionError = error.message ?: error.javaClass.simpleName
                    },
                    onSceneReady = { scene, engine, materialLoader ->
                        controller.onSceneReady(scene, engine, materialLoader)
                        controller.syncScanMesh(mode.showsRoomOverlay)
                    },
                    onSceneReleased = { controller.onSceneReleased() },
                )

                // Full-surface tap target. Sits above the AR view so SceneView's own gesture
                // handling cannot swallow a tap, and below the controls.
                //
                // It is also the only way to set a point, and until now it was a camera preview
                // with no name, no action and no state: a screen reader user could aim the phone
                // and had nothing to double-tap. It now announces itself and offers the one
                // action a camera view can have without a fingertip - "set the point in the
                // middle of the screen" - which is where the reticle already is (finding 211).
                val tapHint = stringResource(R.string.a11y_ar_view)
                val tapAction = stringResource(R.string.a11y_ar_place_point)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            contentDescription = tapHint
                            // The action exists only where it can keep its promise (finding 273):
                            // a walkthrough sets no points, so offering "set the point" there
                            // would be an announced action that does nothing. TalkBack reads the
                            // surface, finds no action, and the sheet's own sentence says why.
                            if (SheetPlan.tapPlacesPoint(mode)) {
                                onClick(label = tapAction) {
                                    // The action has no finger and therefore no point on the
                                    // wall: it asks for the reticle, which is where a screen-reader
                                    // user is aiming anyway (finding 211).
                                    addPoint(null)
                                    true
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                addPoint(offset)
                            }
                        },
                ) {
                    // The ring appears where the reticle samples, for as long as **the reticle** is
                    // on a corner or an edge (findings 213 and 274).
                    //
                    // It used to read `lastHit`, which is the last *tap*: so it never appeared
                    // while the operator aimed at a corner (the moment the information is worth
                    // most), and once a tap had snapped it stayed on screen at the centre of the
                    // display long after the reticle had moved somewhere else - a permanent claim
                    // about a point that was no longer under it. The stabiliser's reading carries
                    // the hit the reticle produced on its most recent sample, which is the only
                    // live source of the snap; the tap-time fact ("your point was moved onto the
                    // edge") is the slot's sentence, and stays there.
                    //
                    // Gated by the mode like every other measuring affordance (finding 273): a
                    // walkthrough has no reticle to settle onto anything.
                    SnapMarker(
                        active = SheetPlan.tapPlacesPoint(mode) &&
                            SnapReading.moved(stability?.lastHit?.snap, LocalDecimalMark.current) != null,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // And the event: one ring out of the finger, once per tap (finding 269). It
                    // sits above the AR view but takes no touch - a `Canvas` with no gesture
                    // modifier leaves the tap surface underneath untouched.
                    TapEchoRing(
                        trigger = ringTick,
                        at = tapAt,
                        tone = when (tapEcho) {
                            TapEcho.PLACED, TapEcho.SNAPPED -> SiteColors.accent
                            TapEcho.MISS, TapEcho.REFUSED, TapEcho.UNDONE, null -> SiteColors.warning
                        },
                    )
                }
            }
        }

        // ── Floating length label anchored to the last measured point ───────────────────
        hudPosition?.let { (x, y) ->
            // Only once there is a length to print (finding 272): with one point the label said
            // `0.00 m` in the middle of the wall the operator had just measured.
            if (HudReadout.labelsLastPoint(routeState.nodeCount) && viewSize.width > 0) {
                val labelWidthPx = with(density) { 190.dp.toPx() }
                Surface(
                    color = HudStyle.panel,
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
            color = HudStyle.panel,
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
                        // The headline is the number being decided (finding 272). Until the run
                        // has a length - one point, and a reticle that has something under it -
                        // that is the segment the operator is walking towards; from the second
                        // point on it is the run itself. Before this, the largest type on the
                        // glass carried `0.00 m` in the colour of a measured value while the
                        // number he was actually deciding grew underneath it in body text.
                        val headline = HudReadout.headline(
                            nodeCount = routeState.nodeCount,
                            hasLiveSegment = liveSegmentText != null,
                        )
                        Text(
                            // A number, never a phrase: `displaySmall` in this column has ~184 dp
                            // on a 360 dp phone next to two 72 dp icons, and "Abschnitt 3,20 m"
                            // would wrap the headline onto a second line and push the whole HUD
                            // down. The word that names the number goes underneath it.
                            text = when (headline) {
                                HudHeadline.SEGMENT -> stringResource(
                                    R.string.hud_length,
                                    shownLength(routeState.liveSegmentMeters),
                                )
                                HudHeadline.TOTAL -> lengthText
                            },
                            style = MaterialTheme.typography.displaySmall,
                            // Gold is what a committed length looks like; the segment is still
                            // being aimed at, so it takes the accent the screen uses for "now".
                            color = when (HudReadout.ink(headline)) {
                                HudInk.MEASURED -> SiteColors.cable
                                HudInk.PROVISIONAL -> SiteColors.accent
                            },
                        )
                        // The caption: under a run it is the segment being decided, with one point
                        // it is the word that says the big number is not the run yet.
                        when (headline) {
                            HudHeadline.SEGMENT -> Text(
                                text = stringResource(R.string.hud_segment_word),
                                style = MaterialTheme.typography.bodyMedium,
                                color = SiteColors.accent,
                            )
                            HudHeadline.TOTAL -> liveSegmentText?.let { segment ->
                                Text(
                                    text = segment,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = HudStyle.text,
                                )
                            }
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
                            color = HudStyle.text,
                        )
                    }
                    // Offline badge: the operator must be able to see at a glance that
                    // nothing on this screen talks to a network.
                    Surface(
                        color = HudStyle.rule,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.ar_offline_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = HudStyle.text,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            // The first device run could not open the export sheet at all, and it
                            // could not say whether the tap ever arrived (finding 258).
                            Diagnostics.event("ui", "export opened from the HUD")
                            onOpenExport()
                        },
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.home_export),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = leave, modifier = Modifier.size(72.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.ar_close),
                            tint = Color.White,
                        )
                    }
                }

                // Which of the three steps is being asked for, always visible and directly under
                // the numbers (finding 265): the report was that the app "made no sense", and a
                // screen with three jobs and no statement of which one is current is exactly that.
                StepStrip(
                    chips = MeasureGuide.strip(guideState),
                    modifier = Modifier.padding(top = 6.dp),
                )
                // One slot, directly under the strip, that answers exactly one of two questions
                // (finding 269): what the last tap did, or - when nothing was just tapped - the
                // one action the reticle is asking for right now ("hold steady"). It is pinned,
                // not scrolled: it is the answer to a thing the operator just did with his hand,
                // and a line he has to scroll to find is a line he never sees.
                //
                // The readiness half used to live behind the diagnostics toggle (finding 268). It
                // does not belong there: "hold steady" is the instruction that decides whether the
                // next tap commits one noisy frame, not a diagnostic.
                slotText?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = slotColor,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            // It changes without the operator touching anything, so a screen
                            // reader has to be told rather than asked to find it.
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                // The room's own gauge, pinned above the scrolling text and drawn only in a
                // walkthrough (finding 267): whether anything is captured, in what colour, and how
                // much of it. In measuring mode it draws nothing at all.
                ScanGauge(
                    state = MeasureGuide.scanLine(guideState),
                    detailFraction = scanState.detailFraction,
                    triangles = scanState.triangleCount,
                )

                // Every coaching line lives inside a budget of its own (finding 257): the
                // header above stays pinned — the export and close icons live there, and they
                // were the first casualties when this column used to grow over them — while the
                // long tail below it scrolls instead of pushing the panels on top of each other.
                Column(
                    modifier = Modifier
                        .heightIn(max = statusMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Coaching *and* evidence, never one instead of the other (finding 265). The
                    // old `guidanceText ?: when (hint)` line dropped the scan gauge on every frame
                    // where the tracking advisor had something to say - which on a Pixel 3a in a
                    // hallway is most frames - so the operator walked and no number ever moved.
                    statusRows.forEach { (line, text) ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                warningText != null && line == GuideLine.COACHING -> SiteColors.warning
                                MeasureGuide.tone(line) == GuideTone.QUIET -> HudStyle.hint
                                MeasureGuide.tone(line) == GuideTone.NORMAL -> HudStyle.text
                                tracking.quality == TrackingQuality.NOT_TRACKING -> SiteColors.danger
                                else -> SiteColors.warning
                            },
                        )
                    }
                    // `isFinal`, not just the state: during the model's load the answer is
                    // "planes only" and it may stop being true in a second. A line that appears and
                    // vanishes on every cold start is a line the operator learns to ignore.
                    if (diagnosticsOpen && !depthStatus.hasDepthField && depthStatus.isFinal) {
                        Text(
                            text = stringResource(R.string.ar_depth_planes_only),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SiteColors.warning,
                        )
                    }
                    when (floorLine) {
                        FloorHudLine.RECORDED -> Text(
                            text = stringResource(R.string.ar_floor_recorded),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SiteColors.accent,
                        )
                        FloorHudLine.UNCERTAIN -> Text(
                            text = stringResource(R.string.ar_floor_uncertain),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SiteColors.warning,
                        )
                        FloorHudLine.NONE -> Unit
                    }
                    warningText?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = SiteColors.warning)
                    }
                    roomTotalText?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = SiteColors.accent)
                    }
                    // The line a worker needs stays; the lines only a developer can use go behind
                    // one tap (finding 268). Pose graph, depth field, last hit and its snap are real
                    // measurements - eight lines of them over a camera image is how a working
                    // screen becomes unreadable, which is what "das macht alles keinen Sinn" meant.
                    TextButton(onClick = { diagnosticsOpen = !diagnosticsOpen }) {
                        Text(
                            text = stringResource(
                                if (diagnosticsOpen) R.string.diag_hide else R.string.diag_show,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = HudStyle.hint,
                        )
                    }
                    // Hit diagnostics, in the operator's language: which surface and which cascade
                    // tier produced the last node.
                    // What the pose graph took and what it lost (finding 244). Hidden until there is
                    // something to say: a line reading "nodes: 0" over a wall that is being measured
                    // fine would read as a fault.
                    if (diagnosticsOpen && graphStatus.standing != PoseGraphStanding.EMPTY) {
                        Text(
                            text = stringResource(R.string.ar_pose_graph, graphStatus.nodes, graphStatus.edges),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HudStyle.text,
                        )
                        if (graphStatus.standing == PoseGraphStanding.LOSSY) {
                            // A hole in the graph is not a cosmetic detail: the optimiser will spread
                            // the missing node's error over the trajectory that connects it.
                            Text(
                                text = stringResource(R.string.ar_pose_graph_lost, graphStatus.lost),
                                style = MaterialTheme.typography.bodyMedium,
                                color = SiteColors.warning,
                            )
                        }
                        // A confirmed closure stays visible after its dialog is gone (finding 250):
                        // the numbers used to exist only while the question was on screen, and the
                        // geometry the operator was about to sign said nothing about whether the walk
                        // had been folded back at all.
                        if (graphStatus.closures > 0) {
                            Text(
                                text = stringResource(R.string.ar_pose_graph_closures, graphStatus.closures),
                                style = MaterialTheme.typography.bodyMedium,
                                color = HudStyle.text,
                            )
                            val correction = graphStatus.lastCorrection
                            if (correction != null && correction.moved) {
                                Text(
                                    text = stringResource(
                                        R.string.ar_pose_graph_closure_shift,
                                        correction.movedNodes,
                                        correction.nodes,
                                        shownLengthWithUnit(correction.maxShiftMetres),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = HudStyle.hint,
                                )
                            }
                            // A closure whose solver gave up is not a closure that moved nothing
                            // (findings 247, 252): silence here would read as a consistent graph.
                            if (correction == null && graphStatus.lastClosureConverged == false) {
                                Text(
                                    text = stringResource(R.string.ar_pose_graph_closure_unfinished),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SiteColors.warning,
                                )
                            }
                        }
                    }
                    if (diagnosticsOpen) lastHit?.let { resolution ->
                        val hit = resolution.hit
                        Text(
                            text = if (hit != null) {
                                hit.surfaceKind.label() + " · " + hit.source.tierLabel()
                            } else {
                                stringResource(R.string.ar_no_surface)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hit != null) HudStyle.hint else SiteColors.danger,
                        )
                        // The reticle was pulled onto a corner or an edge: say which one, and by how
                        // far, in the accent colour - otherwise the jump is a mystery and the operator
                        // taps again to "fix" a point that was already better than his aim
                        // (finding 213).
                        val snap = hit?.snap
                        val moved = SnapReading.moved(snap, LocalDecimalMark.current)
                        if (snap != null && moved != null) {
                            Text(
                                text = stringResource(snap.kind.snapMovedRes(), moved),
                                style = MaterialTheme.typography.bodyMedium,
                                color = SiteColors.accent,
                            )
                        }
                    }
                }
            }
        }

        // ── Bottom controls ──────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // The budget, and a scroll inside it (finding 257). Without this the stack simply
                // grew taller than the screen and drew itself over the status bar.
                .navigationBarsPadding()
                .heightIn(max = sheetMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // What the last tap did, first in the sheet and until the next point (finding 268):
            // saving used to be silent, and "did that work?" is the question a worker asks after
            // every tap that ends something. It reads like the number he will carry to the van.
            savedRun?.let { (label, meters) ->
                if (SheetPlan.showsSavedRun(routeState.nodeCount, label)) {
                    // Resolved outside `semantics`: a `Modifier.semantics { }` lambda is not
                    // composable and `stringResource` may not be called inside it.
                    val savedText = stringResource(
                        R.string.run_saved,
                        label,
                        shownLengthWithUnit(meters),
                    )
                    Surface(
                        color = HudStyle.panel,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            // A card that appears by itself is a change a screen reader has to be
                            // told about; the visible text is the announcement.
                            .semantics(mergeDescendants = true) {
                                liveRegion = LiveRegionMode.Polite
                            },
                    ) {
                        Text(
                            text = savedText,
                            style = MaterialTheme.typography.titleSmall,
                            color = Jamaica.Lime,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            // Which job this is, before any of the controls that belong to one of them (finding
            // 267). It has to be the first thing in the sheet: every button below it means
            // something different depending on the answer.
            ModePicker(
                mode = mode,
                onSelect = { chosen ->
                    if (chosen != mode) {
                        Diagnostics.event("ui", "mode ${chosen.name}")
                        mode = chosen
                    }
                },
            )
            // In measuring mode the two chips and the button that follows already say what the
            // screen does; a third sentence about the same thing was the copy that made the sheet
            // feel like a manual (finding 268). A walkthrough does need one line, because its point
            // button is missing.
            if (!mode.setsPoints) {
                // A walkthrough has no point button, and the sentence says where the points went
                // instead of leaving the operator to discover that tapping does nothing.
                Text(
                    text = stringResource(R.string.capture_no_points),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HudStyle.text,
                )
            }

            // ── The one thing to do next (finding 268) ─────────────────────────────────────
            // One primary action, and it is always the operator's next step: the first point,
            // every further point, or - in a walkthrough - the way back to measuring. Nothing else
            // on this screen is allowed to be a second "thing to do" at the same time.
            when (SheetPlan.primary(mode, routeState.nodeCount)) {
                SheetPlan.Primary.FIRST_POINT -> GloveButton(
                    label = stringResource(R.string.ar_add_first_point),
                    icon = Icons.Default.Add,
                    onClick = { addPoint(null) },
                    modifier = Modifier.fillMaxWidth(),
                )

                SheetPlan.Primary.POINT -> GloveButton(
                    label = stringResource(R.string.ar_add_point),
                    icon = Icons.Default.Add,
                    onClick = { addPoint(null) },
                    modifier = Modifier.fillMaxWidth(),
                )

                // A walkthrough has nothing to tap, so its primary action is the honest exit and
                // not a dead point button (finding 267). Outlined rather than filled: it ends the
                // errand, it does not advance it.
                SheetPlan.Primary.END_CAPTURE -> GloveButton(
                    label = stringResource(R.string.capture_end),
                    icon = Icons.Default.Check,
                    tone = ActionTone.SECONDARY,
                    onClick = {
                        Diagnostics.event("ui", "walkthrough ended")
                        mode = MeasureMode.MEASURE
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (SheetPlan.showsRunActions(mode)) {
                // Full width and second in the hierarchy: saving is what turns a walk into a
                // number, and the sentence underneath says what it is still waiting for.
                GloveButton(
                    label = stringResource(R.string.ar_finish),
                    icon = Icons.Default.Save,
                    tone = ActionTone.SECONDARY,
                    enabled = routeState.canFinish,
                    onClick = commitRun,
                    modifier = Modifier.fillMaxWidth(),
                )
                // A greyed-out button that says nothing reads as a broken button - that is exactly
                // how the first device run reported it (finding 258). One sentence, about the
                // button the operator is reaching for, with the count in it (finding 265).
                blockRow?.let { (line, text) ->
                    if (line == GuideLine.BLOCK_RUN) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HudStyle.hint,
                        )
                    }
                }
            }

            val undoLabel = stringResource(R.string.ar_undo)
            val torchLabel = stringResource(R.string.ar_torch)
            val toolActions = mutableListOf(
                ArAction(
                    label = undoLabel,
                    icon = Icons.AutoMirrored.Filled.Undo,
                    // The state comes from the same object the drawer's tools use (finding 276):
                    // on a fresh screen this button is grey, and grey without a word is the
                    // reading the first device report gave a working button (finding 258).
                    state = ToolsPlan.undo(routeState.nodeCount),
                    onClick = {
                        controller.undo()
                        // The last action on this screen that changed a measurement and said
                        // nothing (finding 270). Undo is a tap like any other, so its result goes
                        // in the same slot - and the restart of the timer is the same one.
                        tapEcho = TapEcho.UNDONE
                        slotTick++
                    },
                ),
            )
            // A torch button that cannot switch anything on is not a button: on a device whose
            // camera refuses the torch (most tablets have no flash) the tap used to do nothing at
            // all (finding 106).
            if (!torchUnavailable) {
                toolActions += ArAction(
                    label = torchLabel,
                    icon = Icons.Default.FlashlightOn,
                    state = ToolState(enabled = true),
                    selected = torchOn,
                    onClick = { controller.toggleTorch() },
                )
            }
            ArActionRow(columns = pairColumns, actions = toolActions, nodeCount = routeState.nodeCount)

            // One setting, one chip (finding 268). Six chips at once were five buttons nobody
            // pressed plus one they could not find; the chip names the cable the run is built
            // with and hands the choice to a full-size dialog where every option is glove-sized
            // and the current one is marked.
            if (SheetPlan.showsCableChip(mode)) {
                GloveButton(
                    label = spec.label,
                    icon = Icons.Default.Settings,
                    tone = ActionTone.SECONDARY,
                    onClick = { specOpen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = changeCableText },
                )
            }

            if (specOpen) {
                AlertDialog(
                    onDismissRequest = { specOpen = false },
                    title = { Text(stringResource(R.string.spec_dialog_title)) },
                    text = {
                        Column(
                            // A group of choices, not five unrelated buttons: the selected cable
                            // is announced as "chosen out of six" (the rule `SpecChip` followed
                            // when the six chips were still on the sheet itself).
                            modifier = Modifier
                                .selectableGroup()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ConduitPresets.all.forEach { preset ->
                                GloveButton(
                                    label = preset.label,
                                    icon = Icons.Default.Check,
                                    tone = if (preset == spec) ActionTone.PRIMARY else ActionTone.SECONDARY,
                                    selected = preset == spec,
                                    onClick = {
                                        spec = preset
                                        controller.setSpec(preset)
                                        Diagnostics.event("ui", "cable ${preset.label}")
                                        specOpen = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { specOpen = false }) {
                            Text(stringResource(R.string.export_back))
                        }
                    },
                )
            }

            // ── Everything rare, behind one disclosure (finding 268) ─────────────────
            // Ring closure, calibration, loop check and marker check are used once a week at
            // most, and each of them was a glove-sized rectangle taken from the two actions
            // used every metre. Closed by default, and the button says which way it goes.
            TextButton(onClick = { toolsOpen = !toolsOpen }) {
                Text(
                    text = stringResource(if (toolsOpen) R.string.tools_hide else R.string.tools_show),
                    style = MaterialTheme.typography.labelMedium,
                    color = HudStyle.hint,
                )
            }

            if (toolsOpen) {
                // The ring lives here rather than next to Save: it is the rare half of the same
                // decision, and the sentence under it says how many points it still needs.
                if (mode.setsPoints) {
                    val ring = ToolsPlan.ring(routeState.nodeCount)
                    GloveButton(
                        label = stringResource(R.string.ar_close_loop),
                        icon = Icons.Default.Loop,
                        tone = ActionTone.SECONDARY,
                        enabled = ring.enabled,
                        onClick = { controller.routeSession.closeLoop() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Every grey tool in this drawer says why (finding 271). The rule was already
                    // being kept for the ring and broken for the two below it.
                    ToolReasonLine(ring.reason, routeState.nodeCount)
                }
                // ── Reference calibration ────────────────────────────────────────────────
                // ARCore's metre is metric on its own; this is the operator's check of it against an
                // object of known size, and the factor it produces is applied to the metres that are
                // reported (`.//docs/CALIBRATION.md`), never to the geometry.
                GloveButton(
                    label = stringResource(R.string.calib_title),
                    icon = Icons.Default.Straighten,
                    tone = ActionTone.SECONDARY,
                    onClick = {
                        calibrationSweep = CalibrationSweep.Idle
                        calibrationOpen = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Loop closure ────────────────────────────────────────────────────────
                // The one write in the SLAM path that needs a person: two visits half a metre apart
                // can be the same doorway or two different corners, and a wrong closure folds the
                // room without anything looking wrong. Enabled once the graph holds nodes at all;
                // the policy's own floor decides whether there is anything to ask about.
                val loopCheck = ToolsPlan.loopCheck(graphStatus.nodes)
                GloveButton(
                    label = stringResource(R.string.loop_check),
                    icon = Icons.Default.Loop,
                    tone = ActionTone.SECONDARY,
                    enabled = loopCheck.enabled,
                    onClick = {
                        closureOpen = true
                        controller.lookForClosure()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ToolReasonLine(loopCheck.reason, routeState.nodeCount)

                // ── Marker check ────────────────────────────────────────────────────────
                // A printed tag of known size, measured through the camera: the second tier of
                // `docs/CALIBRATION.md`, and the only one that needs no tap - the operator aims and
                // walks, and the sweep counts the sightings. It publishes a factor, never a scale
                // stored behind the operator's back; `Apply` is the same reporting-layer write the
                // two-tap sweep uses.
                val markerTool = ToolsPlan.marker(markerAvailable)
                GloveButton(
                    label = stringResource(R.string.marker_title),
                    icon = Icons.Default.QrCodeScanner,
                    tone = ActionTone.SECONDARY,
                    enabled = markerTool.enabled,
                    onClick = {
                        if (controller.startMarkerProbe()) markerOpen = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ToolReasonLine(markerTool.reason, routeState.nodeCount)
            }

            if (closureOpen) {
                LoopClosureDialog(
                    state = closureState,
                    onConfirm = {
                        controller.confirmClosure()
                        closureOpen = false
                    },
                    onRefuse = {
                        controller.dismissClosure()
                        closureOpen = false
                    },
                    onClose = { closureOpen = false },
                )
            }

            if (markerOpen) {
                // The dialog is its own file since finding 242: it is a small state machine
                // (aim, walk, sideways, measured) and it owns the sentences for each phase.
                MarkerCalibrationDialog(
                    state = markerState,
                    onApply = { factor ->
                        onCalibration(factor)
                        controller.stopMarkerProbe()
                        markerOpen = false
                    },
                    onDismiss = {
                        controller.stopMarkerProbe()
                        markerOpen = false
                    },
                )
            }

            if (calibrationOpen) {
                val sweep = calibrationSweep
                AlertDialog(
                    onDismissRequest = {
                        calibrationSweep = CalibrationSweep.Idle
                        calibrationOpen = false
                    },
                    title = { Text(stringResource(R.string.calib_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = stringResource(R.string.calib_hint),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            CalibrationReference.values().forEach { reference ->
                                GloveButton(
                                    label = calibrationLabel(reference),
                                    icon = Icons.Default.Straighten,
                                    tone = if (sweep.reference == reference) ActionTone.PRIMARY else ActionTone.SECONDARY,
                                    onClick = { calibrationSweep = calibrationSweep.start(reference) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            GloveButton(
                                label = stringResource(R.string.calib_take_point),
                                icon = Icons.Default.Add,
                                tone = ActionTone.SECONDARY,
                                enabled = sweep.step.collecting && lastHit?.hit != null,
                                onClick = {
                                    lastHit?.hit?.let { hit ->
                                        calibrationSweep = calibrationSweep.tap(hit.point)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            when (val outcome = sweep.outcome) {
                                is CalibrationOutcome.Accepted -> Text(
                                    text = stringResource(
                                        R.string.calib_success_toast,
                                        ReferenceCalibration.formatScale(outcome.scale),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Jamaica.Lime,
                                )
                                is CalibrationOutcome.Rejected -> Text(
                                    text = stringResource(
                                        R.string.calib_rejected_toast,
                                        ReferenceCalibration.formatScale(outcome.scale),
                                        ReferenceCalibration.formatScale(ReferenceCalibration.MIN_SCALE),
                                        ReferenceCalibration.formatScale(ReferenceCalibration.MAX_SCALE),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SiteColors.danger,
                                )
                                // No measurement at all: the hint above is the whole answer, and
                                // a factor printed here would be a number nobody measured.
                                CalibrationOutcome.Unmeasurable, null -> Unit
                            }
                        }
                    },
                    confirmButton = {
                        val accepted = sweep.outcome as? CalibrationOutcome.Accepted
                        TextButton(
                            enabled = accepted != null,
                            onClick = {
                                accepted?.let { onCalibration(it.scale) }
                                calibrationSweep = CalibrationSweep.Idle
                                calibrationOpen = false
                            },
                        ) { Text(stringResource(R.string.calib_apply)) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                calibrationSweep = CalibrationSweep.Idle
                                calibrationOpen = false
                            },
                        ) { Text(stringResource(R.string.home_cancel)) }
                    },
                )
            }
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
                        color = HudStyle.dialogBody,
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
                        Text(stringResource(R.string.home_cancel), color = HudStyle.dialogAction)
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
                            ) + " · " + stringResource(R.string.hud_length, shownLength(pending.lengthMeters)),
                            formatTimeLabel(pending.savedAtEpochMs, rememberTimePattern()),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HudStyle.dialogBody,
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

/**
 * The sentence under a grey tool (finding 271).
 *
 * One implementation for all three, so that "a disabled control explains itself" cannot be honoured
 * for the ring and forgotten for the two buttons under it - which is exactly what had happened.
 */
@Composable
private fun ToolReasonLine(reason: ToolReason?, nodeCount: Int) {
    val text = toolReasonText(reason, nodeCount) ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = HudStyle.hint,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/**
 * The one place a [ToolReason] becomes a sentence (finding 271).
 *
 * There are two ways to print the answer and they are the same answer: the drawer has room for a
 * line under the button, and the action row - two buttons side by side, on glass, next to the
 * primary - has none. The row puts the same sentence on the button as its `stateDescription`
 * instead (finding 276), which is why the resolution is a function both call rather than a `when`
 * in each of them: a second `when` is how the first one drifted away from the buttons it described.
 */
@Composable
private fun toolReasonText(reason: ToolReason?, nodeCount: Int): String? = when (reason) {
    null -> null
    ToolReason.RING_NEEDS_POINTS -> stringResource(R.string.ar_hint_ring_needs, nodeCount)
    ToolReason.LOOP_NEEDS_ROOM -> stringResource(R.string.loop_needs_room)
    ToolReason.MARKER_NOT_AVAILABLE -> stringResource(R.string.marker_unavailable)
    ToolReason.UNDO_NOTHING_TO_REMOVE -> stringResource(R.string.undo_nothing_to_remove)
}

/** Blocking message with one action: missing permission, unsupported device, failed session. */
@Composable
private fun GateMessage(
    text: String,
    action: String,
    icon: ImageVector = Icons.Default.Add,
    onAction: () -> Unit,
) {
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
            icon = icon,
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
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

/**
 * What the calibration dialog calls the object to hold: a localised name and the size from
 * `:core`, formatted by the one formatter the app uses for lengths.
 *
 * The number is not in the string table on purpose - six translations of "85.60 mm" is six
 * chances to disagree about how long a bank card is, and `ReferenceCalibration` is the only
 * place that fact is allowed to live.
 */
@Composable
private fun calibrationLabel(reference: CalibrationReference): String = if (reference.isCard) {
    stringResource(
        R.string.calib_credit_card,
        shownLengthWithUnit(reference.realMetres.toFloat(), LengthUnit.CENTIMETER, 1),
    )
} else {
    stringResource(R.string.calib_zollstock, shownLengthWithUnit(reference.realMetres.toFloat()))
}

/**
 * One button of the action sheet, as data: [ArActionRow] decides how many fit side by side.
 *
 * The state is a [ToolState] rather than an `enabled` flag (finding 276): a row button cannot be
 * built grey without a reason any more than a drawer tool can, which is the whole point of having
 * taught the rule to the type instead of to the three instances that happened to be in the drawer.
 */
private data class ArAction(
    val label: String,
    val icon: ImageVector,
    val state: ToolState,
    val onClick: () -> Unit,
    val tone: ActionTone = ActionTone.SECONDARY,
    val selected: Boolean = false,
)

/**
 * A row of glove buttons that turns into a stack when they would not fit.
 *
 * A `Row` with three weighted children on a 360 dp screen and a large system font gives each button
 * ~103 dp; the icon and the paddings take most of that and "Close loop" wraps to one character per
 * line, which is what the first device run showed (finding 257). [HudLayout.columns] decides from
 * the width and the font scale alone, so this stays a rendering decision and nothing here measures
 * anything.
 */
@Composable
private fun ArActionRow(columns: Int, actions: List<ArAction>, nodeCount: Int) {
    if (columns <= 1) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            actions.forEach { action ->
                ArActionButton(action, nodeCount, Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            actions.forEach { action ->
                ArActionButton(action, nodeCount, Modifier.weight(1f))
            }
        }
    }
}

/**
 * One button of the row, with its reason attached to the button itself.
 *
 * The row has no room for a line - it is two glove buttons under the primary action - so the
 * reason is announced rather than printed: `stateDescription` is what a screen reader adds after
 * the label and the disabled state, and it is the only place a control this size can explain
 * itself without pushing the next action off the glass (finding 276).
 */
@Composable
private fun ArActionButton(action: ArAction, nodeCount: Int, modifier: Modifier) {
    // Hoisted: a `semantics { }` lambda is not composable and `stringResource` may not be called
    // inside it. `null` on a usable button, so an enabled control announces no excuse.
    val reasonText = toolReasonText(action.state.reason, nodeCount)
    GloveButton(
        label = action.label,
        icon = action.icon,
        onClick = action.onClick,
        modifier = if (reasonText == null) {
            modifier
        } else {
            modifier.semantics { stateDescription = reasonText }
        },
        tone = action.tone,
        enabled = action.state.enabled,
        selected = action.selected,
    )
}
