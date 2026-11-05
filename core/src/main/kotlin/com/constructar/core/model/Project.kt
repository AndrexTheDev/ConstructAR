/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import kotlinx.serialization.Serializable

/**
 * What the scan pipeline (TSDF in `:native-slam`) measured, attached to a project for the
 * protocol header.
 *
 * Deliberately two fields (finding 103). Everything here is written by
 * `ArMeasureController.scanSummary()` when a run is finished and read by something that leaves
 * the phone - the protocol's summary row and its partial-capture note, and the export screen.
 * The model used to carry nine more: a vertex count, a floor area, a ceiling height, a bounding
 * box, a voxel size, an accepted-loop-closure count, a pose-graph RMS and a derived volume. The
 * scan filled **none** of them, so they travelled into every project file and every backup as
 * zeros - a floor area of 0,00 m² and *0 accepted loop closures* reading as measurements the app
 * had taken - and the protocol drew a "Ceiling height" row that could never print because
 * nothing ever computed one. `ScanSummaryTest` pins this field list, so a new field has to
 * arrive with a producer and a reader.
 */
@Serializable
data class ScanSummary(
    /** Triangles the reconstructed mesh held when the run was finished. */
    val meshTriangleCount: Int = 0,
    /**
     * True when the TSDF block budget was spent during this scan (finding 100).
     *
     * A full volume refuses every new block, so the mesh freezes at its current extent while the
     * operator keeps walking: the capture is only part of the room. The HUD says so while the
     * scanner is on screen, and this field is what makes the warning survive the scan -
     * `ArMeasureController.scanSummary()` copies `ScanState.hitBlockBudget` here, the protocol
     * prints a note from it, and the export screen refuses to present a partial capture as a
     * whole room. Defaults to false so a project file written before this field existed still
     * loads (see `ScanSummaryTest`).
     */
    val scanLimited: Boolean = false,
)

/**
 * The on-device project: one room (or one zone) of one site.
 *
 * Everything here is plain data and `@Serializable`, so a project is a single JSON file
 * in app-private storage. No database, no network, no account.
 */
