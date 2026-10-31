package com.constructar.core.geo

import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class PathSmootherTest {

    @Test
    fun `right angle fillet trims by exactly the radius`() {
        // Two 1 m legs meeting at the origin: in along -X, out along +Y.
        val previous = Vec3(-1f, 0f, 0f)
        val corner = Vec3(0f, 0f, 0f)
        val next = Vec3(0f, 1f, 0f)

        val fillet = PathSmoother.computeFillet(previous, corner, next, requestedRadius = 0.1f)
        assertNotNull(fillet)
        fillet!!

        assertEquals(0.1f, fillet.radius, 1e-4f)
        // t = r·tan(45°) = r for a 90° deflection.
        assertEquals(-0.1f, fillet.tangentIn.x, 1e-4f)
        assertEquals(0.1f, fillet.tangentIn.y, 1e-4f)
        assertEquals(0f, fillet.tangentOut.x, 1e-4f)
        assertEquals(0.1f, fillet.tangentOut.y, 1e-4f)
        // Centre at (-r, r).
        assertEquals(-0.1f, fillet.center.x, 1e-4f)
        assertEquals(0.1f, fillet.center.y, 1e-4f)
        assertEquals(PI.toFloat() / 2f, fillet.sweepRadians, 1e-4f)
    }

    @Test
    fun `straight corner is left untouched`() {
        val fillet = PathSmoother.computeFillet(
            Vec3(0f, 0f, 0f),
            Vec3(1f, 0f, 0f),
            Vec3(2f, 0f, 0f),
            requestedRadius = 0.1f,
        )
        assertNull(fillet)
    }

    @Test
    fun `fillet radius is clamped by the shortest leg`() {
        // Legs of 0.2 m with a 5 m requested radius: trim may not exceed 45 % of a leg.
        val fillet = PathSmoother.computeFillet(
            Vec3(-0.2f, 0f, 0f),
            Vec3.ZERO,
            Vec3(0f, 0.2f, 0f),
            requestedRadius = 5f,
        )
        assertNotNull(fillet)
        val trim = (fillet!!.tangentIn - Vec3.ZERO).length
        assertTrue("trim $trim must stay below 45% of 0.2 m", trim <= 0.45f * 0.2f + 1e-4f)
    }

    @Test
    fun `smoothed 90 degree path length matches the analytic arc`() {
        val nodes = listOf(Vec3(-1f, 0f, 0f), Vec3.ZERO, Vec3(0f, 1f, 0f))
        val path = PathSmoother.smooth(nodes, bendRadiusMeters = 0.1f, maxStraightSpanMeters = 10f)

        // Analytic: 2 straight legs of 0.9 m + quarter circle of radius 0.1 m.
        val expected = 1.8f + (PI.toFloat() / 2f) * 0.1f
        assertEquals(expected, path.totalLengthMeters, 0.02f)
        assertEquals(1, path.cornerCount)

        // Every consecutive pair must be tangent-continuous (no kink > 20°).
        for (i in 1 until path.points.size) {
            val a = (path.points[i] - path.points[i - 1]).normalizedOrZero()
            val b = path.tangents[i]
            if (!a.isDegenerate()) {
                assertTrue("kink at $i: ${a.angleTo(b)}", a.angleTo(b) < Math.toRadians(20.0).toFloat())
            }
        }
    }

    @Test
    fun `stranger nodes are dropped and a single duplicate node is not routable`() {
        val nodes = listOf(Vec3.ZERO, Vec3(1e-6f, 0f, 0f))
        var threw = false
        try {
            PathSmoother.smooth(nodes)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("duplicate nodes must be rejected", threw)
    }

    @Test
    fun `arc length is monotonic and ends at the total length`() {
        val nodes = listOf(Vec3(0f, 0f, 0f), Vec3(2f, 0f, 0f), Vec3(2f, 0f, 2f))
        val path = PathSmoother.smooth(nodes, bendRadiusMeters = 0.2f)
        for (i in 1 until path.arcLengths.size) {
            assertTrue(path.arcLengths[i] >= path.arcLengths[i - 1] - 1e-5f)
        }
        assertEquals(path.totalLengthMeters, path.arcLengths.last(), 1e-4f)
    }

    @Test
    fun `resampling keeps both endpoints`() {
        val nodes = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 3f))
        val path = PathSmoother.smooth(nodes, maxStraightSpanMeters = 0.5f)
        val resampled = PathSmoother.resample(path, 1f)
        assertEquals(path.points.first().z, resampled.first().z, 1e-4f)
        assertEquals(path.points.last().z, resampled.last().z, 1e-4f)
        assertTrue(resampled.size >= 3)
    }

    @Test
    fun `pointAt returns the endpoints at the extremes`() {
        val nodes = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 2.5f))
        val path = PathSmoother.smooth(nodes)
        assertEquals(0f, PathSmoother.pointAt(path, 0f).z, 1e-4f)
        assertEquals(2.5f, abs(PathSmoother.pointAt(path, 1f).z), 1e-4f)
        assertEquals(1.25f, abs(PathSmoother.pointAt(path, 0.5f).z), 0.05f)
    }
}
