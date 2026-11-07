/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.workflow

import com.constructar.core.math.Aabb
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.ScanSummary
import com.constructar.core.model.SketchPoint
import com.constructar.core.model.SurfaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectWorkflowEngineTest {

    private val base = ProjectRecord(id = "room", name = "Room", createdAtEpochMs = 1L, updatedAtEpochMs = 1L)
    private val scan = ScanSummary(
        meshTriangleCount = 12,
        bounds = Aabb(Vec3(0f, 0f, 0f), Vec3(4f, 2.6f, 3f)),
    )
    private val sketch = FloorplanSketch(
        points = listOf(
            SketchPoint(0f, 0f), SketchPoint(4f, 0f), SketchPoint(4f, 3f), SketchPoint(0f, 3f),
        ),
    )
    private val route = ConduitRoute(
        id = "run",
        label = "run",
        spec = ConduitSpec.cable(10f),
        createdAtEpochMs = 2L,
        nodes = listOf(node("a", Vec3(0f, 1f, 0f)), node("b", Vec3(1f, 1f, 0f))),
    )

    @Test
    fun `only the seven declared forward edges are allowed`() {
        val states = ProjectWorkflowState.entries
        states.forEachIndexed { index, from ->
            assertTrue(ProjectWorkflowEngine.canTransition(from, from), "$from must be idempotent")
            if (index + 1 < states.size) {
                assertTrue(ProjectWorkflowEngine.canTransition(from, states[index + 1]))
                assertFalse(ProjectWorkflowEngine.canTransition(states[index + 1], from))
            }
        }
        assertFalse(ProjectWorkflowEngine.canTransition(ProjectWorkflowState.IDLE, ProjectWorkflowState.EXPORTED))
    }

    @Test
    fun `scan only persistence reaches spatial mapped without a route`() {
        val active = ProjectWorkflowEngine.reduce(base, ProjectWorkflowEvent.ProjectActivated)
        val mapped = ProjectWorkflowEngine.reduce(active.copy(scan = scan), ProjectWorkflowEvent.SpatialMapped)

        assertEquals(ProjectWorkflowState.SPATIAL_MAPPED, mapped.workflow)
        assertTrue(mapped.routes.isEmpty())
        assertEquals(scan.bounds, mapped.scan?.bounds)
        assertEquals(WorkflowAction.MEASURE_ROUTE, ProjectWorkflowEngine.nextAction(mapped.workflow))
    }

    @Test
    fun `a floorplan alone does not manufacture a measured route`() {
        val active = ProjectWorkflowEngine.reduce(base, ProjectWorkflowEvent.ProjectActivated)
        val mapped = ProjectWorkflowEngine.reduce(
            active.copy(scan = scan, sketch = sketch),
            ProjectWorkflowEvent.PerimeterClosed,
        )
        assertEquals(ProjectWorkflowState.SPATIAL_MAPPED, mapped.workflow)
    }

    @Test
    fun `full signed workflow reaches exported in order`() {
        var project = ProjectWorkflowEngine.reduce(base, ProjectWorkflowEvent.ProjectActivated)
        project = ProjectWorkflowEngine.reduce(project.copy(scan = scan), ProjectWorkflowEvent.SpatialMapped)
        project = ProjectWorkflowEngine.reduce(project.copy(routes = listOf(route)), ProjectWorkflowEvent.RouteMeasured)
        assertEquals(ProjectWorkflowState.TRASSE_MEASURED, project.workflow)
        project = ProjectWorkflowEngine.reduce(project.copy(sketch = sketch), ProjectWorkflowEvent.PerimeterClosed)
        assertEquals(ProjectWorkflowState.PERIMETER_CLOSED, project.workflow)
        project = ProjectWorkflowEngine.reduce(project, ProjectWorkflowEvent.BomCalculated(10L))
        assertEquals(ProjectWorkflowState.BOM_CALCULATED, project.workflow)
        project = ProjectWorkflowEngine.reduce(project, ProjectWorkflowEvent.FieldBookCompleted(11L))
        project = ProjectWorkflowEngine.reduce(project, ProjectWorkflowEvent.SignatureCaptured(12L))
        assertEquals(ProjectWorkflowState.DOSSIER_COMPLETE, project.workflow)
        project = ProjectWorkflowEngine.reduce(project, ProjectWorkflowEvent.ExportCompleted(13L))
        assertEquals(ProjectWorkflowState.EXPORTED, project.workflow)
    }

    @Test
    fun `early export is recorded but never claims dossier completion`() {
        val active = ProjectWorkflowEngine.reduce(base, ProjectWorkflowEvent.ProjectActivated)
        val mapped = ProjectWorkflowEngine.reduce(active.copy(scan = scan), ProjectWorkflowEvent.SpatialMapped)
        val exported = ProjectWorkflowEngine.reduce(mapped, ProjectWorkflowEvent.ExportCompleted(20L))
        assertEquals(20L, exported.lastExportAtEpochMs)
        assertEquals(ProjectWorkflowState.SPATIAL_MAPPED, exported.workflow)
    }

    @Test
    fun `reconcile regresses a state whose assets were removed`() {
        val impossible = base.copy(
            workflow = ProjectWorkflowState.EXPORTED,
            scan = scan,
            routes = listOf(route),
            sketch = sketch,
            bomCalculatedAtEpochMs = 1L,
            fieldBookCompletedAtEpochMs = 2L,
            signatureCapturedAtEpochMs = 3L,
            lastExportAtEpochMs = 4L,
        ).copy(routes = emptyList())
        assertEquals(ProjectWorkflowState.SPATIAL_MAPPED, ProjectWorkflowEngine.reconcile(impossible).workflow)
    }

    private fun node(id: String, position: Vec3) = RouteNodeRecord(
        id = id,
        position = position,
        normal = Vec3(0f, 0f, 1f),
        surface = SurfaceKind.WALL,
        hitSource = HitSource.PLANE_POLYGON,
    )
}
