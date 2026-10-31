/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.geo

import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineMetricsTest {

    private fun node(x: Float, y: Float, z: Float, source: HitSource = HitSource.PLANE_POLYGON) =
        RouteNodeRecord(
            id = "n",
            position = Vec3(x, y, z),
            normal = Vec3(0f, 0f, 1f),
            surface = SurfaceKind.WALL,
            hitSource = source,
            confidence = source.reliability,
        )

    @Test
    fun `straight run length is the euclidean sum`() {
        val points = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(1f, 0f, 2f))
        assertEquals(3f, PolylineMetrics.length(points), 1e-5f)
    }

    @Test
    fun `closed loop adds the closing segment`() {
        val points = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(1f, 0f, 1f), Vec3(0f, 0f, 1f))
        assertEquals(4f, PolylineMetrics.length(points, closed = true), 1e-5f)
    }

    @Test
    fun `clips couplings and bend counts follow trade practice`() {
        val nodes = listOf(
            node(0f, 0f, 0f),
            node(1.8f, 0f, 0f), // 1.8 m
            node(1.8f, 0f, 1.8f), // 1.8 m, 90° corner
            node(3.6f, 0f, 1.8f), // 1.8 m, 90° corner
        )
        val metrics = PolylineMetrics.measure(nodes, ConduitSpec.cable(16f))
        assertEquals(5.4f, metrics.totalMeters, 1e-4f)
        assertEquals(2, metrics.elbow90Count)
        // 5.4 m at 0.6 m clip spacing ⇒ ceil(9) + 1 = 10 clips.
        assertEquals(10, metrics.clipCount)
        assertEquals(0, metrics.couplingCount)
        assertEquals(5.4f * 1.07f, metrics.withWasteMeters, 1e-3f)
        assertEquals(3, metrics.segments.size)
    }

    @Test
    fun `bend classification tolerates ten degrees of tapping error`() {
        val points = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(1f, 0f, 1.05f))
        val bends = PolylineMetrics.bendAngles(points, closed = false)
        assertEquals(1, bends.size)
        assertEquals(BendKind.BEND_90, bends[0].kind)
    }

    @Test
    fun `low confidence nodes are counted for review`() {
        val nodes = listOf(
            node(0f, 0f, 0f),
            node(1f, 0f, 0f, HitSource.FEATURE_POINT),
            node(2f, 0f, 0f),
        )
        val metrics = PolylineMetrics.measure(nodes, ConduitSpec.pipe(20f))
        assertTrue(metrics.lowConfidenceNodes >= 1)
    }

    @Test
    fun `minimum bend radius scales with the conduit`() {
        val cable = PolylineMetrics.defaultBendRadius(ConduitSpec.cable(16f))
        val pipe = PolylineMetrics.defaultBendRadius(ConduitSpec.pipe(32f))
        assertEquals(0.128f, cable, 1e-4f) // 8 × 0.016 m
        assertEquals(0.16f, pipe, 1e-4f) // 5 × 0.032 m
    }
}
