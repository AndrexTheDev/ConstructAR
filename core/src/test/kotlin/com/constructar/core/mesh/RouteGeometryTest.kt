/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.mesh

import com.constructar.core.geo.PolylineMetrics
import com.constructar.core.math.Aabb
import com.constructar.core.math.Mat4
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import com.constructar.core.bom.MaterialListBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {

    private fun wallNode(id: String, x: Float, y: Float) = RouteNodeRecord(
        id = id,
        position = Vec3(x, y, 0f),
        normal = Vec3(0f, 0f, 1f),
        surface = SurfaceKind.WALL,
        hitSource = HitSource.PLANE_POLYGON,
        confidence = 1f,
    )

    private fun route(spec: ConduitSpec, nodes: List<RouteNodeRecord>): ConduitRoute = ConduitRoute(
        id = "route-1",
        label = "Living room east wall",
        spec = spec,
        nodes = nodes,
        createdAtEpochMs = 0L,
    )

    @Test
    fun `cable geometry is lifted off the wall by its radius`() {
        val spec = ConduitSpec.cable(16f) // r = 8 mm
        val result = RouteGeometry.build(route(spec, listOf(wallNode("a", 0f, 0f), wallNode("b", 1f, 0f))))

        // Measured length uses the tapped surface points, not the offset centreline.
        assertEquals(1f, result.lengthMeters, 1e-4f)
        val bounds = result.mesh.bounds()!!
        // Flush mounting: the *centreline* sits one radius (8 mm) off the plaster, so the tube
        // touches the wall at z = 0 and its crown reaches one diameter (16 mm) out.
        assertEquals(0f, bounds.min.z, 1e-4f)
        assertEquals(0.016f, bounds.max.z, 1e-4f)
        assertTrue(result.bendRadiusCompliant)
    }

    @Test
    fun `duct geometry keeps its back face on the plaster`() {
        val spec = ConduitSpec.duct(60f, 40f) // half height 0.02
        val result = RouteGeometry.build(route(spec, listOf(wallNode("a", 0f, 0f), wallNode("b", 1f, 0f))))
        val bounds = result.mesh.bounds()!!
        assertEquals(0f, bounds.min.z, 1e-3f)
        assertEquals(0.04f, bounds.max.z, 1e-3f)
        // Width runs along the wall (Y).
        assertEquals(0.03f, bounds.max.y, 1e-3f)
    }

    @Test
    fun `cornered run stays within a millimetre of the raw length`() {
        val spec = ConduitSpec.pipe(20f)
        val nodes = listOf(
            wallNode("a", 0f, 0f),
            wallNode("b", 2f, 0f),
            wallNode("c", 2f, 1.5f),
        )
        val result = RouteGeometry.build(route(spec, nodes))
        val raw = PolylineMetrics.length(nodes.map { it.position })
        assertEquals(3.5f, raw, 1e-4f)
        // The fillet replaces a sharp corner with an arc: length differs only marginally.
        assertTrue("fillet length ${result.lengthMeters}", kotlin.math.abs(result.lengthMeters - raw) < 0.05f)
        assertEquals(1, result.path.cornerCount)
    }

    @Test
    fun `preview geometry follows a live reticle point`() {
        val spec = ConduitSpec.cable(10f)
        val nodes = listOf(wallNode("a", 0f, 0f))
        val preview = RouteGeometry.buildPreview(nodes, wallNode("live", 0.8f, 0.3f), spec)
        assertNotNull(preview)
        assertTrue(preview!!.mesh.vertexCount > 0)
        assertEquals(spec, preview.spec)
    }

    @Test
    fun `mesh transform moves geometry to world space`() {
        val spec = ConduitSpec.cable(10f)
        val result = RouteGeometry.build(route(spec, listOf(wallNode("a", 0f, 0f), wallNode("b", 1f, 0f))))
        val matrix = Mat4.fromRotationTranslation(Quat.IDENTITY, Vec3(10f, 0f, 0f))
        val moved = result.mesh.transformed(matrix)
        val before = result.mesh.bounds()!!
        val after = moved.bounds()!!
        assertEquals(before.min.x + 10f, after.min.x, 1e-4f)
    }

    @Test
    fun `bounds helper rejects empty input`() {
        assertEquals(null, Aabb.of(emptyList()))
    }

    @Test
    fun `material list aggregates runs of the same conduit`() {
        val spec = ConduitSpec.cable(16f)
        val project = ProjectRecord.empty("p", "Flat 12").copy(
            routes = listOf(
                route(spec, listOf(wallNode("a", 0f, 0f), wallNode("b", 2f, 0f))),
                route(spec, listOf(wallNode("c", 0f, 1f), wallNode("d", 3f, 1f))),
            ),
        )
        val list = MaterialListBuilder.build(project)
        assertEquals(1, list.lines.size)
        assertEquals(2, list.lines[0].runCount)
        assertEquals(5f, list.lines[0].totalMeters, 1e-4f)
        assertFalse(list.isEmpty)
        assertEquals(5f, list.metersFor(com.constructar.core.model.ConduitKind.CABLE), 1e-4f)
    }
}