@Serializable
data class ProjectRecord(
    val id: String,
    val name: String,
    val siteName: String = "",
    val technician: String = "",
    val notes: String = "",
    /** Who the protocol is for; printed in the header block of the PDF. */
    val customerName: String = "",
    val customerAddress: String = "",
    val customerPhone: String = "",
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val routes: List<ConduitRoute> = emptyList(),
    val scan: ScanSummary? = null,
    /**
     * The room's outline as the home screen's card draws it, or `null` before a closed plan exists.
     *
     * Written when a run is committed, from the same plan that produces
     * `files/projects/<id>/thumbnail.svg` (see `FloorplanSketch` for why the phone draws from the
     * JSON rather than from the SVG). Defaults to `null`, so a project file written before this
     * field existed loads and behaves exactly as it did - its card shows the icon.
     */
    val sketch: FloorplanSketch? = null,
    /** What happened on site, newest first. */
    val diary: List<DiaryEntry> = emptyList(),
    /** Open and closed defects, newest first. */
    val defects: List<DefectItem> = emptyList(),
    /** Working time, oldest first; the last one may still be running. */
    val sessions: List<WorkSession> = emptyList(),
    /** Hourly rate for this job in cents; `null` = the firm's default from settings. */
    val hourlyRateCents: Int? = null,
    /** Lump sum for material, in cents. The app never invents a material price. */
    val materialLumpSumCents: Long = 0L,
    /**
     * What every measured metre is multiplied by, from a reference calibration (1.0 = none).
     *
     * ARCore's metre is metric on its own; this factor is the operator's check of it against an
     * object of known size - an ISO/IEC 7810 bank card or a folding rule at the 1 m / 2 m mark
     * (`ReferenceCalibration`). It is stored on the project rather than baked into the node
     * positions for one reason: a factor that has been multiplied into the geometry cannot be
     * corrected by a better one tomorrow, and the nodes are also what the 3D overlay and the
     * anchors are built from.
     *
     * Defaults to `1.0`, so a project written before this field existed loads and reports exactly
     * what it always did. Only the *reported* metres scale: this number never reaches
     * `route.nodes`, never reaches the mesh, and never reaches the AR HUD while measuring - the
     * operator measures what the phone sees, and the paperwork says what the calibration says.
     */
    val calibrationScale: Double = 1.0,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    /** Defects still to be fixed — the number the project screen shows in red. */
    val openDefectCount: Int
        get() = defects.count { !it.resolved }

    /**
     * Everything recorded on site, including a session that has not been stopped yet: raw working
     * time, milliseconds, nothing rounded - the truth about how long he was there.
     *
     * Not the number anything printed or billed uses: the protocol's table, its labour line and the
     * room screen's preview all take `WorkTime.billableMillis`, which adds up the rounded session
     * rows (finding 81). Anything that shows a duration to a human belongs there.
     */
    fun totalWorkMillis(nowMs: Long = System.currentTimeMillis()): Long = WorkTime.totalMillis(sessions, nowMs)
    /**
     * The metres this project reports: the runs added up, then corrected by [calibrationScale].
     *
     * One number for every reader - the room screen, the protocol table, the bill of materials -
     * so a calibrated project cannot print 40.0 m in one place and 41.2 m in another. With the
     * default factor of 1.0 this is exactly the sum of the runs, which is what it always was.
     */
    val totalLengthMeters: Float
        get() = (routes.sumOf { it.polylineLengthMeters.toDouble() } * calibrationScale).toFloat()

    /** One run's length as it is reported: measured by the phone, corrected by the calibration. */
    fun calibratedMeters(route: ConduitRoute): Float =
        (route.polylineLengthMeters.toDouble() * calibrationScale).toFloat()

    /** True when a reference calibration has been applied to this project. */
    val isCalibrated: Boolean
        get() = calibrationScale != 1.0

    fun routeById(routeId: String): ConduitRoute? = routes.firstOrNull { it.id == routeId }

    fun upsertRoute(route: ConduitRoute): ProjectRecord = copy(
        routes = if (routes.any { it.id == route.id }) {
            routes.map { if (it.id == route.id) route else it }
        } else {
            routes + route
        },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    fun removeRoute(routeId: String): ProjectRecord = copy(
        routes = routes.filterNot { it.id == routeId },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    // ── Site file ───────────────────────────────────────────────────────────────────────
    // Every mutation stamps `updatedAtEpochMs`, because the project list is sorted by it and
    // a diary entry is as much "the job moved" as a new run is.

    /** Sets the two money fields the operator actually types. Both are optional. */
    fun withPricing(hourlyRateCents: Int?, materialLumpSumCents: Long): ProjectRecord = copy(
        hourlyRateCents = hourlyRateCents?.takeIf { it > 0 },
        materialLumpSumCents = materialLumpSumCents.coerceAtLeast(0L),
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    fun withCustomer(name: String, address: String, phone: String): ProjectRecord = copy(
        customerName = name.trim(),
        customerAddress = address.trim(),
        customerPhone = phone.trim(),
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    fun addDiaryEntry(entry: DiaryEntry): ProjectRecord = copy(
        diary = listOf(entry) + diary.filterNot { it.id == entry.id },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    /** Attaches (or clears) the documentation photo of one diary entry. */
    fun withDiaryPhoto(entryId: String, photoToken: String?): ProjectRecord = copy(
        diary = diary.map { if (it.id == entryId) it.copy(photo = photoToken) else it },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    fun withDefectPhoto(defectId: String, photoToken: String?): ProjectRecord = copy(
        defects = defects.map { if (it.id == defectId) it.copy(photo = photoToken) else it },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    fun removeDiaryEntry(entryId: String): ProjectRecord = copy(
        diary = diary.filterNot { it.id == entryId },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    fun addDefect(defect: DefectItem): ProjectRecord = copy(
        defects = listOf(defect) + defects.filterNot { it.id == defect.id },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    fun toggleDefect(defectId: String): ProjectRecord = copy(
        defects = defects.map { if (it.id == defectId) it.copy(resolved = !it.resolved) else it },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    fun removeDefect(defectId: String): ProjectRecord = copy(
        defects = defects.filterNot { it.id == defectId },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    /**
     * Starts the clock, unless one is already running.
     *
     * A second tap on "start" must not create a second open session: two open sessions would
     * double the hours, and the operator would only notice on the invoice.
     */
    fun startSession(session: WorkSession): ProjectRecord = if (WorkTime.isRunning(sessions)) {
        this
    } else {
        copy(
            sessions = sessions + session,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    /** Stops every open session at [nowMs]; the last one is the only one that can be open. */
    fun stopSession(nowMs: Long = System.currentTimeMillis()): ProjectRecord = if (!WorkTime.isRunning(sessions)) {
        this
    } else {
        copy(
            sessions = sessions.map {
                // Never before the start. A phone whose clock stepped backwards while the session
                // ran (an NTP correction after a dead spot, a manual change, an RTC that lost
                // time) would otherwise print 09:00-08:40 in a document the customer signs, and
                // an office has to reject that. The duration is no less true for it: a session
                // whose end is not after its start already counts as zero minutes in `WorkTime`.
                it.copy(endEpochMs = it.endEpochMs ?: maxOf(nowMs, it.startEpochMs))
            },
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    /**
     * Drops one work session.
     *
     * Finding 44: a stopped session could not be corrected at all, and a running one could only
     * be stopped *now*. Whoever came back the next morning to a clock he forgot to stop had one
     * option left - delete the room and measure it again - because the 16 hours were already on
     * the invoice line and in the protocol. Removing the session is the correction that costs the
     * least: the room, the runs and the photos stay, and the price follows the times that are
     * actually true.
     *
     * Removing the open session is deliberate and allowed: it is how a forgotten timer is
     * cancelled without pretending the night was worked.
     */
    fun removeSession(sessionId: String): ProjectRecord =
        if (sessions.none { it.id == sessionId }) {
            this
        } else {
            copy(
                sessions = sessions.filterNot { it.id == sessionId },
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }

    companion object {
        /** Bump when the JSON shape changes; migrations live in `ProjectMigrations`. */
        const val SCHEMA_VERSION: Int = 1

        fun empty(id: String, name: String, nowMs: Long = System.currentTimeMillis()) = ProjectRecord(
            id = id,
            name = name,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs,
        )
    }
}
