/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import com.constructar.core.math.Vec3
import com.constructar.core.mesh.MeshData
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SketchPoint
import com.constructar.core.model.SurfaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CalibrationTransformTest {
    private val route = ConduitRoute(
        id = "r",
        label = "Route",
        spec = ConduitSpec.cable(16f),
        nodes = listOf(
            RouteNodeRecord("a", Vec3(1f, 2f, 3f), Vec3.Z_AXIS, SurfaceKind.WALL, HitSource.PLANE_POLYGON),
            RouteNodeRecord("b", Vec3(3f, 2f, 3f), Vec3.Z_AXIS, SurfaceKind.WALL, HitSource.PLANE_POLYGON),
        ),
        createdAtEpochMs = 0L,
    )

    @Test
    fun `every route point is multiplied once while persisted evidence stays raw`() {
        val transformed = CalibrationTransform.of(1.05).route(route)

        assertEquals(1.05f, transformed.nodes[0].position.x, 1e-5f)
        assertEquals(2.10f, transformed.nodes[0].position.y, 1e-5f)
        assertEquals(3.15f, transformed.nodes[0].position.z, 1e-5f)
        assertEquals(3.15f, transformed.nodes[1].position.x, 1e-5f)
        assertEquals(2.10f, transformed.polylineLengthMeters, 1e-5f)
        assertEquals(Vec3(1f, 2f, 3f), route.nodes[0].position)
        assertEquals(2f, route.polylineLengthMeters, 1e-5f)
    }

    @Test
    fun `sketch distances and mesh vertices use the identical factor`() {
        val transform = CalibrationTransform.of(1.10)
        val sketch = transform.sketch(FloorplanSketch(listOf(SketchPoint(1f, 2f), SketchPoint(3f, 4f))))
        assertEquals(1.10f, sketch.points[0].xMeters, 1e-5f)
        assertEquals(2.20f, sketch.points[0].zMeters, 1e-5f)
        assertEquals(1.10f, transform.distance(1f), 1e-5f)

        val mesh = MeshData.fromTriangles(
            positions = floatArrayOf(1f, 2f, 3f, 2f, 2f, 3f, 1f, 3f, 3f),
            normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
            indices = intArrayOf(0, 1, 2),
        )
        val vertex = transform.mesh(mesh).positionAt(0)
        assertEquals(1.10f, vertex.x, 1e-5f)
        assertEquals(2.20f, vertex.y, 1e-5f)
        assertEquals(3.30f, vertex.z, 1e-5f)
    }

    @Test
    fun `identity and malformed imported factors cannot alter or poison geometry`() {
        assertSame(route, CalibrationTransform.IDENTITY.route(route))
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { scale ->
            val transform = CalibrationTransform.of(scale)
            assertSame(route, transform.route(route))
            assertEquals(Vec3(1f, 2f, 3f), transform.point(Vec3(1f, 2f, 3f)))
        }
    }
}
