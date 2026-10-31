/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.hit.HitCascadeConfig
import com.constructar.arengine.hit.HitRequest
import com.constructar.arengine.hit.PlaneCandidate
import com.constructar.arengine.hit.SurfaceHit
import com.constructar.arengine.hit.SurfaceHitResolver
import com.constructar.core.math.Ray
import com.constructar.core.math.Vec3
import com.constructar.core.model.HitSource
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hit cascade is the single most important correctness surface in the app: a wrong tier
 * silently moves a cable by centimetres, and nothing downstream can detect it.
 */
class SurfaceHitResolverTest {

    private val resolver = SurfaceHitResolver(HitCascadeConfig())

    /** A 4 m × 3 m wall at z = −2 facing the viewer. */
    private fun wall(): PlaneCandidate = PlaneCandidate.fromPolygon(
        id = "wall-1",
        polygon = listOf(
            Vec3(-2f, 0f, -2f),
            Vec3(2f, 0f, -2f),
            Vec3(2f, 3f, -2f),
            Vec3(-2f, 3f, -2f),
        ),
        normal = Vec3(0f, 0f, 1f),
        center = Vec3(0f, 1.5f, -2f),
    )!!

    private val viewer = Vec3(0f, 1.5f, 0f)
    private val rayToWall = Ray(viewer, Vec3(0f, 0f, -1f))

    @Test
    fun `plane polygon wins over every other tier`() {
        val resolution = resolver.resolve(
            HitRequest(
                ray = rayToWall,
                viewerPosition = viewer,
                planes = listOf(wall()),
                depthPoint = depthHit(1.9f),
                directDepthHit = depthHit(1.95f),
                featurePoint = featureHit(2.4f),
            ),
        )
        val hit = resolution.hit
        assertNotNull(hit)
        assertEquals(HitSource.PLANE_POLYGON, hit!!.source)
        assertEquals(2f, hit.point.z, 1e-4f)
        assertEquals(SurfaceKind.WALL, hit.surfaceKind)
        // Normal must face the operator, never into the plaster.
        assertTrue((hit.normal dot (viewer - hit.point)) > 0f)
    }

    @Test
    fun `ray outside the tracked polygon falls through to depth`() {
        // The wall plane exists, but the polygon only covers x ∈ [0, 1]; the ray at x = 0.5
        // is inside, so aim well outside it (x = 1.5 m off-axis).
        val smallPolygon = PlaneCandidate.fromPolygon(
            id = "wall-2",
            polygon = listOf(
                Vec3(-0.5f, 0f, -2f),
                Vec3(0.5f, 0f, -2f),
                Vec3(0.5f, 3f, -2f),
                Vec3(-0.5f, 3f, -2f),
            ),
            normal = Vec3(0f, 0f, 1f),
            center = Vec3(0f, 1.5f, -2f),
        )!!
        val offsetRay = Ray(Vec3(1.5f, 1.5f, 0f), Vec3(0f, 0f, -1f))
        val resolution = resolver.resolve(
            HitRequest(
                ray = offsetRay,
                viewerPosition = Vec3(1.5f, 1.5f, 0f),
                planes = listOf(smallPolygon),
                depthPoint = depthHit(2.05f, point = Vec3(1.5f, 1.5f, -2.05f)),
            ),
        )
        assertEquals(HitSource.AR_DEPTH_POINT, resolution.hit?.source)
        // And the plane tier must explain why it declined.
        val planeAttempt = resolution.attempts.first { it.source == HitSource.PLANE_POLYGON }
        assertFalse(planeAttempt.accepted)
    }

    @Test
    fun `depth tiers are used in strict order`() {
        val depthPoint = resolver.resolve(
            HitRequest(
                ray = rayToWall,
                viewerPosition = viewer,
                depthPoint = depthHit(1.9f),
                directDepthHit = depthHit(2.1f),
                featurePoint = featureHit(2.5f),
            ),
        )
        assertEquals(HitSource.AR_DEPTH_POINT, depthPoint.hit?.source)

        val direct = resolver.resolve(
            HitRequest(
                ray = rayToWall,
                viewerPosition = viewer,
                directDepthHit = depthHit(2.1f),
                featurePoint = featureHit(2.5f),
            ),
        )
        assertEquals(HitSource.DIRECT_DEPTH_RAYCAST, direct.hit?.source)

        val feature = resolver.resolve(
            HitRequest(ray = rayToWall, viewerPosition = viewer, featurePoint = featureHit(2.5f)),
        )
        assertEquals(HitSource.FEATURE_POINT, feature.hit?.source)
    }

    @Test
    fun `no candidate at all yields a miss with a full diagnostic trail`() {
        val resolution = resolver.resolve(HitRequest(ray = rayToWall, viewerPosition = viewer))
        assertNull(resolution.hit)
        assertFalse(resolution.accepted)
        assertEquals(4, resolution.attempts.size)
    }

    @Test
    fun `hits beyond the configured range are rejected`() {
        val far = depthHit(12f, point = Vec3(0f, 1.5f, -12f))
        val resolution = resolver.resolve(
            HitRequest(ray = rayToWall, viewerPosition = viewer, depthPoint = far),
        )
        assertNull(resolution.hit)
    }

    @Test
    fun `subsumed and untracked planes are ignored`() {
        val resolution = resolver.resolve(
            HitRequest(
                ray = rayToWall,
                viewerPosition = viewer,
                planes = listOf(wall().copy(subsumedBy = "plane-9"), wall().copy(isTracking = false)),
                depthPoint = depthHit(1.9f),
            ),
        )
        assertEquals(HitSource.AR_DEPTH_POINT, resolution.hit?.source)
    }

    @Test
    fun `normal is flipped towards the viewer when the plane faces away`() {
        val flipped = wall().copy(normal = Vec3(0f, 0f, -1f), offset = 2f)
        val resolution = resolver.resolve(
            HitRequest(ray = rayToWall, viewerPosition = viewer, planes = listOf(flipped)),
        )
        val hit = resolution.hit!!
        assertTrue("normal must face the operator", (hit.normal dot (viewer - hit.point)) > 0f)
    }

    private fun depthHit(distance: Float, point: Vec3 = Vec3(0f, 1.5f, -distance)) = SurfaceHit(
        point = point,
        normal = Vec3(0f, 0f, 1f),
        source = HitSource.AR_DEPTH_POINT,
        confidence = HitSource.AR_DEPTH_POINT.reliability,
        distanceMeters = distance,
        surfaceKind = SurfaceKind.WALL,
    )

    private fun featureHit(distance: Float) = SurfaceHit(
        point = Vec3(0f, 1.5f, -distance),
        normal = Vec3(0f, 0f, 1f),
        source = HitSource.FEATURE_POINT,
        confidence = HitSource.FEATURE_POINT.reliability,
        distanceMeters = distance,
    )
}
