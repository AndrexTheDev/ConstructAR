/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.constructar.app.billing.EntitlementStore
import com.constructar.app.billing.LicencePublicKey
import com.constructar.app.data.DraftGate
import com.constructar.app.data.ProjectRepository
import com.constructar.app.data.SaveCoalescer
import com.constructar.app.data.RoomThumbnail
import com.constructar.app.data.SiteSettings
import com.constructar.app.data.UiPreferences
import com.constructar.app.ui.FieldPhotos
import com.constructar.app.ui.ProjectSlots
import com.constructar.core.calib.ReferenceCalibration
import com.constructar.core.bom.MaterialList
import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.bom.MaterialListOptions
import com.constructar.core.licence.LicenceCheck
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.DefectItem
import com.constructar.core.model.DefectSeverity
import com.constructar.core.model.DiaryEntry
import com.constructar.core.model.PhotoKind
import com.constructar.core.model.PhotoTokens
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteDraft
import com.constructar.core.model.RouteLabels
import com.constructar.core.model.WorkSession
import com.constructar.core.price.Pricing
import com.constructar.core.wall.OpeningKind
import com.constructar.core.wall.PlacedOpening
import com.constructar.core.wall.statedRoomHeight
import com.constructar.core.workflow.ProjectWorkflowEngine
import com.constructar.core.workflow.ProjectWorkflowEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Application-level state: which project is open, what is licensed, and everything derived
 * from the two.
 *
 * Deliberately kept free of ARCore: the AR screen owns a controller with its own lifecycle,
 * and this view model only persists what the operator decides to keep. That separation is
 * what allows the project screen (and the export screen) to work with the camera closed.
 */
/**
 * A failure the operator must see, as a *code*.
 *
 * The view model holds no Context and produces no sentences: [AppRoot] turns these into
 * `stringResource` lookups, so the snackbar is localised like the rest of the UI.
 */
enum class UiMessage {
    PROJECT_OPEN_FAILED,
    PROJECT_SAVE_FAILED,

    /**
     * A field photo could not be written to app storage - a full phone, usually. Its own code
     * because the operator's next action differs: with the room save he retries, with this one he
     * frees space first.
     */
    PHOTO_SAVE_FAILED,

    /** The free plan's single room is taken. The UI answers with the paywall, never a crash. */
    PROJECT_LIMIT_REACHED,

    /**
     * A room could not be removed from storage - a read-only or revoked folder.
     *
     * `repository.delete(id)` answers whether the file is gone, and the answer was dropped: the
     * screen cleared the open room and told the caller it was deleted, the next `refresh()` read
     * the folder and listed the room again, and the operator deleted it a second time (finding 93).
     */
    PROJECT_DELETE_FAILED,

    /**
     * A photo file could not be removed: the defect or room it belonged to is gone, the picture
     * is not.
     *
     * That is not cosmetic - the file goes into the next backup and stays on the phone after the
     * customer's documentation was deliberately deleted (finding 93).
     */
    PHOTO_DELETE_FAILED,
}

class ProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)

    /** Submission order for draft writes; see [writeDraft]. */
    private val draftGate = DraftGate()
    private val draftWrites = Mutex()
    private val projectWriteGate = DraftGate()
    private val projectWrites = Mutex()
    private val entitlements = EntitlementStore(application)
    private val preferences = UiPreferences(application)
    private val settings = SiteSettings(application)

    private val mutableState = MutableStateFlow(AppState())

    /** Coalesces the floor writes: the last floor of a burst is the one worth storing. */
    private val floorWrites = SaveCoalescer<FloorSample>(windowMillis = SaveCoalescer.DEFAULT_WINDOW_MS) {
        persistFloor(it)
    }

    init {
        viewModelScope.launch { floorWrites.run() }
    }

    /** One "this session measured this floor" request, on its way to disk. */
    private data class FloorSample(val sessionId: String, val heightM: Float)
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    /** The public write path for central-state intents. No composable mutates [state] directly. */
    fun dispatch(intent: AppIntent) {
        when (intent) {
            AppIntent.Refresh -> refresh()
            is AppIntent.Workflow -> applyWorkflow(intent.projectId, listOf(intent.event))
            is AppIntent.ExportCommitted -> applyWorkflow(
                intent.projectId,
                buildList {
                    if (intent.hasSignature) add(ProjectWorkflowEvent.SignatureCaptured(intent.atEpochMs))
                    add(ProjectWorkflowEvent.ExportCompleted(intent.atEpochMs))
                },
            )
        }
    }

    /** Exposed for the export screen, which builds its own request. */
    val entitlementsStore: EntitlementStore get() = entitlements

    init {
        // [EntitlementStore] verifies the stored licence in its own constructor; calling
        // `refreshEntitlements()` here as well would run the same ECDSA verification a second
        // time on every cold start for the same answer. The state is seeded from what that
        // constructor already computed, and `refresh()` (the project list) goes to IO.
        mutableState.value = mutableState.value.copy(
            entitlements = entitlements.entitlements.value,
            deviceTag = entitlements.deviceTag,
            trialUsed = entitlements.trialUsed(),
            trialDaysLeft = entitlements.trialDaysLeft(),
            trialAvailable = entitlements.diagnostics.value.trialAvailable,
            demoSigningKey = LicencePublicKey.isDemoKey,
            onboardingDone = preferences.onboardingDone(),
            wasteOverride = settings.wasteFactorOverride(),
            clipSpacingOverride = settings.clipSpacingMetersOverride(),
            firmHourlyRateCents = settings.hourlyRateCents() ?: Pricing.DEFAULT_HOURLY_RATE_CENTS,
            localStateTampered = entitlements.diagnostics.value.localStateTampered,
        )
        refresh()
        resumeLastProject()
    }

    /**
     * Re-opens the room the process died in.
     *
     * The id is a preference, the room is a file: if the file is gone there is nothing to resume
     * and the operator simply lands on his list. Deliberately silent - a message about a room he
     * cannot see would be noise on cold start.
     */
    private fun resumeLastProject() {
        val id = preferences.lastProjectId() ?: return
        viewModelScope.launch {
            val project = repository.load(id) ?: run {
                preferences.setLastProjectId(null)
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                openProject = project,
                materialList = buildMaterialList(project),
                photo = repository.loadPhoto(project.id),
                fieldPhotos = loadFieldPhotos(project),
                resumeReady = true,
            )
        }
    }

    /** The resumed room has been shown once; the next visit to the list must stay a visit. */
    fun consumeResume() {
        mutableState.value = mutableState.value.copy(resumeReady = false)
    }

    fun refresh() {
        viewModelScope.launch {
            val listing = repository.list()
            mutableState.value = mutableState.value.copy(
                loading = false,
                projects = listing.projects,
                unreadableProjects = listing.unreadable,
                unreadableFiles = listing.unreadableNames,
            )
        }
    }

    /** Re-derives licence, trial and device state. Called on resume: that is how expiry works. */
    fun refreshEntitlements() {
        entitlements.refresh()
        // The two answered from sealed values run *before* the snapshot: either of them can fail a
        // seal, and that is the diagnosis the screen is about to show (finding 139).
        val trialUsed = entitlements.trialUsed()
        val trialDaysLeft = entitlements.trialDaysLeft()
        entitlements.refreshDiagnostics()
        mutableState.value = mutableState.value.copy(
            entitlements = entitlements.entitlements.value,
            deviceTag = entitlements.deviceTag,
            trialUsed = trialUsed,
            trialDaysLeft = trialDaysLeft,
            demoSigningKey = LicencePublicKey.isDemoKey,
            clockRolledBack = entitlements.diagnostics.value.clockRolledBack,
            integrityFailed = entitlements.diagnostics.value.integrityFailed,
            localStateTampered = entitlements.diagnostics.value.localStateTampered,
            trialAvailable = entitlements.diagnostics.value.trialAvailable,
        )
    }

    fun activateLicence(text: String): LicenceCheck {
        val check = entitlements.activate(text)
        refreshEntitlements()
        return check
    }

    /**
     * Starts the seven-day trial and says whether it did.
     *
     * The paywall used to switch itself to "trial active" before calling this, so a refusal - the
     * trial was already used, the clock moved back, the build cannot vouch for itself - looked
     * exactly like a success until the operator left the screen and found the app still free.
     */
    fun startTrial(): Boolean {
        val started = entitlements.startTrial()
        refreshEntitlements()
        return started
    }

    fun completeOnboarding() {
        preferences.setOnboardingDone()
        mutableState.value = mutableState.value.copy(onboardingDone = true)
    }

    /**
     * Creates a project unless the free plan's slot is taken.
     *
     * The gate lives here rather than in the button so that no future entry point (a deep link,
     * a share intent) can create a second room without the operator seeing the paywall.
     */
    fun createProject(name: String, siteName: String = "", onCreated: (ProjectRecord) -> Unit) {
        viewModelScope.launch {
            val current = mutableState.value
            // The same count the button draws its state from: a room file this build cannot read
            // is still a room on the phone (findings 126, 149).
            val slotsLeft = ProjectSlots.allowsAnother(
                entitlements = current.entitlements,
                readable = current.projects.size,
                unreadable = current.unreadableProjects,
            )
            if (!slotsLeft) {
                mutableState.value = current.copy(lastError = UiMessage.PROJECT_LIMIT_REACHED)
                return@launch
            }
            // A blank name becomes the operator's own language's word for "room", not English.
            val fallback = getApplication<Application>().getString(R.string.home_default_room_name)
            val project = ProjectWorkflowEngine.reduce(
                repository.create(name, siteName, fallbackName = fallback),
                ProjectWorkflowEvent.ProjectActivated,
            )
            if (!repository.save(project)) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_SAVE_FAILED)
                return@launch
            }
            preferences.setLastProjectId(project.id)
            mutableState.value = mutableState.value.copy(openProject = project, lastError = null)
            refresh()
            onCreated(project)
        }
    }

    fun openProject(id: String, onOpened: (ProjectRecord?) -> Unit = {}) {
        viewModelScope.launch {
            val loaded = repository.load(id)
            if (loaded == null) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_OPEN_FAILED)
                onOpened(null)
                return@launch
            }
            val project = ProjectWorkflowEngine.reduce(loaded, ProjectWorkflowEvent.ProjectActivated)
            if (project != loaded && !repository.save(project)) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_SAVE_FAILED)
                onOpened(null)
                return@launch
            }
            // The room the operator is standing in, for the next cold start (see
            // `resumeLastProject`). Written after a successful load only: a room that cannot be
            // opened is not one to come back to.
            preferences.setLastProjectId(project.id)
            mutableState.value = mutableState.value.copy(
                openProject = project,
                materialList = buildMaterialList(project),
                photo = repository.loadPhoto(project.id),
                fieldPhotos = loadFieldPhotos(project),
                lastError = null,
            )
            onOpened(project)
        }
    }

    /** Adds or replaces a run and persists the project. */
    fun upsertRoute(route: ConduitRoute, onSaved: (ProjectRecord?) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        val updated = project.upsertRoute(route)
        mutate(updated, onSaved)
    }

    // ── Drafts of a run in progress ────────────────────────────────────────────────────────

    /**
     * Loads whatever the last process left of a run for the open project.
     *
     * Called on entering the AR screen rather than at start-up: a draft is only interesting to
     * the screen that can do something with it, and reading it here keeps the home screen free
     * of file access it does not need.
     */
    fun loadDraft() {
        val project = mutableState.value.openProject ?: return
        viewModelScope.launch {
            val draft = repository.loadDraft(project.id)
            // Still the same project? The operator may have gone back while this was in flight.
            if (mutableState.value.openProject?.id == project.id) {
                mutableState.value = mutableState.value.copy(pendingDraft = draft)
            }
        }
    }

    /**
     * Persists the run being measured, or clears it with `null`.
     *
     * Fire-and-forget on purpose: the screen writes on every committed point, and a write that
     * fails must never interrupt a measurement. The cost of a lost draft is the same as before
     * this existed; the cost of a blocked tap is worse.
     *
     * Ordering, however, is not negotiable - see [writeDraft].
     */
    fun saveDraft(draft: RouteDraft?) {
        val projectId = mutableState.value.openProject?.id ?: return
        writeDraft(projectId, draft)
    }

    /**
     * The one place a draft reaches the disk, and the only place that decides whether it may.
     *
     * Two calls a second apart can still complete out of order, because both cross to
     * `Dispatchers.IO` and neither waits for the other. Without a rule, the delete that follows
     * "Save as run" can finish first and the save that came before it can finish last - the draft
     * is back on disk, the next entry offers to "recover" a run that is already saved, and the same
     * conduit is measured into the bill of materials twice. So each request takes a ticket in
     * submission order and only the newest ticket writes, under one lock (finding 74).
     */
    private fun writeDraft(projectId: String, draft: RouteDraft?) {
        val ticket = draftGate.ticket()
        viewModelScope.launch {
            draftWrites.withLock {
                if (!draftGate.isNewest(ticket)) return@withLock
                if (draft == null || !draft.isRecoverable) {
                    repository.deleteDraft(projectId)
                } else {
                    repository.saveDraft(draft)
                }
            }
        }
    }

    /** Takes the interrupted run as a finished route - marked as recovered - and drops the draft. */
    fun recoverDraft(draft: RouteDraft) {
        val project = mutableState.value.openProject ?: return
        val route = draft.toRoute(
            id = "route-${UUID.randomUUID()}",
            label = RouteLabels.next(draft.spec, project.routes.map { it.label }),
            createdAtEpochMs = System.currentTimeMillis(),
        ) ?: return
        mutableState.value = mutableState.value.copy(pendingDraft = null)
        // Through the gate: a save that was queued while he was tapping must not bring the
        // draft back after he has taken it as a run.
        writeDraft(project.id, null)
        upsertRoute(route)
    }

    /** Forgets the interrupted run, because the operator said so. */
    fun discardDraft() {
        val project = mutableState.value.openProject ?: return
        mutableState.value = mutableState.value.copy(pendingDraft = null)
        writeDraft(project.id, null)
    }

    fun removeRoute(routeId: String, onSaved: (ProjectRecord?) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        mutate(project.removeRoute(routeId), onSaved)
    }

    /** Renames the open project (or any project by id) without leaving the screen. */
    fun renameProject(name: String, onSaved: (ProjectRecord?) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        mutate(project.copy(name = trimmed), onSaved)
    }

    /** Who is signing the protocol; the name is printed in the PDF header. */
    fun updateTechnician(name: String, onSaved: (ProjectRecord?) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        mutate(project.copy(technician = name.trim()), onSaved)
    }

    /** The note the operator leaves for the office — it travels in the JSON and the PDF. */
    fun updateNotes(notes: String, onSaved: (ProjectRecord?) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        mutate(project.copy(notes = notes.trim()), onSaved)
    }

    // ── Site file: customer, time, diary, defects ───────────────────────────────────────
    // All of it goes through [mutate], so a new note or a stopped clock is written with the same
    // atomic temp-file dance as a measured run and shows up in the project list immediately.

    /** The customer block printed in the protocol header. */
    fun updateCustomer(name: String, address: String, phone: String, onSaved: (ProjectRecord?) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        mutate(project.withCustomer(name, address, phone), onSaved)
    }

    fun addDiaryEntry(text: String, onSaved: (ProjectRecord?) -> Unit = {}) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val project = mutableState.value.openProject ?: return
        mutate(
            project.addDiaryEntry(
                DiaryEntry(id = UUID.randomUUID().toString(), createdAtEpochMs = System.currentTimeMillis(), text = trimmed),
            ),
            onSaved,
        )
    }

    fun deleteDiaryEntry(entryId: String) {
        val project = mutableState.value.openProject ?: return
        project.diary.firstOrNull { it.id == entryId }?.photo?.let { token ->
            // Same as the defect path: the entry goes, the file may not (finding 93).
            if (!repository.deletePhotoAs(token)) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PHOTO_DELETE_FAILED)
            }
            mutableState.value = mutableState.value.copy(fieldPhotos = mutableState.value.fieldPhotos - token)
        }
        mutate(project.removeDiaryEntry(entryId))
    }

    fun addDefect(
        title: String,
        location: String,
        severity: DefectSeverity,
        onSaved: (ProjectRecord?) -> Unit = {},
    ) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val project = mutableState.value.openProject ?: return
        mutate(
            project.addDefect(
                DefectItem(
                    id = UUID.randomUUID().toString(),
                    createdAtEpochMs = System.currentTimeMillis(),
                    title = trimmed,
                    location = location.trim(),
                    severity = severity,
                ),
            ),
            onSaved,
        )
    }

    fun toggleDefect(defectId: String) {
        val project = mutableState.value.openProject ?: return
        mutate(project.toggleDefect(defectId))
    }

    fun deleteDefect(defectId: String) {
        val project = mutableState.value.openProject ?: return
        project.defects.firstOrNull { it.id == defectId }?.photo?.let { token ->
            // The defect goes either way; the picture is a file, and a file this phone refuses to
            // delete stays in the folder and in the next backup (finding 93).
            if (!repository.deletePhotoAs(token)) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PHOTO_DELETE_FAILED)
            }
            mutableState.value = mutableState.value.copy(fieldPhotos = mutableState.value.fieldPhotos - token)
        }
        mutate(project.removeDefect(defectId))
    }

    /**
     * Starts the clock for the open project.
     *
     * [ProjectRecord.startSession] refuses a second open session, so a double tap on a wet,
     * cold screen cannot double the hours.
     */
    fun startWorkSession() {
        val project = mutableState.value.openProject ?: return
        val started = project.startSession(
            WorkSession(id = UUID.randomUUID().toString(), startEpochMs = System.currentTimeMillis()),
        )
        if (started !== project) mutate(started)
    }

    fun stopWorkSession() {
        val project = mutableState.value.openProject ?: return
        val stopped = project.stopSession()
        if (stopped !== project) mutate(stopped)
    }

    /**
     * Drops one work session - the correction for a clock that was stopped late, or not at all.
     *
     * The room, the runs and the photos stay; only the hours that are not true go. See
     * [ProjectRecord.removeSession].
     */
    fun deleteWorkSession(sessionId: String) {
        val project = mutableState.value.openProject ?: return
        val corrected = project.removeSession(sessionId)
        if (corrected !== project) mutate(corrected)
    }

    // ── Pricing ─────────────────────────────────────────────────────────────────────────
    fun updatePricing(hourlyRateCents: Int?, materialLumpSumCents: Long, onSaved: (ProjectRecord?) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        mutate(project.withPricing(hourlyRateCents, materialLumpSumCents), onSaved)
    }

    /**
     * Stores the factor a reference calibration produced, or refuses it.
     *
     * The window is decided in `:core` (`ReferenceCalibration.accepted`), not here: a screen that
     * could store a factor of 1.43 would put a document labelled *calibrated* over a measurement
     * nobody checked, and a second copy of `[0.85, 1.15]` in the view model is a second definition
     * of what counts as a measurement. Returns the project as it now stands, or `null` when no
     * project is open or the factor was refused.
     */
    fun setCalibration(scale: Double, onSaved: (ProjectRecord?) -> Unit = {}): ProjectRecord? {
        val project = mutableState.value.openProject ?: return null
        if (!ReferenceCalibration.accepted(scale)) return null
        val updated = project.copy(calibrationScale = scale)
        mutate(updated, onSaved)
        return updated
    }

    /**
     * Records the floor plane's height, for the AR session that measured it.
     *
     * Written by the AR screen as soon as the hit source sees a floor (see `FloorEvaluator`), and
     * only when it actually changes: the session re-reports the same plane on every frame, and a
     * centimetre of jitter around the same plane is the same floor. The *state* changes at once so
     * the HUD and the zones see the new floor on the next frame; the *file* is written once per
     * quiet window (`SaveCoalescer`), because a JSON write per ARCore frame is a disk benchmark
     * with a measurement attached (finding 219).
     *
     * The floor is stored per session (`sessionFloorHeights`) and mirrored into `floorHeightM` for
     * the readers that predate the map: ARCore's origin does not survive a session, so a floor is
     * only a reference for the nodes measured against the same one (finding 217).
     */
    fun setFloorHeight(
        sessionId: String,
        heightM: Float,
        onSaved: (ProjectRecord?) -> Unit = {},
    ): ProjectRecord? {
        val project = mutableState.value.openProject ?: return null
        if (!heightM.isFinite() || sessionId.isEmpty()) return null

        // The floor is recorded *per session*, and only for the session that measured it: a node
        // placed in this session has no meaning in the next one (finding 217), so the id travels
        // with the height instead of the app assuming the last floor applies.
        val known = project.sessionFloorHeights[sessionId]
        // Read into a local: `ProjectRecord` lives in another module, so the compiler will not
        // smart-cast its property after a null check and the comparison below would not compile.
        val stored = project.floorHeightM
        val unchanged = known != null &&
            kotlin.math.abs(known - heightM) < FLOOR_TOLERANCE_M &&
            stored != null &&
            kotlin.math.abs(stored - heightM) < FLOOR_TOLERANCE_M
        if (unchanged) return project

        val updated = withFloor(project, sessionId, heightM)
        // The state is updated *now* - the HUD and every zone read it on the next frame. Only the
        // file write waits for the burst to end: ARCore re-reports the same plane every frame, and
        // one JSON write per frame is a disk benchmark, not a measurement (finding 219).
        mutableState.value = mutableState.value.copy(
            openProject = updated,
            materialList = buildMaterialList(updated),
        )
        floorWrites.offer(FloorSample(sessionId, heightM))
        return updated
    }

    /**
     * States the room's floor-to-ceiling height - the number every wall area is made of.
     *
     * The wall screen used to build every wall at `DEFAULT_WALL_HEIGHT_M`, which is the height of
     * a *board*, chosen so a 2.50 m board covers a wall without a butt joint. That made a 2.80 m
     * room report 35.0 m² of wall where it has 39.2 m², four boards short, on every job with a
     * ceiling that is not the trade default (finding 227).
     *
     * `statedRoomHeight` owns what counts as a room height; a number it refuses is not stored, so a
     * typo cannot become a wall.
     */
    fun setWallHeight(heightM: Float, onSaved: (ProjectRecord?) -> Unit = {}): ProjectRecord? {
        val project = mutableState.value.openProject ?: return null
        val stated = statedRoomHeight(heightM) ?: return null
        if (stated == project.wallHeightM) return project
        val updated = project.copy(wallHeightM = stated)
        mutate(updated, onSaved)
        return updated
    }

    /** The project with one session's floor recorded, leaving the other sessions' floors alone. */
    private fun withFloor(project: ProjectRecord, sessionId: String, heightM: Float) = project.copy(
        floorHeightM = heightM,
        sessionFloorHeights = project.sessionFloorHeights + (sessionId to heightM),
    )

    /**
     * Writes the newest floor of a burst.
     *
     * Re-reads the open project instead of writing the copy the offer captured: the operator may
     * have walked into another room, added a route, or undone something while the window was
     * running, and this write must not resurrect the older document.
     */
    private suspend fun persistFloor(sample: FloorSample) {
        val project = mutableState.value.openProject ?: return
        val updated = withFloor(project, sample.sessionId, sample.heightM)
        val saved = repository.save(updated)
        if (!saved) {
            mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_SAVE_FAILED)
        }
    }

    /**
     * Records a door or a window on a wall of the room's ring.
     *
     * The offset is in metres along the wall, computed by `:core` from the tap
     * (`openingOffsetForTap`) - the screen knows where the finger landed, not how long the wall is
     * or how wide a door is. Everything here does is append the rectangle the plan will subtract
     * and redraw; the union subtraction itself stays in `wallSurface`, so two windows that overlap
     * take board off the wall once rather than twice.
     */
    fun addOpening(
        wallIndex: Int,
        kind: OpeningKind,
        offsetM: Float,
        onSaved: (ProjectRecord?) -> Unit = {},
    ): ProjectRecord? {
        val project = mutableState.value.openProject ?: return null
        if (wallIndex < 0 || !offsetM.isFinite() || offsetM < 0f) return null
        val opening = when (kind) {
            OpeningKind.WINDOW -> PlacedOpening.window(wallIndex, offsetM)
            // A passage is a door with no leaf; the trade dimensions of the reveal are the same.
            OpeningKind.DOOR, OpeningKind.PASSAGE -> PlacedOpening.door(wallIndex, offsetM)
        }
        val updated = project.copy(openings = project.openings + opening)
        mutate(updated, onSaved)
        return updated
    }

    /**
     * Removes the last opening placed on [wallIndex] - the only removal a tap-placed list needs.
     *
     * A room has four walls and a handful of openings; an operator who mis-tapped wants the last
     * one gone, not a numbered list to manage. Nothing is removed from another wall by mistake:
     * the index is part of the question.
     */
    fun removeLastOpening(wallIndex: Int, onSaved: (ProjectRecord?) -> Unit = {}): ProjectRecord? {
        val project = mutableState.value.openProject ?: return null
        val last = project.openings.indexOfLast { it.wallIndex == wallIndex }
        if (last < 0) return project
        val updated = project.copy(
            openings = project.openings.filterIndexed { index, _ -> index != last },
        )
        mutate(updated, onSaved)
        return updated
    }

    /** The rate this project bills at: its own override, or the firm's default. */
    fun hourlyRateCents(project: ProjectRecord): Int =
        project.hourlyRateCents ?: mutableState.value.firmHourlyRateCents

    /**
     * Attaches the photo of a diary entry or a defect.
     *
     * The token carries the kind (`<project>__diary_<id>`) so one code path serves both, and
     * the file name alone is enough to restore it from a backup.
     *
     * The copy is the one step in this view model that suspends *before* it reads the project
     * again, so it is also the one place where "the project" can move underneath it: the picker
     * hands back a picture, the operator taps into another room while five megabytes are being
     * copied, and the state this function started with is no longer what is on screen. The token
     * names its own room, so the answer is not "apply it to whatever is open" - it is to follow
     * the token, and if the room or the entry is gone by the time the copy lands, to remove the
     * file again instead of leaving it in private storage with nothing pointing at it.
     */
    fun attachFieldPhoto(token: String, uri: Uri, onDone: (Boolean) -> Unit = {}) {
        val entryId = PhotoTokens.entryOf(token) ?: return
        val ownerId = PhotoTokens.projectOf(token)
        viewModelScope.launch {
            // Both failure branches say so: the caller's callback was never wired, so a full
            // phone swallowed the photograph without a word (finding 72).
            if (!repository.savePhotoAs(token, uri)) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PHOTO_SAVE_FAILED)
                onDone(false)
                return@launch
            }
            val open = mutableState.value.openProject
            val stored = if (open?.id == ownerId) null else repository.load(ownerId)
            when (val target = fieldPhotoTarget(token, entryId, open, stored)) {
                is FieldPhotoTarget.OnScreen -> {
                    val thumbnail = repository.loadPhotoAs(token)
                    // `onDone` carries the *disk* answer, not the screen's: the row now shows the
                    // picture either way, and the caller uses this flag to decide whether the
                    // cache copy of the capture may go. Reporting `true` for a project that was
                    // never written would delete the only copy of a photo nothing points at
                    // (findings 72, 128).
                    mutate(target.project) { saved ->
                        val photos = if (thumbnail != null) {
                            mutableState.value.fieldPhotos + (token to thumbnail)
                        } else {
                            mutableState.value.fieldPhotos
                        }
                        mutableState.value = mutableState.value.copy(fieldPhotos = photos)
                        onDone(saved != null)
                    }
                }
                // Not the room the operator is looking at: write it where it belongs and let the
                // list reload. Applying it to the open room would have shown the picture in the
                // wrong room, and saving *that* project would have carried the entry's token into
                // a room that never had the entry.
                is FieldPhotoTarget.OffScreen -> {
                    // The file is already on disk; if its room cannot be rewritten the entry keeps
                    // no token and the copy is an orphan - reporting success here is exactly the
                    // "attached" for a file nothing points at that finding 58 removed.
                    val written = repository.save(target.project)
                    if (!written) {
                        mutableState.value = mutableState.value.copy(lastError = UiMessage.PHOTO_SAVE_FAILED)
                    }
                    refresh()
                    onDone(written)
                }
                FieldPhotoTarget.Gone -> {
                    // The room is gone, so the copy just made has to go too; if the phone refuses,
                    // the file stays as an orphan and the operator is told (finding 93).
                    if (!repository.deletePhotoAs(token)) {
                        mutableState.value = mutableState.value.copy(lastError = UiMessage.PHOTO_DELETE_FAILED)
                    }
                    onDone(false)
                }
            }
        }
    }

    private suspend fun loadFieldPhotos(project: ProjectRecord): Map<String, Bitmap> {
        // The order matters: the budget is small, so it is spent on the photos that carry the
        // most - an open defect before a resolved one, and both before the diary (finding 42).
        val tokens = FieldPhotos.ordered(project).take(FieldPhotos.SCREEN_BUDGET)
        if (tokens.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Bitmap>(tokens.size)
        tokens.forEach { token ->
            repository.loadPhotoAs(token)?.let { out[token] = it }
        }
        return out
    }

    /**
     * Deletes a project file this build cannot read, freeing the room it occupies.
     *
     * Without this the free plan could be locked for good: the damaged file takes the one slot
     * (finding 126), the button refuses to create (finding 149), a restore is granted no room -
     * and the file itself could not be reached from any screen, so the app's own sentence
     * "deleting it frees the slot - nothing is ever locked away" was untrue (finding 150).
     */
    fun deleteUnreadableFile(name: String) {
        viewModelScope.launch {
            // Asked, not assumed - a read-only folder keeps the file and the count (finding 93).
            if (!repository.deleteUnreadable(name)) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_DELETE_FAILED)
                return@launch
            }
            refresh()
        }
    }

    fun deleteProject(id: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            // Asked, not assumed: on a read-only or revoked folder the file is still there, and
            // the room reappears on the next refresh while the caller has already been told it was
            // deleted (finding 93).
            if (!repository.delete(id)) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_DELETE_FAILED)
                return@launch
            }
            if (preferences.lastProjectId() == id) preferences.setLastProjectId(null)
            if (mutableState.value.openProject?.id == id) {
                mutableState.value = mutableState.value.copy(openProject = null, materialList = null, photo = null)
            }
            refresh()
            onDeleted()
        }
    }

    /**
     * Persists the scan statistics (triangle count, extents, coverage) into the open project.
     * Called when a run is finished, so the protocol describes the room as it was scanned
     * rather than whatever the last frame happened to contain.
     */
    /**
     * Commits a scan-only walkthrough as one durable project write. No route is required: mesh
     * bounds, scan statistics and the floorplan sketch are first-class project assets.
     */
    fun finishCapture(
        summary: com.constructar.core.model.ScanSummary,
        plan: com.constructar.nativebridge.FloorplanPayload?,
    ) {
        val project = mutableState.value.openProject ?: return
        val sketch = plan?.let(RoomThumbnail::sketch)
        val withAssets = project.copy(scan = summary, sketch = sketch ?: project.sketch)
        val event = if (withAssets.sketch?.isDrawable == true) {
            ProjectWorkflowEvent.PerimeterClosed
        } else {
            ProjectWorkflowEvent.SpatialMapped
        }
        val updated = ProjectWorkflowEngine.reduce(withAssets, event)
        mutate(updated) { saved ->
            if (saved != null) {
                saved.sketch?.let(RoomThumbnail::svg)?.let { svg ->
                    viewModelScope.launch { repository.saveThumbnail(project.id, svg) }
                }
            }
        }
    }

    /**
     * Stores the room's outline, derived from the plan the AR screen took at commit time.
     *
     * The outline is stored even when the ring did **not** close: a doorway leaves it open, and an
     * open ring is not a picture and not a size - but it is the only record of the corners the scan
     * saw, and the only thing an operator could later close by hand (finding 203). What is *not*
     * stored is a wall nobody measured: an open ring keeps `Closure.Open` and the card keeps its
     * placeholder until a person says the gap is a wall.
     *
     * The SVG is written only for a drawable ring, and its refusal is not an error: a room under
     * 10 cm produces neither a picture nor a size.
     *
     * A failed save is reported like any other: a card that silently stays an icon is a room whose
     * picture the operator will look for and not find.
     */
    fun saveFloorplanThumbnail(plan: com.constructar.nativebridge.FloorplanPayload) {
        val project = mutableState.value.openProject ?: return
        val sketch = RoomThumbnail.sketch(plan) ?: return
        // The outline goes into the project file as well as the exported SVG: the card draws from
        // the JSON (Android has no SVG renderer and this app has no third-party libraries), and a
        // restored project keeps its picture without the file system having to keep a cache.
        val updated = ProjectWorkflowEngine.reduce(
            project.copy(sketch = sketch),
            ProjectWorkflowEvent.PerimeterClosed,
        )
        mutableState.value = mutableState.value.copy(openProject = updated)
        viewModelScope.launch {
            val written = repository.save(updated)
            if (!written) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_SAVE_FAILED)
            }
            val svg = RoomThumbnail.svg(sketch)
            if (svg != null) repository.saveThumbnail(project.id, svg)
            refresh()
        }
    }

    /**
     * Closes the open ring of a room by hand: the operator walked the gap and says it is a wall.
     *
     * This is the only way an open plan becomes a room, and it is deliberately a separate, explicit
     * action - nothing here guesses that a doorway should be boarded. The corners stay exactly as
     * the scan recorded them; the closing wall is a statement about the gap between the last and the
     * first, and the sketch records that it was drawn and not measured, so a protocol that prints
     * an accuracy never silently includes it as a measured wall.
     *
     * Idempotent by construction: a measured ring, or one already closed by hand, is left alone.
     */
    fun closeRoomByHand(projectId: String) {
        viewModelScope.launch {
            val project = repository.load(projectId) ?: return@launch
            val open = project.sketch?.closedByHand() ?: return@launch
            if (open.closure == project.sketch?.closure) return@launch
            val updated = ProjectWorkflowEngine.reduce(
                project.copy(sketch = open),
                ProjectWorkflowEvent.PerimeterClosed,
            )
            val written = repository.save(updated)
            if (!written) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_SAVE_FAILED)
            }
            val svg = RoomThumbnail.svg(open)
            if (svg != null) repository.saveThumbnail(projectId, svg)
            if (mutableState.value.openProject?.id == projectId) {
                mutableState.value = mutableState.value.copy(openProject = updated)
            }
            refresh()
        }
    }

    fun updateScanSummary(summary: com.constructar.core.model.ScanSummary) {
        val project = mutableState.value.openProject ?: return
        val updated = ProjectWorkflowEngine.reduce(
            project.copy(scan = summary),
            ProjectWorkflowEvent.SpatialMapped,
        )
        mutableState.value = mutableState.value.copy(openProject = updated)
        viewModelScope.launch {
            if (!repository.save(updated)) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_SAVE_FAILED)
            }
            refresh()
        }
    }

    /** Keeps the photo the camera app just wrote into our cache; one photo per project. */
    fun attachPhoto(uri: Uri, onDone: (Boolean) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        viewModelScope.launch {
            val saved = repository.savePhoto(project.id, uri)
            mutableState.value = mutableState.value.copy(
                photo = if (saved) repository.loadPhoto(project.id) else mutableState.value.photo,
            )
            onDone(saved)
        }
    }

    fun closeProject() {
        // Closing a room is the operator saying he is done with it: the next cold start goes to
        // the list, not back into this room.
        preferences.setLastProjectId(null)
        mutableState.value = mutableState.value.copy(
            openProject = null,
            materialList = null,
            photo = null,
            fieldPhotos = emptyMap(),
            resumeReady = false,
        )
    }

    /**
     * Sets the firm's waste factor and rebuilds the open project's material list immediately, so
     * the number on screen is the number that will be exported.
     */
    fun setWasteOverride(value: Float?) {
        settings.setWasteFactorOverride(value)
        val project = mutableState.value.openProject
        mutableState.value = mutableState.value.copy(
            wasteOverride = value,
            materialList = project?.let { buildMaterialList(it) },
        )
    }

    /**
     * Sets the firm's clip spacing and rebuilds the open project's material list immediately.
     *
     * The same shape as [setWasteOverride], and for the same reason: the number the operator just
     * changed has to be the number on the screen and in the export (finding 151).
     */
    fun setClipSpacingOverride(value: Float?) {
        settings.setClipSpacingOverride(value)
        val project = mutableState.value.openProject
        mutableState.value = mutableState.value.copy(
            clipSpacingOverride = value,
            materialList = project?.let { buildMaterialList(it) },
        )
    }

    /**
     * Sets the firm's hourly rate, the default every project bills at unless it overrides it.
     *
     * Re-emitting the state is what makes the open project's card and the protocol pick the new
     * number up without reopening the job.
     */
    fun setFirmHourlyRate(cents: Int?) {
        settings.setHourlyRateCents(cents)
        mutableState.value = mutableState.value.copy(
            firmHourlyRateCents = cents ?: Pricing.DEFAULT_HOURLY_RATE_CENTS,
        )
    }

    /** The one place a material list is built, so the screen, the CSV and the PDF cannot diverge. */
    private fun buildMaterialList(project: ProjectRecord): MaterialList =
        MaterialListBuilder.build(
            project = project,
            options = MaterialListOptions(
                wasteFactorOverride = mutableState.value.wasteOverride,
                clipSpacingMetersOverride = mutableState.value.clipSpacingOverride,
            ),
        )

    fun clearError() {
        mutableState.value = mutableState.value.copy(lastError = null)
    }

    fun repository(): ProjectRepository = repository

    /**
     * Restores a backup: projects first, then the photos that belong to them.
     *
     * A photo is attached to the project **this restore created**, never to one that was already
     * on the phone: a restore of last week's backup must not overwrite the picture taken since,
     * and it must not leave a copy without its photo either. When a project had to be re-keyed
     * because its id was taken (finding 40), the photo is re-homed onto the new id, so the copy
     * comes back complete and the original keeps its own picture. The open project is re-read
     * afterwards, so a restored photo shows up without leaving the screen.
     */
    fun importBackup(
        texts: List<String>,
        photos: List<Pair<String, Uri>> = emptyList(),
        onDone: (RestoreCounts) -> Unit = { _ -> },
    ) {
        viewModelScope.launch {
            // Restoring is a way to *create* projects, so it obeys the same plan as creating
            // them. Without this, a free installation could be filled by importing somebody
            // else's backup - and the free plan would mean nothing. The files that are held
            // back are not deleted or altered; they stay in the operator's zip and can be
            // imported after upgrading.
            // Counted through `ProjectSlots` for the same reason the create gate is: a room file
            // that cannot be read still occupies its slot, so a restore may not hand a free
            // installation a second room file (findings 126, 149).
            val room = ProjectSlots.remaining(
                entitlements = mutableState.value.entitlements,
                readable = mutableState.value.projects.size,
                unreadable = mutableState.value.unreadableProjects,
            )
            // The id each restored file was stored under, keyed by the id it carried: equal for a
            // new room, a fresh id when the old one was taken. A photo follows its room, never
            // the name it happened to have in the zip.
            val homes = HashMap<String, String>()
            val counts = restoreOutcome(
                slots = room,
                texts = texts,
                parse = { text -> repository.parseProject(text) != null },
            ) { text ->
                val result = repository.importProjectDetailed(text)
                if (result == null) {
                    false
                } else {
                    homes[result.originalId] = result.project.id
                    true
                }
            }
            // `<project id>` for the room's own photo, `<project id>__diary_<entry>` for an
            // entry photo: only a room this restore created gives the photo a home - and a copy
            // that this phone refuses is counted, because the card used to say "restored" while
            // the documentation was still missing (finding 92).
            val photoRestore = restorePhotos(photos, homes, repository::savePhotoAs)
            refresh()
            mutableState.value.openProject?.let { open ->
                val reloaded = repository.load(open.id)
                if (reloaded != null) {
                    mutableState.value = mutableState.value.copy(
                        openProject = reloaded,
                        materialList = buildMaterialList(reloaded),
                        photo = repository.loadPhoto(reloaded.id),
                        fieldPhotos = loadFieldPhotos(reloaded),
                    )
                }
            }
            onDone(counts.copy(photosFailed = photoRestore.failed))
        }
    }

    private fun applyWorkflow(projectId: String, events: List<ProjectWorkflowEvent>) {
        viewModelScope.launch {
            val current = mutableState.value.openProject?.takeIf { it.id == projectId }
                ?: repository.load(projectId)
                ?: return@launch
            val updated = events.fold(current) { project, event ->
                ProjectWorkflowEngine.reduce(project, event)
            }
            if (updated == current) return@launch
            if (!repository.save(updated)) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_SAVE_FAILED)
                return@launch
            }
            if (mutableState.value.openProject?.id == projectId) {
                mutableState.value = mutableState.value.copy(
                    openProject = updated,
                    materialList = buildMaterialList(updated),
                )
            }
            refresh()
        }
    }

    private fun mutate(updated: ProjectRecord, onSaved: (ProjectRecord?) -> Unit = {}) {
        val ticket = projectWriteGate.ticket()
        val reconciled = ProjectWorkflowEngine.reconcile(updated)
        mutableState.value = mutableState.value.copy(
            openProject = reconciled,
            materialList = buildMaterialList(reconciled),
        )
        viewModelScope.launch {
            val saved = projectWrites.withLock {
                // A superseded full-project snapshot must not overwrite the newer one; it is not
                // a storage failure because the newer snapshot already contains this mutation.
                if (!projectWriteGate.isNewest(ticket)) return@withLock true
                val written = repository.save(reconciled)
                written
            }
            if (!saved) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_SAVE_FAILED)
            }
            refresh()
            onSaved(if (saved) reconciled else null)
        }
    }
}

