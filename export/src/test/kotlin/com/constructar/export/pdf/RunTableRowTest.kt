/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import com.constructar.core.bom.MaterialList
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The runs table of the protocol, and the summary row it has to agree with.
 *
 * The page printed the raw measurement in every row and a calibrated total in the summary above it:
 * on a project corrected by 5 %, a table of 3.00 m and 2.00 m stood under "Total length 5.25 m", in
 * the document the customer signs, while the CSV beside it already printed 3.15 and 2.10
 * (finding 277). A list that does not add up to its own total is worse than no list - it is a number
 * the reader can see is wrong and cannot explain.
 *
 * `runTableRow` is out of the drawing code for the same reason `summaryRows` is: what a row prints
 * is a contract a test can hold without a canvas.
 */
class RunTableRowTest {

    private val labels = PdfLabels()

    private fun route(id: String, lengthMeters: Float) = ConduitRoute(
        id = id,
        label = "CABLE 20 mm #$id",
        spec = ConduitSpec.cable(10f),
        nodes = listOf(
            RouteNodeRecord(
                id = "$id-1",
                position = Vec3(0f, 1.2f, 0f),
                normal = Vec3(0f, 0f, 1f),
                surface = SurfaceKind.WALL,
                hitSource = HitSource.PLANE_POLYGON,
                confidence = 0.95f,
            ),
            RouteNodeRecord(
                id = "$id-2",
                position = Vec3(lengthMeters, 1.2f, 0f),
                normal = Vec3(0f, 0f, 1f),
                surface = SurfaceKind.WALL,
                hitSource = HitSource.PLANE_POLYGON,
                confidence = 0.95f,
            ),
        ),
        createdAtEpochMs = 1_000L,
    )

    private fun project(scale: Double) = ProjectRecord(
        id = "p1",
        name = "Bad Nord",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        routes = listOf(route("1", 3f), route("2", 2f)),
        calibrationScale = scale,
    )

    private fun materialList() = MaterialList(
        projectId = "p1",
        projectName = "Bad Nord",
        lines = emptyList(),
        generatedAtEpochMs = 0L,
    )

    @Test
    fun `a calibrated row prints the metres the summary row is the sum of`() {
        val project = project(scale = 1.05)

        val rows = project.routes.map { runTableRow(project, it, labels) }

        assertEquals("the first run is corrected, not measured", "3.15", rows[0][2])
        assertEquals("the second run is corrected, not measured", "2.10", rows[1][2])
    }

    @Test
    fun `the table adds up to the total length row above it`() {
        val project = project(scale = 1.05)

        val printed = project.routes.sumOf { runTableRow(project, it, labels)[2].toDouble() }
        val total = summaryRows(project, materialList(), labels)
            .first { it.first == labels.labelTotalLength }
            .second
            .removeSuffix(" m")
            .toDouble()

        assertEquals("a page whose table does not add up is a page nobody can check", total, printed, 0.005)
    }

    @Test
    fun `an uncalibrated project prints the measured metres unchanged`() {
        val project = project(scale = 1.0)

        val rows = project.routes.map { runTableRow(project, it, labels) }

        assertEquals("3.00", rows[0][2])
        assertEquals("the run's own name is the first column, as the CSV prints it", "CABLE 20 mm #1", rows[0][0])
    }
}
