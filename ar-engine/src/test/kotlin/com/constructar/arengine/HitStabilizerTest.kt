/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.hit.HitStabilizer
import com.constructar.arengine.hit.SurfaceHit
import com.constructar.core.math.Vec3
import com.constructar.core.model.HitSource
import com.constructar.core.model.SurfaceKind
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stabiliser is the difference between a 2 cm reading and a 5 mm one, so its window rules
 * are pinned: what settles, what a stray frame may not do, and when the window must be thrown
 * away.
 */
class HitStabilizerTest {

    private fun hit(x: Float, y: Float, z: Float, source: HitSource = HitSource.PLANE_POLYGON, planeId: String = "p1") =
        SurfaceHit(
            point = Vec3(x, y, z),
            normal = Vec3(0f, 0f, 1f),
            source = source,
            confidence = 1f,
            distanceMeters = 2f,
            planeId = planeId,
            surfaceKind = SurfaceKind.WALL,
        )

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-4f) {
        assertTrue("expected $expected, was $actual", abs(expected - actual) <= tolerance)
    }

    @Test
    fun `a single sample is reported but cannot settle`() {
        val stabilizer = HitStabilizer()
        val reading = stabilizer.sample(hit(1f, 0f, 0f), nowMs = 1_000L)
        assertEquals(1, reading.samples)
        assertFalse(reading.settled)
        assertClose(1f, reading.point!!.x)
    }

    @Test
    fun `three agreeing samples settle on their median`() {
        val stabilizer = HitStabilizer()
        stabilizer.sample(hit(1.000f, 0f, 0f), 1_000L)
        stabilizer.sample(hit(1.004f, 0f, 0f), 1_040L)
        val reading = stabilizer.sample(hit(0.997f, 0f, 0f), 1_080L)
        assertTrue(reading.settled)
        // 0,997 / 1,000 / 1,004 -> median 1,000
        assertClose(1.000f, reading.point!!.x)
        assertTrue(reading.spreadMeters <= 0.010f)
    }

    @Test
    fun `a single wild frame is outvoted by the median`() {
        val stabilizer = HitStabilizer()
        stabilizer.sample(hit(1.000f, 0f, 0f), 1_000L)
        stabilizer.sample(hit(1.001f, 0f, 0f), 1_040L)
        stabilizer.sample(hit(1.002f, 0f, 0f), 1_080L)
        // The reticle slips onto a door reveal for one frame: the point must barely move.
        val reading = stabilizer.sample(hit(1.300f, 0f, 0f), 1_120L)
        // Four samples: the median is the mean of the two middle values, 1,001 and 1,002.
        assertClose(1.0015f, reading.point!!.x)
        assertTrue("spread ${reading.spreadMeters}", reading.spreadMeters > 0.05f)
    }

    @Test
    fun `a moving hand never settles`() {
        val stabilizer = HitStabilizer()
        var t = 1_000L
        var settled = false
        repeat(6) { step ->
            val reading = stabilizer.sample(hit(1f + step * 0.05f, 0f, 0f), t)
            settled = settled || reading.settled
            t += 40
        }
        assertFalse("a 5 cm sweep must not lock", settled)
    }

    @Test
    fun `a stray frame widens the spread instead of hiding it`() {
        val stabilizer = HitStabilizer()
        stabilizer.sample(hit(1.000f, 0f, 0f), 1_000L)
        stabilizer.sample(hit(1.000f, 0f, 0f), 1_040L)
        val clean = stabilizer.sample(hit(1.000f, 0f, 0f), 1_080L)
        assertEquals(0f, clean.spreadMeters, 1e-5f)
        val dirty = stabilizer.sample(hit(1.200f, 0f, 0f), 1_120L)
        assertTrue("a 20 cm sample must not read as settled", !dirty.settled || dirty.spreadMeters > 0.05f)
    }

    @Test
    fun `a different plane starts a new window`() {
        val stabilizer = HitStabilizer()
        stabilizer.sample(hit(1f, 0f, 0f), 1_000L)
        stabilizer.sample(hit(1f, 0f, 0f), 1_040L)
        val otherWall = hit(1f, 0f, 0f, planeId = "p2")
        val reading = stabilizer.sample(otherWall, 1_080L)
        assertEquals(1, reading.samples)
        assertFalse(reading.settled)
    }

    @Test
    fun `a different cascade tier starts a new window`() {
        val stabilizer = HitStabilizer()
        stabilizer.sample(hit(1f, 0f, 0f), 1_000L)
        stabilizer.sample(hit(1f, 0f, 0f), 1_040L)
        val depth = hit(1f, 0f, 0f, source = HitSource.DIRECT_DEPTH_RAYCAST, planeId = "p1")
        assertEquals(1, stabilizer.sample(depth, 1_080L).samples)
    }

    @Test
    fun `a miss clears the window so an old point cannot be committed later`() {
        val stabilizer = HitStabilizer()
        stabilizer.sample(hit(1f, 0f, 0f), 1_000L)
        stabilizer.sample(hit(1f, 0f, 0f), 1_040L)
        stabilizer.sample(hit(1f, 0f, 0f), 1_080L)
        val miss = stabilizer.sample(null, 1_120L)
        assertEquals(0, miss.samples)
        assertFalse(miss.hasPoint)
        // A later sample must not resurrect the old, settled window.
        assertFalse(stabilizer.sample(hit(1f, 0f, 0f), 1_160L).settled)
    }

    @Test
    fun `a stale window is dropped after the timeout`() {
        val stabilizer = HitStabilizer(windowMillis = 500L)
        stabilizer.sample(hit(1f, 0f, 0f), 1_000L)
        stabilizer.sample(hit(1f, 0f, 0f), 1_040L)
        stabilizer.sample(hit(1f, 0f, 0f), 1_080L)
        // Two seconds later the operator samples again; the old three are gone.
        assertEquals(1, stabilizer.sample(hit(1f, 0f, 0f), 3_000L).samples)
    }

    @Test
    fun `the committed hit is the settled median and the raw hit otherwise`() {
        val stabilizer = HitStabilizer()
        // One sample: nothing has settled, so the operator's tap is honoured as-is.
        assertEquals(1.010f, stabilizer.stabilize(hit(1.010f, 0f, 0f), 1_000L).point.x, 1e-5f)

        stabilizer.sample(hit(1.000f, 0f, 0f), 1_040L)
        stabilizer.sample(hit(1.006f, 0f, 0f), 1_080L)
        // Four samples inside 1 cm: the median wins over the raw tap. With an even count the
        // median is the mean of the two middle values (1,006 and 1,010), i.e. 1,008 - still
        // closer to the operator's intended point than either edge of the window.
        val committed = stabilizer.stabilize(hit(1.010f, 0f, 0f), 1_120L)
        assertClose(1.008f, committed.point.x)
        // The tier is never upgraded by averaging.
        assertEquals(HitSource.PLANE_POLYGON, committed.source)
    }

    @Test
    fun `reset empties the window`() {
        val stabilizer = HitStabilizer()
        repeat(4) { stabilizer.sample(hit(1f, 0f, 0f), 1_000L + it * 40) }
        stabilizer.reset()
        assertEquals(0, stabilizer.sample(null, 1_200L).samples)
    }
}
