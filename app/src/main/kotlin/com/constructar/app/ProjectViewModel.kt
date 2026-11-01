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
import com.constructar.app.data.ProjectRepository
import com.constructar.app.data.SiteSettings
import com.constructar.app.data.UiPreferences
import com.constructar.core.bom.MaterialList
import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.bom.MaterialListOptions
import com.constructar.core.licence.Entitlements
import com.constructar.core.licence.LicenceCheck
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.DefectItem
import com.constructar.core.model.DefectSeverity
import com.constructar.core.model.DiaryEntry
import com.constructar.core.model.PhotoKind
import com.constructar.core.model.PhotoTokens
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.WorkSession
import com.constructar.core.price.Pricing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /** The free plan's single room is taken. The UI answers with the paywall, never a crash. */
    PROJECT_LIMIT_REACHED,
}

class ProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val entitlements = EntitlementStore(application)
    private val preferences = UiPreferences(application)
    private val settings = SiteSettings(application)

    data class UiState(
        val loading: Boolean = true,
        val projects: List<ProjectRepository.ProjectSummary> = emptyList(),
        val openProject: ProjectRecord? = null,
        val materialList: MaterialList? = null,
        val lastError: UiMessage? = null,
        val entitlements: Entitlements = Entitlements.FREE,
        val deviceTag: String = "",
        val trialUsed: Boolean = false,
        val trialDaysLeft: Long = 0L,
        /** True while the bundled licence key is the demo key — surfaced in a debug build only. */
        val demoSigningKey: Boolean = false,
        /** The device clock is behind the last day this install has seen. */
        val clockRolledBack: Boolean = false,
        /** The installation could not be confirmed (re-signed APK or an attached debugger). */
        val integrityFailed: Boolean = false,
        val onboardingDone: Boolean = false,
        /** Firm-wide waste factor, `null` = each conduit family's default. */
        val wasteOverride: Float? = null,
        /** Documentation photo of the open project, decoded for screen and protocol. */
        val photo: Bitmap? = null,
        /** Firm-wide hourly rate, used when a project does not override it. */
        val firmHourlyRateCents: Int = Pricing.DEFAULT_HOURLY_RATE_CENTS,
        /** Decoded thumbnails of the open project's entry photos, by token. */
        val fieldPhotos: Map<String, Bitmap> = emptyMap(),
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

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
            demoSigningKey = LicencePublicKey.isDemoKey,
            onboardingDone = preferences.onboardingDone(),
            wasteOverride = settings.wasteFactorOverride(),
            firmHourlyRateCents = settings.hourlyRateCents() ?: Pricing.DEFAULT_HOURLY_RATE_CENTS,
        )
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projects = repository.list()
            mutableState.value = mutableState.value.copy(loading = false, projects = projects)
        }
    }

    /** Re-derives licence, trial and device state. Called on resume: that is how expiry works. */
    fun refreshEntitlements() {
        entitlements.refresh()
        mutableState.value = mutableState.value.copy(
            entitlements = entitlements.entitlements.value,
            deviceTag = entitlements.deviceTag,
            trialUsed = entitlements.trialUsed(),
            trialDaysLeft = entitlements.trialDaysLeft(),
            demoSigningKey = LicencePublicKey.isDemoKey,
            clockRolledBack = entitlements.diagnostics.value.clockRolledBack,
            integrityFailed = entitlements.diagnostics.value.integrityFailed,
        )
    }

    fun activateLicence(text: String): LicenceCheck {
        val check = entitlements.activate(text)
        refreshEntitlements()
        return check
    }

    fun startTrial() {
        entitlements.startTrial()
        refreshEntitlements()
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
            if (!current.entitlements.allowsAnotherProject(current.projects.size)) {
                mutableState.value = current.copy(lastError = UiMessage.PROJECT_LIMIT_REACHED)
                return@launch
            }
            // A blank name becomes the operator's own language's word for "room", not English.
            val fallback = getApplication<Application>().getString(R.string.home_default_room_name)
            val project = repository.create(name, siteName, fallbackName = fallback)
            mutableState.value = mutableState.value.copy(openProject = project, lastError = null)
            refresh()
            onCreated(project)
        }
    }

    fun openProject(id: String, onOpened: (ProjectRecord?) -> Unit = {}) {
        viewModelScope.launch {
            val project = repository.load(id)
            if (project == null) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_OPEN_FAILED)
                onOpened(null)
                return@launch
            }
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
            repository.deletePhotoAs(token)
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
            repository.deletePhotoAs(token)
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

    // ── Pricing ─────────────────────────────────────────────────────────────────────────
    fun updatePricing(hourlyRateCents: Int?, materialLumpSumCents: Long, onSaved: (ProjectRecord?) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        mutate(project.withPricing(hourlyRateCents, materialLumpSumCents), onSaved)
    }

    /** The rate this project bills at: its own override, or the firm's default. */
    fun hourlyRateCents(project: ProjectRecord): Int =
        project.hourlyRateCents ?: mutableState.value.firmHourlyRateCents

    /**
     * Attaches the photo of a diary entry or a defect.
     *
     * The token carries the kind (`<project>__diary_<id>`) so one code path serves both, and
     * the file name alone is enough to restore it from a backup.
     */
    fun attachFieldPhoto(token: String, uri: Uri, onDone: (Boolean) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        viewModelScope.launch {
            val saved = repository.savePhotoAs(token, uri)
            if (!saved) {
                onDone(false)
                return@launch
            }
            val entryId = PhotoTokens.entryOf(token)
            val updated = when (PhotoTokens.kindOf(token)) {
                PhotoKind.DIARY -> entryId?.let { project.withDiaryPhoto(it, token) } ?: project
                PhotoKind.DEFECT -> entryId?.let { project.withDefectPhoto(it, token) } ?: project
                PhotoKind.ROOM -> project
            }
            val thumbnail = repository.loadPhotoAs(token)
            mutate(updated) { _ ->
                val photos = if (thumbnail != null) {
                    mutableState.value.fieldPhotos + (token to thumbnail)
                } else {
                    mutableState.value.fieldPhotos
                }
                mutableState.value = mutableState.value.copy(fieldPhotos = photos)
                onDone(true)
            }
        }
    }

    private suspend fun loadFieldPhotos(project: ProjectRecord): Map<String, Bitmap> {
        val tokens = buildList {
            project.diary.forEach { entry -> entry.photo?.let { add(it) } }
            project.defects.forEach { defect -> defect.photo?.let { add(it) } }
        }.take(MAX_FIELD_PHOTOS)
        if (tokens.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Bitmap>(tokens.size)
        tokens.forEach { token ->
            repository.loadPhotoAs(token)?.let { out[token] = it }
        }
        return out
    }

    fun deleteProject(id: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.delete(id)
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
    fun updateScanSummary(summary: com.constructar.core.model.ScanSummary) {
        val project = mutableState.value.openProject ?: return
        val updated = project.copy(scan = summary)
        mutableState.value = mutableState.value.copy(openProject = updated)
        viewModelScope.launch {
            repository.updateScanSummary(updated.id, summary)
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
        mutableState.value = mutableState.value.copy(
            openProject = null,
            materialList = null,
            photo = null,
            fieldPhotos = emptyMap(),
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
            options = MaterialListOptions(wasteFactorOverride = mutableState.value.wasteOverride),
        )

    fun clearError() {
        mutableState.value = mutableState.value.copy(lastError = null)
    }

    fun repository(): ProjectRepository = repository

    /**
     * Restores a backup: projects first, then the photos that belong to them.
     *
     * Photos are matched by file name (which is the project id) and only attached when that
     * project exists on this phone, so a restore never leaves an orphan image or, worse, puts a
     * photo on the wrong room. The open project is re-read afterwards, so a restored photo shows
     * up without leaving the screen.
     */
    fun importBackup(
        texts: List<String>,
        photos: List<Pair<String, Uri>> = emptyList(),
        onDone: (imported: Int, heldBack: Int) -> Unit = { _, _ -> },
    ) {
        viewModelScope.launch {
            // Restoring is a way to *create* projects, so it obeys the same plan as creating
            // them. Without this, a free installation could be filled by importing somebody
            // else's backup - and the free plan would mean nothing. The files that are held
            // back are not deleted or altered; they stay in the operator's zip and can be
            // imported after upgrading.
            val room = (mutableState.value.entitlements.maxProjects - mutableState.value.projects.size)
                .coerceAtLeast(0)
            val (imported, heldBack) = restoreOutcome(room, texts) { text ->
                repository.importProject(text) != null
            }
            photos.forEach { (token, uri) ->
                // `<project id>` for the room's own photo, `<project id>__diary_<entry>` for an
                // entry photo: the room decides whether the photo has a home on this phone.
                val projectId = PhotoTokens.projectOf(token)
                if (repository.hasProject(projectId)) repository.savePhotoAs(token, uri)
            }
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
            onDone(imported, heldBack)
        }
    }

    private companion object {
        /** Enough documentation for a protocol appendix, few enough to keep the heap flat. */
        const val MAX_FIELD_PHOTOS = 12
    }

    private fun mutate(updated: ProjectRecord, onSaved: (ProjectRecord?) -> Unit = {}) {
        mutableState.value = mutableState.value.copy(
            openProject = updated,
            materialList = buildMaterialList(updated),
        )
        viewModelScope.launch {
            val saved = repository.save(updated)
            if (!saved) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_SAVE_FAILED)
            }
            refresh()
            onSaved(if (saved) updated else null)
        }
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
internal inline fun restoreOutcome(
    slots: Int,
    texts: List<String>,
    import: (String) -> Boolean,
): Pair<Int, Int> {
    var imported = 0
    var heldBack = 0
    for (text in texts) {
        if (imported >= slots) {
            heldBack++
            continue
        }
        if (import(text)) imported++ else heldBack++
    }
    return imported to heldBack
}
