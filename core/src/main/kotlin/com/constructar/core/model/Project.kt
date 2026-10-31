/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.Aabb
import kotlinx.serialization.Serializable

/**
 * Statistics produced by the scan pipeline (TSDF in `:native-slam`) and attached to a
 * project for the protocol header and the BIM export metadata.
 */
@Serializable
data class ScanSummary(
    val meshVertexCount: Int = 0,
    val meshTriangleCount: Int = 0,
    /** Horizon-normalised floor area in m² — "how much wall/ceiling we actually saw". */
    val floorAreaSquareMeters: Float = 0f,
    /** Free ceiling height derived from the floor/ceiling plane pair, metres. */
    val ceilingHeightMeters: Float = 0f,
    /** Bounding box of the reconstructed volume, metres (origin = AR world origin). */
    val extentMin: List<Float> = emptyList(),
    val extentMax: List<Float> = emptyList(),
    val voxelSizeMeters: Float = 0f,
    /** Loop closures accepted by the SLAM back-end during this scan. */
    val loopClosures: Int = 0,
    /** Final RMS residual of the pose-graph optimisation, metres. */
    val poseGraphRmsMeters: Float = 0f,
    /** Fraction of the depth buffer that passed the confidence filter, 0..1. */
    val depthCoverage: Float = 0f,
) {
    val volumeCubicMeters: Float
        get() = if (extentMin.size == 3 && extentMax.size == 3) {
            val dx = extentMax[0] - extentMin[0]
            val dy = extentMax[1] - extentMin[1]
            val dz = extentMax[2] - extentMin[2]
            (dx * dy * dz).coerceAtLeast(0f)
        } else {
            0f
        }

    companion object {
        fun fromBounds(bounds: Aabb?, voxelSizeMeters: Float): ScanSummary = if (bounds == null) {
            ScanSummary(voxelSizeMeters = voxelSizeMeters)
        } else {
            ScanSummary(
                extentMin = listOf(bounds.min.x, bounds.min.y, bounds.min.z),
                extentMax = listOf(bounds.max.x, bounds.max.y, bounds.max.z),
                voxelSizeMeters = voxelSizeMeters,
            )
        }
    }
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
    /** What happened on site, newest first. */
    val diary: List<DiaryEntry> = emptyList(),
    /** Open and closed defects, newest first. */
    val defects: List<DefectItem> = emptyList(),
    /** Working time, oldest first; the last one may still be running. */
    val sessions: List<WorkSession> = emptyList(),
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    /** Defects still to be fixed — the number the project screen shows in red. */
    val openDefectCount: Int
        get() = defects.count { !it.resolved }

    /** Everything recorded on site, including a session that has not been stopped yet. */
    fun totalWorkMillis(nowMs: Long = System.currentTimeMillis()): Long = WorkTime.totalMillis(sessions, nowMs)
    val totalLengthMeters: Float
        get() = routes.sumOf { it.polylineLengthMeters.toDouble() }.toFloat()

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
            sessions = sessions.map { it.copy(endEpochMs = it.endEpochMs ?: nowMs) },
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
