/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.screenshots

import com.constructar.app.data.ProjectRepository
import com.constructar.core.bom.MaterialList
import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.model.ConduitPresets
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.DefectItem
import com.constructar.core.model.DefectSeverity
import com.constructar.core.model.DiaryEntry
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.ScanSummary
import com.constructar.core.model.SurfaceKind
import com.constructar.core.model.WorkSession
import com.constructar.core.math.Vec3

/**
 * One realistic room, reused by every screenshot in the matrix.
 *
 * The point of the matrix is to judge the *layout* of a screen in six languages, so the data has
 * to look like a real job: two runs on a wall, a duct across the ceiling, some hours on the
 * clock, two diary lines and a defect that is still open. Deliberately hard-coded instead of
 * generated — a screenshot that changes every run is not comparable.
 */
object ScreenshotFixtures {

    /** 2026-03-04T09:15:00Z — a weekday morning, so the timestamps in the shots look sane. */
    const val NOW: Long = 1_772_616_900_000L

    private const val HOUR: Long = 3_600_000L

    private fun node(id: String, x: Float, y: Float, z: Float, surface: SurfaceKind) = RouteNodeRecord(
        id = id,
        position = Vec3(x, y, z),
        normal = when (surface) {
            SurfaceKind.FLOOR -> Vec3(0f, 1f, 0f)
            SurfaceKind.CEILING -> Vec3(0f, -1f, 0f)
            else -> Vec3(0f, 0f, 1f)
        },
        surface = surface,
        hitSource = HitSource.PLANE_POLYGON,
        confidence = 0.93f,
    )

    private fun wallRun(id: String, label: String, presetIndex: Int, from: Float, to: Float, y: Float) =
        ConduitRoute(
            id = id,
            label = label,
            spec = ConduitPresets.all[presetIndex],
            nodes = listOf(
                node("$id-1", from, y, -1.2f, SurfaceKind.WALL),
                node("$id-2", (from + to) / 2f, y, -1.2f, SurfaceKind.WALL),
                node("$id-3", to, y, -1.2f, SurfaceKind.WALL),
            ),
            createdAtEpochMs = NOW - 2 * HOUR,
        )

    fun project(): ProjectRecord = ProjectRecord(
        id = "demo-room",
        name = "Küche Erdgeschoss",
        siteName = "Neubau Seilerweg 14",
        technician = "A. Rodríguez",
        notes = "Decke abgehängt, Zuleitung über die Nordwand.",
        customerName = "Hausverwaltung Molina",
        customerAddress = "Calle Feria 12, 41003 Sevilla",
        customerPhone = "+34 954 000 000",
        createdAtEpochMs = NOW - 26 * HOUR,
        updatedAtEpochMs = NOW - 40 * 60_000L,
        routes = listOf(
            wallRun("run-1", "Kabel Ost", 1, 0f, 3.4f, 1.15f),
            wallRun("run-2", "Kabel West", 0, 0f, 1.8f, 2.05f),
            ConduitRoute(
                id = "run-3",
                label = "Kanal Decke",
                spec = ConduitPresets.all[4],
                nodes = listOf(
                    node("run-3-1", 0f, 2.6f, -1.2f, SurfaceKind.CEILING),
                    node("run-3-2", 2.2f, 2.6f, -1.2f, SurfaceKind.CEILING),
                ),
                createdAtEpochMs = NOW - 90 * 60_000L,
            ),
        ),
        scan = ScanSummary(
            meshVertexCount = 48_210,
            meshTriangleCount = 96_384,
            floorAreaSquareMeters = 18.4f,
            ceilingHeightMeters = 2.61f,
            extentMin = listOf(-3.1f, 0f, -3.4f),
            extentMax = listOf(3.2f, 2.61f, 2.9f),
            voxelSizeMeters = 0.03f,
            loopClosures = 4,
        ),
        diary = listOf(
            DiaryEntry(
                id = "d-2",
                createdAtEpochMs = NOW - 40 * 60_000L,
                text = "Rohre verlegt, Druckprobe bestanden.",
            ),
            DiaryEntry(
                id = "d-1",
                createdAtEpochMs = NOW - 5 * HOUR,
                text = "Baustrom angeschlossen, Schlüssel beim Bauleiter abgeholt.",
            ),
        ),
        defects = listOf(
            DefectItem(
                id = "m-2",
                createdAtEpochMs = NOW - 3 * HOUR,
                title = "Bohrloch zu nah an der Wasserleitung",
                location = "Nordwand, 1,80 m",
                severity = DefectSeverity.HIGH,
            ),
            DefectItem(
                id = "m-1",
                createdAtEpochMs = NOW - 6 * HOUR,
                title = "Putz uneben, Halter sitzt nicht",
                location = "Ostwand, 2,05 m",
                severity = DefectSeverity.LOW,
                resolved = true,
            ),
        ),
        sessions = listOf(
            WorkSession(id = "s-1", startEpochMs = NOW - 9 * HOUR, endEpochMs = NOW - 6 * HOUR),
            WorkSession(id = "s-2", startEpochMs = NOW - 3 * HOUR, endEpochMs = NOW - 30 * 60_000L),
            WorkSession(id = "s-3", startEpochMs = NOW - 25 * 60_000L),
        ),
        hourlyRateCents = 4_800,
        materialLumpSumCents = 68_400L,
    )

    fun materialList(): MaterialList = MaterialListBuilder.build(project(), nowMs = NOW)

    /** The home list: this room plus a second one, so the card layout is visible. */
    fun summaries(): List<ProjectRepository.ProjectSummary> = listOf(
        ProjectRepository.ProjectSummary(
            id = "demo-room",
            name = "Küche Erdgeschoss",
            siteName = "Neubau Seilerweg 14",
            routeCount = 3,
            totalLengthMeters = 14.6f,
            updatedAtEpochMs = NOW - 40 * 60_000L,
            openDefects = 1,
            workRunning = true,
        ),
        ProjectRepository.ProjectSummary(
            id = "demo-bath",
            name = "Bad Obergeschoss",
            siteName = "Neubau Seilerweg 14",
            routeCount = 2,
            totalLengthMeters = 6.1f,
            updatedAtEpochMs = NOW - 2 * 24 * HOUR,
            openDefects = 0,
            workRunning = false,
        ),
    )
}
