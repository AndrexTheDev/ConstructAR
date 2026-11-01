/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export

import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * What the bundle contains - and, more importantly, what it does **not** pretend to contain.
 *
 * `build` documents that it never throws for a missing mesh, it just omits it. That promise is
 * easy to state and easy to break in a refactor, and it decides what the export screen says to
 * the operator: the screen infers "nothing to write" from an empty artifact list, so an empty
 * list has to mean exactly "there was nothing to write", never "I gave up quietly".
 */
class ExportServiceTest {

    private val service = ExportService(clock = { 0L })

    private fun emptyProject() = ProjectRecord.empty("p1", "Baustelle Nord", 0L)

    private fun measuredProject(): ProjectRecord {
        val spec = ConduitSpec.cable(16f, "Cable Ø16")
        val route = ConduitRoute(
            id = "r1",
            label = "Flur",
            spec = spec,
            nodes = listOf(
                RouteNodeRecord("n1", Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON),
                RouteNodeRecord("n2", Vec3(2.5f, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON),
            ),
            createdAtEpochMs = 0L,
        )
        return ProjectRecord.empty("p2", "Neubau", 0L).copy(routes = listOf(route))
    }

    private fun request(project: ProjectRecord) = ExportService.Request(
        project = project,
        materialList = MaterialListBuilder.build(project, nowMs = 0L),
        routeMeshes = ExportService.routeMeshes(project),
        appVersion = "test",
    )

    @Test
    fun `a project with no runs still exports its paper, and no model files`() {
        val project = emptyProject()
        val artifacts = service.build(request(project))

        assertEquals(listOf("csv", "pdf"), artifacts.map { it.fileName.substringAfterLast('.') }.sorted())
        // The CSV must actually write: a document that exists only as a file name is not a
        // document. (The PDF writer needs a real `PdfDocument`, so its bytes are covered where
        // one exists - `PdfTextTest` for the layout, the screenshot matrix for the page.)
        val csv = artifacts.single { it.fileName.endsWith(".csv") }
        val sink = ByteArrayOutputStream()
        csv.write(sink)
        assertTrue("the csv wrote nothing", sink.size() > 0)
        val text = String(sink.toByteArray(), Charsets.UTF_8)
        // The production CSV carries a UTF-8 BOM for Excel, so the header is not at index 0.
        assertTrue(text.contains("Project;"))
    }

    @Test
    fun `mesh formats are omitted, not faked, when there is no geometry`() {
        val project = emptyProject()
        val artifacts = service.build(request(project).copy(includePdf = false, includeCsv = false))

        assertTrue(artifacts.isEmpty())
    }

    @Test
    fun `a measured run produces every requested format`() {
        val project = measuredProject()
        val artifacts = service.build(request(project))

        val kinds = artifacts.map { it.fileName.substringAfterLast('.') }.sorted()
        assertEquals(listOf("csv", "glb", "pdf", "ply"), kinds)
        // Four formats, four distinct files in one folder: a collision would silently overwrite.
        assertEquals(artifacts.size, artifacts.map { it.fileName }.distinct().size)
    }

    @Test
    fun `the order list is a file of its own, next to the full bill of materials`() {
        val project = measuredProject()
        val artifacts = service.build(request(project).copy(includeOrderList = true, includeGlb = false, includePly = false))

        val names = artifacts.map { it.fileName }
        // Two CSVs, two names: `-order` is the wholesaler's one-line-per-article list, and it must
        // not collide with - or replace - the full list the operator keeps.
        assertEquals(listOf("csv", "csv", "pdf"), artifacts.map { it.fileName.substringAfterLast('.') }.sorted())
        assertTrue(names.toString(), names.any { it.endsWith("-order.csv") })
        assertEquals(artifacts.size, names.distinct().size)

        val order = artifacts.single { it.fileName.endsWith("-order.csv") }
        val sink = ByteArrayOutputStream()
        order.write(sink)
        val text = String(sink.toByteArray(), Charsets.UTF_8)
        assertTrue(text, text.contains("Article;Order length (m);"))
        assertFalse("the order list carries no per-run detail", text.contains("Run;"))
    }
}