/** A floor within a centimetre of the stored one is the same floor: ARCore reports it per frame. */
private const val FLOOR_TOLERANCE_M = 0.01f


/**
 * Where an already-copied field photo belongs.
 *
 * Three answers, not two, because "the room is gone" and "the room is simply not on screen" have
 * opposite consequences: the first means the file has to be removed again, the second means it has
 * to be written to that room on disk. Everything this needs is a value, so the decision is decided
 * - and tested - without a picker, a content resolver or a phone.
 */
internal sealed interface FieldPhotoTarget {
    /** The room is open: apply it to the state the screen is showing. */
    data class OnScreen(val project: ProjectRecord) : FieldPhotoTarget

    /** The room exists but is not open: write it there without touching the open room. */
    data class OffScreen(val project: ProjectRecord) : FieldPhotoTarget

    /** The room or the entry it names no longer exists: the copy is an orphan and goes. */
    data object Gone : FieldPhotoTarget
}

internal fun fieldPhotoTarget(
    token: String,
    entryId: String,
    open: ProjectRecord?,
    stored: ProjectRecord?,
): FieldPhotoTarget {
    val ownerId = PhotoTokens.projectOf(token)
    val room = open?.takeIf { it.id == ownerId } ?: stored?.takeIf { it.id == ownerId }
        ?: return FieldPhotoTarget.Gone
    val updated = when (PhotoTokens.kindOf(token)) {
        PhotoKind.DIARY -> room.withDiaryPhoto(entryId, token)
        PhotoKind.DEFECT -> room.withDefectPhoto(entryId, token)
        // A room photo is not attached through this path; the site photo has its own.
        PhotoKind.ROOM -> return FieldPhotoTarget.Gone
    }
    // `with*Photo` maps over the entries, so an id that is not there comes back unchanged. That is
    // the second way a copy can be an orphan, and it must not be mistaken for success.
    val attached = when (PhotoTokens.kindOf(token)) {
        PhotoKind.DIARY -> updated.diary.any { it.id == entryId && it.photo == token }
        PhotoKind.DEFECT -> updated.defects.any { it.id == entryId && it.photo == token }
        PhotoKind.ROOM -> false
    }
    if (!attached) return FieldPhotoTarget.Gone
    return if (room.id == open?.id) {
        FieldPhotoTarget.OnScreen(updated)
    } else {
        FieldPhotoTarget.OffScreen(updated)
    }
}

