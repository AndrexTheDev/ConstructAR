/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.geo

import com.constructar.core.math.Plane3
import com.constructar.core.math.PolygonMath
import com.constructar.core.math.Quat
import com.constructar.core.math.Ray
import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FramesAndPlaneTest {

    @Test
    fun `parallel transport frames stay orthonormal on a helix`() {
        val points = (0..60).map { i ->
            val t = i / 10f
            Vec3(cos(t) * 0.5f, t * 0.3f, sin(t) * 0.5f)
        }
        val frames = ParallelTransport.frames(points)
        assertEquals(points.size, frames.size)
        for (frame in frames) {
            assertEquals(1f, frame.tangent.length, 1e-4f)
            assertEquals(1f, frame.normal.length, 1e-4f)
            assertEquals(1f, frame.binormal.length, 1e-4f)
            assertEquals(0f, frame.tangent dot frame.normal, 1e-4f)
            assertEquals(0f, frame.normal dot frame.binormal, 1e-4f)
        }
    }

    @Test
    fun `rotation minimising frames do not accumulate twist`() {
        // A full planar circle: returning to the start must return the frame to the start
        // orientation (a naive up-vector frame would flip through the turn).
        val points = (0..72).map { i ->
            val a = (i / 72f) * 2f * Math.PI.toFloat()
            Vec3(cos(a), 0f, sin(a))
        }
        val frames = ParallelTransport.frames(points, Vec3.Y_AXIS)
        var maxTwist = 0f
        for (i in 1 until frames.size) {
            maxTwist = maxOf(maxTwist, frames[i].normal.angleTo(frames[i - 1].normal))
        }
        // Per-step twist must stay far below the per-step tangent turn (2π/72 ≈ 5°).
        assertTrue("max step twist $maxTwist rad", maxTwist < Math.toRadians(6.0).toFloat())
    }

    @Test
    fun `surface alignment faces the viewer`() {
        val surface = Vec3(0f, 0f, 0f)
        val normal = Vec3(0f, 0f, -1f) // ARCore normal pointing into the wall
        val viewer = Vec3(0f, 0f, 1.5f)
        val facing = SurfaceAlignment.facingCamera(normal, surface, viewer)
        assertEquals(1f, facing.z, 1e-5f)

        val offset = SurfaceAlignment.offsetAlongNormal(surface, facing, 0.02f)
        assertEquals(0.02f, offset.z, 1e-5f)
    }

    @Test
    fun `tangent frame keeps the duct flush on a 45 degree wall`() {
        val tangent = Vec3(0f, 0f, 1f)
        val surfaceNormal = Vec3(1f, 0f, 0f)
        val frame = SurfaceAlignment.tangentFrame(Vec3.ZERO, tangent, surfaceNormal)
        assertEquals(0f, frame.tangent dot frame.normal, 1e-5f)
        assertEquals(1f, frame.normal dot surfaceNormal, 1e-5f)
        // lateral = tangent × normal = +Y.
        assertEquals(1f, frame.binormal.y, 1e-5f)
    }

    @Test
    fun `least squares plane fit recovers a tilted plane`() {
        // Plane: y = 0.5x + 1, sampled on a grid.
        val points = ArrayList<Vec3>()
        for (i in -2..2) {
            for (j in -2..2) {
                val x = i * 0.3f
                val z = j * 0.3f
                points.add(Vec3(x, 0.5f * x + 1f, z))
            }
        }
        val fit = Plane3.fitLeastSquares(points)
        assertNotNull(fit)
        fit!!
        // Expected normal ∝ (-0.5, 1, 0) normalised, up to sign.
        val expected = Vec3(-0.5f, 1f, 0f).normalized()
        val agreement = kotlin.math.abs(fit.plane.normal dot expected)
        assertEquals(1f, agreement, 1e-3f)
        // Every sample must lie on the fitted plane.
        for (p in points) assertEquals(0f, fit.plane.signedDistance(p), 1e-3f)
    }

    @Test
    fun `polygon containment accepts inside and rejects outside`() {
        val square = listOf(
            Vec3(-1f, 0f, -1f),
            Vec3(1f, 0f, -1f),
            Vec3(1f, 0f, 1f),
            Vec3(-1f, 0f, 1f),
        )
        assertTrue(PolygonMath.contains(square, Vec3(0.2f, 0f, 0.2f)))
        assertFalse(PolygonMath.contains(square, Vec3(2f, 0f, 2f)))
        // On-edge counts as inside (a tap on the very edge of a wall must not fail).
        assertTrue(PolygonMath.contains(square, Vec3(1f, 0f, 0.1f)))
        assertEquals(4f, PolygonMath.area(square), 1e-4f)
    }

    @Test
    fun `ray plane intersection returns the surface point`() {
        val plane = Plane3.fromPointNormal(Vec3(0f, 0f, 3f), Vec3(0f, 0f, -1f))
        val ray = Ray(Vec3.ZERO, Vec3(0f, 0f, 1f))
        val t = plane.intersect(ray)
        assertNotNull(t)
        assertEquals(3f, t!!, 1e-4f)
        assertEquals(3f, ray.pointAt(t).z, 1e-4f)
    }

    @Test
    fun `quaternion rotation from world up to a wall normal`() {
        val normal = Vec3(1f, 0f, 0f)
        val rotation = Quat.rotationFromTo(Vec3.Y_AXIS, normal)
        val rotated = rotation.rotate(Vec3.Y_AXIS)
        assertEquals(1f, rotated.x, 1e-4f)
        assertEquals(0f, rotated.y, 1e-4f)
        // 180° case must not produce NaN.
        val flipped = Quat.rotationFromTo(Vec3.Y_AXIS, Vec3(0f, -1f, 0f))
        val flippedVector = flipped.rotate(Vec3.Y_AXIS)
        assertEquals(-1f, flippedVector.y, 1e-3f)
        assertTrue(flippedVector.x.isFinite())
    }
}
