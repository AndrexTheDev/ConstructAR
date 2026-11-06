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
 * Deliberately three fields (findings 103, 251). Everything here is written by
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
    /**
     * The loop closures this scan's operator confirmed, or `null` when there were none.
     *
     * `null` rather than a zero-valued record on purpose. This model used to carry an "accepted
     * loop closures" count among nine fields nothing filled, and *0 accepted loop closures* in a
     * project the app had written read as a measurement - a room that was checked and needed no
     * folding (finding 103). The difference matters more now than it did then: since finding 245
     * a closure *can* be confirmed, so a zero would be ambiguous between "nothing was folded" and
     * "the operator never asked". A missing value says which.
     *
     * Written by `ArMeasureController.scanSummary()` from `PoseGraphStatus`, read by the
     * protocol's closure note and by the export screen (finding 250's numbers, persisted).
     */
    val closures: ScanClosure? = null,
)

/**
 * What the pose graph's confirmed loop closures did to one scan (finding 251).
 *
 * The numbers the HUD shows after the dialog is gone (`PoseGraphStatus.closures` and
 * `lastCorrection`) did not survive the run: a project file, and the protocol written from it,
 * said nothing about the trajectory having been folded. That is the assumption the whole geometry
 * rests on, and the one thing a customer reading the protocol would never learn - the same rule as
 * finding 217, one level up: an inference may not be presented as a measurement, and here the
 * inference *corrects* the measurements.
 */
@Serializable
data class ScanClosure(
    /** Confirmations in this scan. Every one added an edge to the graph, converged or not. */
    val closures: Int = 0,
    /** Nodes the last *converged* closure moved, and the nodes it compared (findings 246, 249). */
    val movedNodes: Int = 0,
    val nodes: Int = 0,
    /** How far the last converged closure moved the worst node, in metres. */
    val maxShiftMetres: Float = 0f,
    /**
     * Whether the last closure's optimiser converged (finding 252).
     *
     * `false` means the numbers above are not "nothing moved" but "there is no result": the
     * solver hit its iteration limit and left the poses halfway (finding 247). Defaults to `true`,
     * because a file written before this field can only describe a converged run - there was no
     * other kind of record then.
     */
    val converged: Boolean = true,
) {

    /**
     * Whether these numbers could have come from the optimiser at all (finding 253).
     *
     * A project file is not a trusted input: it is copied out as a backup, restored on another
     * phone and readable as text in between, and `ProjectRepository` refuses a file only on its
     * `schemaVersion`. So `movedNodes = 42, nodes = 10` or a NaN shift can reach this record and,
     * from here, a protocol the customer signs. The rule is the arithmetic of the report itself:
     * no negative counts, no more moved nodes than compared ones, a finite non-negative distance,
     * and - the coherence finding 252 established - no numbers at all for a run whose solver
     * stopped.
     */
    val isPlausible: Boolean
        get() = closures >= 0 &&
            nodes >= 0 &&
            movedNodes >= 0 &&
            movedNodes <= nodes &&
            maxShiftMetres.isFinite() &&
            maxShiftMetres >= 0f &&
            (converged || (nodes == 0 && movedNodes == 0 && maxShiftMetres == 0f))
}

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
    /**
     * The floor plane's height in the AR session's own coordinates, or `null` when none was seen.
     *
     * ARCore's world origin is wherever the session started - usually the phone at chest height -
     * so a node's `y` is *not* a height above the finished floor. Anything that talks about
     * mounting heights (DIN 18015 sockets at 30 cm, switches at 105 cm) needs the floor as a
     * reference, and the only honest source of it is a horizontal plane the session actually
     * tracked: the app records the plane's height here (see `FloorReference`) instead of assuming
     * the floor is at zero, which would put a socket at 1.4 m on every job.
     *
     * `null` is not a failure: it means the installation zones cannot be assigned yet, and the
     * screen says so rather than printing heights it cannot vouch for.
     */
    val floorHeightM: Float? = null,
    /**
     * The finished floor's height per AR session, in that session's own coordinates.
     *
     * The map exists because a world origin does not survive the process: a floor measured in
     * today's session says nothing about a node measured yesterday (see
     * `RouteNodeRecord.sessionId`). A mounting height is only computable where the node's session
     * and the floor's session are the same one, and `:core`'s `relativeNodes` refuses every other
     * combination rather than subtracting two numbers from different coordinate systems
     * (finding 217).
     *
     * [floorHeightM] stays as the *most recent* session's floor, because it is what an older file
     * carries and what the AR screen still reports; a project measured before this map existed has
     * one entry worth of information and is read through the legacy rule in `relativeNodes`.
     */
    val sessionFloorHeights: Map<String, Float> = emptyMap(),
    /**
     * The room's floor-to-ceiling height in metres, as the operator stated it, or `null`.
     *
     * Every wall area, board, stud and track on the wall screen is the wall's length times its
     * height, and until this field existed the height was `DEFAULT_WALL_HEIGHT_M` - the height of
     * the *board*, 2.50 m, chosen by the estimator so a board fits a wall without a butt joint. A
     * 2.80 m room therefore lost 0.30 m of every wall-metre: 35.0 m² printed where the room is
     * 39.2 m², four boards short, on every job with a ceiling that is not the trade default
     * (finding 227).
     *
     * The operator states it on the wall screen; `statedRoomHeight` refuses values outside
     * 2.00..4.50 m, and `null` means "nobody said", which the screen prints rather than hiding.
     * Deliberately not derived from the measured points: a run is drawn at socket height, so the
     * tallest point in a room is not its ceiling.
     */
    val wallHeightM: Float? = null,
    /**
     * Doors, windows and passages the operator placed on the room's walls.
     *
     * Without them the drywall take-off deducts nothing: a door is 1.78 m² of wall that does not
     * get boarded, and a 35 m² room with one door over-orders by more than half a board - on
     * every job, forever, in the direction that costs the fitter money (finding 218). Empty for
     * every project written before the field existed, which reads correctly: no opening recorded
     * means nothing deducted, and the screen says so instead of printing a silent zero.
     */
    val openings: List<com.constructar.core.wall.PlacedOpening> = emptyList(),
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
        get() = reportedMeters(routes.sumOf { it.polylineLengthMeters.toDouble() }.toFloat())

    /**
     * One measured length as it is reported: the phone's rows, the AR screen's running total, the
     * protocol's table and its summary, the bill of materials (finding 277).
     *
     * This was two multiplications - here and in [calibratedMeters] - and every surface picked
     * whichever one it happened to find. The project screen's rows and the protocol's runs table
     * printed the raw `polylineLengthMeters` while the total *above them* was corrected, so a
     * calibrated project's list added up to 12.80 m under a 13.44 m sum, and the page the customer
     * signs carried the same 64 cm tear between its table and its summary. A list that does not add
     * up to its own total is worse than no list: it is a number the operator can see is wrong and
     * cannot explain.
     *
     * With one function there is no "which metres" decision left for a screen to get wrong, and the
     * rows add up by construction.
     */
    fun reportedMeters(measuredMeters: Float): Float =
        (measuredMeters.toDouble() * calibrationScale).toFloat()

    /** One run's length as it is reported: measured by the phone, corrected by the calibration. */
    fun calibratedMeters(route: ConduitRoute): Float = reportedMeters(route.polylineLengthMeters)

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
