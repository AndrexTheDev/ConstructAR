/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.hit.HitResolution
import com.constructar.arengine.hit.PlaneCandidate
import com.constructar.arengine.hit.ReticleSnap
import com.constructar.arengine.hit.SnapFeatureKind
import com.constructar.arengine.hit.SurfaceHit
import com.constructar.arengine.hit.TierOutcome
import com.constructar.core.math.PolygonMath
import com.constructar.core.math.Vec3
import com.constructar.core.model.HitSource
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cascade answers "which surface"; this step answers "where on it".
 *
 * The rule that must not bend: only a plane-polygon hit may move. A depth or feature-point hit
 * has no polygon of its own, so pulling it onto a corner would claim a precision the sensor never
 * measured, and the normal must stay on the surface the operator aimed at rather than flipping to
 * the perpendicular wall that shares the corner.
 */
class ReticleSnapTest {

    private fun plane(id: String, normal: Vec3, polygon: List<Vec3>): PlaneCandidate {
        var sum = Vec3(0f, 0f, 0f)
        for (vertex in polygon) sum += vertex
        return PlaneCandidate(
            id = id,
            polygon = polygon,
            normal = normal,
            offset = normal dot (sum / polygon.size.toFloat()),
            center = sum / polygon.size.toFloat(),
            areaSquareMeters = PolygonMath.area(polygon),
            isVertical = kotlin.math.abs(normal.y) < 0.6f,
        )
    }

    private fun wallAtXZero() = plane(
        "wall-x",
        Vec3(1f, 0f, 0f),
        listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 4f), Vec3(0f, 2.5f, 4f), Vec3(0f, 2.5f, 0f)),
    )

    private fun wallAtZZero() = plane(
        "wall-z",
        Vec3(0f, 0f, 1f),
        listOf(Vec3(0f, 0f, 0f), Vec3(4f, 0f, 0f), Vec3(4f, 2.5f, 0f), Vec3(0f, 2.5f, 0f)),
    )

    private fun hit(
        point: Vec3,
        source: HitSource = HitSource.PLANE_POLYGON,
        normal: Vec3 = Vec3(1f, 0f, 0f),
    ) = SurfaceHit(
        point = point,
        normal = normal,
        source = source,
        confidence = 0.9f,
        distanceMeters = 1.75f,
        planeId = "wall-x",
        surfaceKind = SurfaceKind.WALL,
    )

    private fun resolution(hit: SurfaceHit?) = HitResolution(
        hit = hit,
        attempts = listOf(TierOutcome(HitSource.PLANE_POLYGON, considered = true, accepted = hit != null, reason = "test")),
        distanceFromPreviousMeters = 0.5f,
    )

    private val planes get() = listOf(wallAtXZero(), wallAtZZero())

    @Test
    fun `a plane hit near the corner is moved onto it`() {
        val original = hit(Vec3(0.05f, 1.2f, 0.03f))
        val point = requireNotNull(ReticleSnap.snap(resolution(original), planes).hit).point

        assertEquals(0f, point.x, 1e-5f)
        assertEquals(0f, point.z, 1e-5f)
        assertEquals(1.2f, point.y, 1e-5f)
    }

    @Test
    fun `snapping keeps source, normal, plane id and confidence`() {
        val original = hit(Vec3(0.05f, 1.2f, 0.03f))
        val snapped = ReticleSnap.snap(resolution(original), planes).hit!!

        assertEquals(HitSource.PLANE_POLYGON, snapped.source)
        assertEquals("wall-x", snapped.planeId)
        assertEquals(SurfaceKind.WALL, snapped.surfaceKind)
        assertEquals(0.9f, snapped.confidence, 1e-6f)
        assertEquals(1.75f, snapped.distanceMeters, 1e-6f)
        // The aimed wall's normal, never the perpendicular one that shares the corner.
        assertEquals(Vec3(1f, 0f, 0f), snapped.normal)
    }

    @Test
    fun `the cascade bookkeeping survives the snap`() {
        val original = resolution(hit(Vec3(0.05f, 1.2f, 0.03f)))
        val snapped = ReticleSnap.snap(original, planes)

        assertEquals(original.attempts, snapped.attempts)
        assertEquals(0.5f, snapped.distanceFromPreviousMeters!!, 1e-6f)
        assertTrue(snapped.accepted)
    }

    @Test
    fun `a move records the feature it moved onto and the distance`() {
        // 5 cm off the corner in x and 3 cm in z: the point lands on the corner, and the record
        // has to carry both facts, because a reticle that jumps silently is a reticle the operator
        // cannot learn to trust (finding 213).
        val snapped = requireNotNull(ReticleSnap.snap(resolution(hit(Vec3(0.05f, 1.2f, 0.03f))), planes).hit)
        val record = requireNotNull(snapped.snap)

        assertEquals(SnapFeatureKind.CORNER, record.kind)
        assertEquals(kotlin.math.sqrt(0.05f * 0.05f + 0.03f * 0.03f), record.distanceM, 1e-4f)
    }

    @Test
    fun `a point that did not move records nothing`() {
        // Beyond the tolerance: no record, because nothing happened.
        val far = requireNotNull(ReticleSnap.snap(resolution(hit(Vec3(0.2f, 1.2f, 0f))), planes).hit)
        assertNull(far.snap)

        // A depth hit is never snapped, so it carries no record either.
        val depth = requireNotNull(
            ReticleSnap.snap(resolution(hit(Vec3(0.05f, 1.2f, 0.03f), source = HitSource.DIRECT_DEPTH_RAYCAST)), planes).hit,
        )
        assertNull(depth.snap)

        // Disabled: the raw point, and no claim about a move that did not happen.
        val raw = requireNotNull(
            ReticleSnap.snap(resolution(hit(Vec3(0.05f, 1.2f, 0.03f))), planes, enabled = false).hit,
        )
        assertNull(raw.snap)
    }

    @Test
    fun `a hit beyond the tolerance is left alone`() {
        val original = hit(Vec3(0.2f, 1.2f, 0f))
        val snapped = ReticleSnap.snap(resolution(original), planes)

        assertSame(original, snapped.hit)
        assertEquals(0.2f, snapped.hit!!.point.x, 1e-5f)
    }

    @Test
    fun `depth hits are never snapped`() {
        for (source in listOf(HitSource.AR_DEPTH_POINT, HitSource.DIRECT_DEPTH_RAYCAST, HitSource.FEATURE_POINT)) {
            val original = hit(Vec3(0.05f, 1.2f, 0.03f), source = source)
            val snapped = ReticleSnap.snap(resolution(original), planes)
            assertSame("$source must not move", original, snapped.hit)
        }
    }

    @Test
    fun `a miss stays a miss`() {
        val snapped = ReticleSnap.snap(HitResolution.miss(emptyList()), planes)

        assertNull(snapped.hit)
        assertFalse(snapped.accepted)
    }

    @Test
    fun `no planes means an untouched resolution`() {
        val original = resolution(hit(Vec3(0.05f, 1.2f, 0.03f)))
        val snapped = ReticleSnap.snap(original, emptyList())

        assertSame(original, snapped)
    }

    @Test
    fun `disabling the snap returns the raw polygon hit`() {
        val original = hit(Vec3(0.05f, 1.2f, 0.03f))
        val snapped = ReticleSnap.snap(resolution(original), planes, enabled = false)

        assertSame(original, snapped.hit)
        assertEquals(0.05f, snapped.hit!!.point.x, 1e-5f)
    }
}
