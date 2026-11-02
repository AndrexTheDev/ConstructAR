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
    fun `a closed ring counts the shared end clip once`() {
        // A 1.5 m square: 4.5 m as an open run, 6 m once the ring is closed.
        val nodes = listOf(
            node(0f, 0f, 0f),
            node(1.5f, 0f, 0f),
            node(1.5f, 0f, 1.5f),
            node(0f, 0f, 1.5f),
        )
        val open = PolylineMetrics.measure(nodes, ConduitSpec.cable(16f))
        val ring = PolylineMetrics.measure(nodes, ConduitSpec.cable(16f), closed = true)

        assertEquals(4.5f, open.totalMeters, 1e-4f)
        assertEquals(6f, ring.totalMeters, 1e-4f)
        // Open: ceil(4.5 / 0.6) + 1 = 9 — clipped at both ends.
        assertEquals(9, open.clipCount)
        // Closed: ceil(6 / 0.6) = 10 — the start clip is the end clip, so no extra one.
        assertEquals(10, ring.clipCount)
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
    fun `a raycast point marks the run as uncertain in the order list`() {
        // 0.7 is below the depth tier (0.85), so the phone already tells the operator the weakest
        // point of this run is 70 %. The order list used to draw its line at 0.5 and stay silent,
        // which meant the paperwork was more optimistic than the screen (finding 47).
        val raycast = listOf(
            node(0f, 0f, 0f),
            node(1f, 0f, 0f, HitSource.DIRECT_DEPTH_RAYCAST),
            node(2f, 0f, 0f),
        )
        assertEquals(1, PolylineMetrics.measure(raycast, ConduitSpec.cable(16f)).lowConfidenceNodes)

        // A depth point is exactly at the bar: measured, not questioned.
        val depth = listOf(
            node(0f, 0f, 0f),
            node(1f, 0f, 0f, HitSource.AR_DEPTH_POINT),
            node(2f, 0f, 0f),
        )
        assertEquals(0, PolylineMetrics.measure(depth, ConduitSpec.cable(16f)).lowConfidenceNodes)

        // And a plane-run stays clean, which is the common case and must not become noise.
        val plane = listOf(node(0f, 0f, 0f), node(1f, 0f, 0f), node(2f, 0f, 0f))
        assertEquals(0, PolylineMetrics.measure(plane, ConduitSpec.cable(16f)).lowConfidenceNodes)
    }

    /**
     * A bend of sweep δ and centreline radius r replaces two straight trims of `r·tan(δ/2)` with
     * an arc of `r·δ`, so the bent conduit is *shorter* than the polyline the tape measures.
     *
     * A 20 mm pipe bends at r = 0.1 m, so one right angle deducts
     * `2 · 0.1 · tan 45° − 0.1 · π/2 = 0.0429 m`. The field used to hold the negative of this and
     * was documented as "the arc length a fitter has to add inside the bend" - the opposite sign
     * of what a bend does - and was printed nowhere (finding 114).
     */
    @Test
    fun `a right angle shortens the developed length by the bend deduction`() {
        val nodes = listOf(node(0f, 0f, 0f), node(2f, 0f, 0f), node(2f, 0f, 2f))
        val metrics = PolylineMetrics.measure(nodes, ConduitSpec.pipe(20f))

        assertEquals(0.0429f, metrics.bendDeductionMeters, 1e-4f)
        // Reported, never folded in: the metre figure stays the tape-measure one.
        assertEquals(4f, metrics.totalMeters, 1e-4f)
        assertEquals(4f * 1.08f, metrics.withWasteMeters, 1e-3f)
        assertEquals(1, metrics.elbow90Count)
    }

    /** A 45° offset deducts a tenth of what a right angle does - the same formula at δ = π/4. */
    @Test
    fun `a forty five degree offset deducts its own smaller amount`() {
        val diagonal = 0.7071f
        val nodes = listOf(node(0f, 0f, 0f), node(2f, 0f, 0f), node(2f + diagonal, 0f, diagonal))
        val metrics = PolylineMetrics.measure(nodes, ConduitSpec.pipe(20f))

        assertEquals(0.0043f, metrics.bendDeductionMeters, 1e-4f)
        assertTrue(metrics.bendDeductionMeters < 0.0429f)
    }

    /**
     * A run that doubles back is a measurement artefact, not a 180° bend: `tan(δ/2)` goes to
     * infinity there, so the uncapped formula produced a deduction of astronomical size. The
     * sweep is clamped to what a bender can make, and the total can never eat the piece.
     */
    @Test
    fun `a double back is clamped instead of deducting the whole run`() {
        val nodes = listOf(node(0f, 0f, 0f), node(2f, 0f, 0f), node(1f, 0f, 0f))
        val metrics = PolylineMetrics.measure(nodes, ConduitSpec.pipe(20f))

        assertTrue(metrics.bendDeductionMeters.isFinite())
        assertEquals(0.0429f, metrics.bendDeductionMeters, 1e-4f)

        // Two nodes on one line: no corner, no deduction at all.
        val straight = PolylineMetrics.measure(
            listOf(node(0f, 0f, 0f), node(3f, 0f, 0f), node(6f, 0f, 0f)),
            ConduitSpec.pipe(20f),
        )
        assertEquals(0f, straight.bendDeductionMeters, 1e-6f)
    }

    @Test
    fun `minimum bend radius scales with the conduit`() {
        val cable = PolylineMetrics.defaultBendRadius(ConduitSpec.cable(16f))
        val pipe = PolylineMetrics.defaultBendRadius(ConduitSpec.pipe(32f))
        assertEquals(0.128f, cable, 1e-4f) // 8 × 0.016 m
        assertEquals(0.16f, pipe, 1e-4f) // 5 × 0.032 m
    }
}
