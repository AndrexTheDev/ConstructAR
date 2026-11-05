/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.ProjectRecord
import com.constructar.core.math.Vec3
import com.constructar.core.wall.DEFAULT_WALL_HEIGHT_M
import com.constructar.core.wall.DrywallEstimate
import com.constructar.core.wall.DrywallSpec
import com.constructar.core.wall.InstallationPlan
import com.constructar.core.wall.MeasuredWall
import com.constructar.core.wall.WallSurfaceReport
import com.constructar.core.wall.classifyInstallations
import com.constructar.core.wall.estimateDrywall
import com.constructar.core.wall.wallSurface

/**
 * The room's walls, its drywall take-off and its installation zones - assembled once, for a screen.
 *
 * Every number here comes from `:core` and none is computed in a composable: the wall areas from
 * `wallSurface`, the purchase from `estimateDrywall`, the DIN 18015 zones from
 * `classifyInstallations`. What this object decides is *what the room is* and *which metres are
 * reported*, and both are decisions with a wrong answer that looks right:
 *
 *  - **The walls are the room's own ring**, the one the home card draws and the protocol prints.
 *    The closing wall is a wall: a 4 x 3 m room is 14 m of wall, not 11 m, and the take-off is a
 *    purchase order - 3 m of missing wall is a board and a half. An open ring is not a room yet
 *    (the card offers to close it by hand) and produces no walls at all.
 *  - **The calibration factor is applied to the metres, not to the nodes.** It is the operator's
 *    check of ARCore's metre, and the app applies it exactly once, at the reporting layer - the
 *    same rule the bill of materials follows. Areas follow automatically: a factor of 1.1 turns
 *    4.00 m into 4.40 m and 12.0 m² into 14.5 m², because a square metre is two metres.
 *  - **A mounting height is measured from the floor, and only a tracked floor may say where that
 *    is** (`ProjectRecord.floorHeightM`, written by the AR session). Without it the zones are not
 *    assigned at all: ARCore's origin is wherever the phone happened to start, and a socket
 *    printed at 1.4 m is worse than no socket at all.
 *
 * `null` fields are answers, not failures: no ring means no walls, no floor means no zones.
 */
data class WallPlan(
    val walls: List<MeasuredWall>,
    val surface: WallSurfaceReport,
    val drywall: DrywallEstimate,
    /** Zone assignments for the measured points, or `null` when no floor has been seen. */
    val installations: InstallationPlan?,
    /** The floor height the zones were computed against, when there was one. */
    val floorHeightM: Float?,
    /**
     * How many doors and windows the take-off was told about - zero, until the app has a source
     * for them. The screen prints that fact rather than a silent `0.00 m² deducted`.
     */
    val openingsRecorded: Int,
    /** How many measured points were classified (all routes, including a recovered run). */
    val pointCount: Int,
) {
    /** True when there was a closed room to measure. */
    val valid: Boolean get() = walls.isNotEmpty()
}

object WallPlanBuilder {

    /**
     * Builds the plan for [project].
     *
     * `spec` is the drywall specification: the defaults are the German trade's (2.50 x 1.25 m
     * board, 62.5 cm stud centres so every board edge lands on metal, 10 % waste) and a screen
     * that shows the numbers has to show them too - a take-off without its assumptions is a number
     * to argue with.
     */
    fun build(project: ProjectRecord, spec: DrywallSpec = DrywallSpec()): WallPlan {
        val scale = project.calibrationScale.toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f
        val walls = wallsOf(project.sketch, metresScale = scale)
        val surface = wallSurface(walls)
        val drywall = estimateDrywall(surface, spec)
        val points = floorRelativePoints(project, scale)
        val floor = project.floorHeightM?.takeIf { it.isFinite() }
        val installations = if (floor == null || points.isEmpty()) {
            null
        } else {
            classifyInstallations(points, ceilingHeightM = DEFAULT_WALL_HEIGHT_M)
        }
        return WallPlan(
            walls = walls,
            surface = surface,
            drywall = drywall,
            installations = installations,
            floorHeightM = floor,
            openingsRecorded = 0,
            pointCount = points.size,
        )
    }

    /**
     * The room's ring as walls, one per edge, closing the loop.
     *
     * The coordinates are scaled here and nowhere else; a wall that collapses to a point under
     * that scaling (a duplicate corner, a zero-length trace) is dropped rather than reported as
     * 0.00 m of board.
     */
    fun wallsOf(
        sketch: FloorplanSketch?,
        metresScale: Float = 1f,
        heightM: Float = DEFAULT_WALL_HEIGHT_M,
    ): List<MeasuredWall> {
        if (sketch == null || !sketch.isDrawable) return emptyList()
        if (!metresScale.isFinite() || metresScale <= 0f) return emptyList()
        if (!heightM.isFinite() || heightM <= 0f) return emptyList()
        val points = sketch.points
        if (points.size < 3) return emptyList()
        val walls = ArrayList<MeasuredWall>(points.size)
        for (index in points.indices) {
            val from = points[index]
            val to = points[(index + 1) % points.size]
            val wall = MeasuredWall(
                startXM = from.xMeters * metresScale,
                startZM = from.zMeters * metresScale,
                endXM = to.xMeters * metresScale,
                endZM = to.zMeters * metresScale,
                heightM = heightM,
            )
            if (wall.lengthM > 0f) walls += wall
        }
        return walls
    }

    /**
     * Every measured point, expressed as a height above the finished floor.
     *
     * A recovered run is included on purpose: its *coordinates* are relative to a session that no
     * longer exists, which keeps it out of the 3D export and the AR preview, but its heights were
     * measured like every other height and the zone count is about the installation, not about
     * where the phone stood.
     */
    private fun floorRelativePoints(project: ProjectRecord, metresScale: Float): List<Vec3> {
        val floor = project.floorHeightM?.takeIf { it.isFinite() } ?: return emptyList()
        val points = ArrayList<Vec3>()
        for (route in project.routes) {
            for (node in route.nodes) {
                val position = node.position
                if (!position.isReal()) continue
                points += Vec3(
                    x = position.x * metresScale,
                    y = (position.y - floor) * metresScale,
                    z = position.z * metresScale,
                )
            }
        }
        return points
    }

    /** Non-finite coordinates are not a measurement, and a zone is not assigned to one. */
    private fun Vec3.isReal(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}