/**
 * What a restore reports as imported and held back.
 *
 * The plan limit has to be counted in **rooms actually created**, not in files attempted. The
 * first version compared the file's position against the free slots, so a backup folder where the
 * picker happened to hand over a stray `.json` first used up the slot with a file that could not
 * be parsed: the operator was told one room was held back, and it was the only real room in the
 * folder. A failed import frees its slot again, which is what this loop does.
 *
 * Pure on purpose - the count is the part that can be wrong, and it can be tested without an
 * Android context.
 */
/**
 * What one restore attempt did, room by room and photo by photo.
 *
 * Public, not `internal`: it travels through `importBackup`'s callback into `BackupCard`, and
 * Kotlin refuses to expose an internal type from a public function - the compiler said so on CI
 * when finding 92 first landed.
 */
data class RestoreCounts(
    val imported: Int,
    /** Rooms the plan had no slot for: still in the zip, imported after upgrading. */
    val heldBack: Int,
    /** Files that are not a room at all: no upgrade will ever import them. */
    val unreadable: Int,
    /**
     * Rooms that were read fine but could not be stored on this phone.
     *
     * A third category, because the other two both describe the *file* and this one describes the
     * phone: a full or read-only storage. Counting it as [unreadable] told the operator "this file
     * is not a room" about a perfectly good room he had just backed up, and then pointed him at a
     * file that is fine while the room was gone (finding 86).
     */
    val notWritten: Int,
    /**
     * Photos that belonged to a restored room and that this phone could not write.
     *
     * The copy was attempted and refused - a full or read-only storage - so the file is still in
     * the operator's backup and the room in the app simply has no picture. The result of
     * `savePhotoAs` used to be thrown away: the restore announced the rooms, the site page
     * promised that restoring "brings the photos along", and the missing photograph was invisible
     * on the one screen that could have said so (finding 92).
     */
    val photosFailed: Int = 0,
)

internal inline fun restoreOutcome(
    slots: Int,
    texts: List<String>,
    parse: (String) -> Boolean,
    import: (String) -> Boolean,
): RestoreCounts {
    var imported = 0
    var heldBack = 0
    var unreadable = 0
    var notWritten = 0
    for (text in texts) {
        // Two questions, in this order, because they have different answers. "Is this a room at
        // all?" is answered by parsing, for every file in the selection - a file past the free
        // slots is still either a room waiting for Pro or a truncated file nobody can import, and
        // calling the second one "held back" is the sentence that makes a customer pay for an
        // upgrade that will import nothing. "Does the plan have a room left?" is the quota.
        if (!parse(text)) {
            unreadable++
            continue
        }
        if (imported >= slots) {
            heldBack++
            continue
        }
        // The file parsed, so a refusal here is the phone's answer, not the file's: a full disk, a
        // revoked folder, an unwritable file. Reported apart from `unreadable` (finding 86).
        if (import(text)) imported++ else notWritten++
    }
    return RestoreCounts(
        imported = imported,
        heldBack = heldBack,
        unreadable = unreadable,
        notWritten = notWritten,
    )
}

/** How a restore's photos went: copies that landed, and copies this phone refused. */
internal data class PhotoRestore(val restored: Int, val failed: Int)

/**
 * Copies every restored photo into its room, and counts what happened.
 *
 * A photo is matched to its room by the id in its token, never by the file name it had in the
 * backup folder: a room that was imported under a fresh id (its old one was taken) must take its
 * pictures with it. A photo whose room was held back - the plan had no slot for it - is neither
 * restored nor counted as a failure: it is still in the backup, waiting for the same upgrade the
 * room is waiting for.
 *
 * Kept as its own function, taking the copy as a lambda, so the three outcomes can be tested
 * without a content resolver, a file or a phone (finding 92).
 */
internal suspend fun <T> restorePhotos(
    photos: List<Pair<String, T>>,
    homes: Map<String, String>,
    save: suspend (token: String, photo: T) -> Boolean,
): PhotoRestore {
    var restored = 0
    var failed = 0
    for ((token, photo) in photos) {
        val home = homes[PhotoTokens.projectOf(token)] ?: continue
        if (save(PhotoTokens.withProject(token, home), photo)) restored++ else failed++
    }
    return PhotoRestore(restored = restored, failed = failed)
}
