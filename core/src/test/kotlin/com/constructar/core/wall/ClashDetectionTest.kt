/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.math.Aabb
import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clash check answers one question - does this run fit - and it is wrong in ways that look
 * right, so the geometry is tested before the report.
 *
 * The case that matters most: a run straight through the middle of a box touches none of its
 * twelve edges. An implementation built only from edge and corner distances puts the closest
 * approach at 0.50 m for a segment that goes through a 1 m cube, which reads as "clear" on a
 * drawing of a cable run inside a beam.
 */
class ClashDetectionTest {

    private val unitBox = Aabb(Vec3(-0.5f, -0.5f, -0.5f), Vec3(0.5f, 0.5f, 0.5f))

    @Test
    fun `point to box is zero inside and exact outside`() {
        assertEquals(0f, ClashGeometry.pointToAabbDistance(Vec3(0f, 0f, 0f), unitBox), 1e-6f)
        assertEquals(0f, ClashGeometry.pointToAabbDistance(Vec3(0.5f, 0f, 0f), unitBox), 1e-6f)
        assertEquals(0.5f, ClashGeometry.pointToAabbDistance(Vec3(1f, 0f, 0f), unitBox), 1e-6f)
        // 30 cm past one face, 40 cm past another: the corner distance, not the larger axis.
        assertEquals(0.5f, ClashGeometry.pointToAabbDistance(Vec3(0.8f, 0.9f, -0.5f), unitBox), 1e-6f)
    }

    @Test
    fun `a segment through the middle of a box intersects it`() {
        val entry = ClashGeometry.segmentEntryFraction(Vec3(-2f, 0f, 0f), Vec3(2f, 0f, 0f), unitBox)
        assertEquals(0.375f, entry ?: -1f, 1e-5f)
    }

    @Test
    fun `a segment that misses the box by a centimetre reports no entry`() {
        val entry = ClashGeometry.segmentEntryFraction(
            Vec3(-2f, 0.51f, 0f),
            Vec3(2f, 0.51f, 0f),
            unitBox,
        )
        assertNull(entry)
    }

    @Test
    fun `a segment that starts inside the box enters at its own start`() {
        val entry = ClashGeometry.segmentEntryFraction(Vec3(0f, 0f, 0f), Vec3(2f, 0f, 0f), unitBox)
        assertEquals(0f, entry ?: -1f, 1e-6f)
    }

    @Test
    fun `a collapsed segment is a point, inside or outside`() {
        val inside = ClashGeometry.segmentEntryFraction(Vec3(0.1f, 0.1f, 0.1f), Vec3(0.1f, 0.1f, 0.1f), unitBox)
        assertEquals(0f, inside ?: -1f, 1e-6f)
        val outside = ClashGeometry.segmentEntryFraction(Vec3(1.1f, 0f, 0f), Vec3(1.1f, 0f, 0f), unitBox)
        assertNull(outside)
    }

    @Test
    fun `segment to box distance is zero through the box, not the distance to its edges`() {
        // The shortcut this pins: the closest edge of a 1 m cube to a segment through its centre is
        // 0.5 m away, so an edge-only implementation reports 0.5 m for a penetration.
        val through = ClashGeometry.segmentToAabb(Vec3(-2f, 0f, 0f), Vec3(2f, 0f, 0f), unitBox)
        assertEquals(0f, through.distanceM, 1e-6f)
        assertEquals(0.375f, through.fraction, 1e-5f)

        val fromInside = ClashGeometry.segmentToAabb(Vec3(0.2f, 0f, 0f), Vec3(2f, 0f, 0f), unitBox)
        assertEquals(0f, fromInside.distanceM, 1e-6f)
    }

    @Test
    fun `segment to box distance is exact when it passes by`() {
        val above = ClashGeometry.segmentToAabb(Vec3(-2f, 0.8f, 0f), Vec3(2f, 0.8f, 0f), unitBox)
        assertEquals(0.3f, above.distanceM, 1e-5f)
        assertEquals(0.375f, above.fraction, 1e-5f)

        val beside = ClashGeometry.segmentToAabb(Vec3(-2f, 0f, 0.8f), Vec3(2f, 0f, 0.8f), unitBox)
        assertEquals(0.3f, beside.distanceM, 1e-5f)

        val far = ClashGeometry.segmentToAabb(Vec3(-2f, 2f, 2f), Vec3(2f, 2f, 2f), unitBox)
        assertEquals(1.5f * 1.4142135f, far.distanceM, 1e-4f)
    }

    @Test
    fun `segment to segment handles parallel, crossing, skew and points`() {
        val parallel = ClashGeometry.segmentToSegment(
            Vec3(0f, 0f, 0f),
            Vec3(1f, 0f, 0f),
            Vec3(0f, 1f, 0f),
            Vec3(1f, 1f, 0f),
        )
        assertEquals(1f, parallel.distanceM, 1e-6f)

        val crossing = ClashGeometry.segmentToSegment(
            Vec3(-1f, 0f, 0f),
            Vec3(1f, 0f, 0f),
            Vec3(0f, -1f, 0f),
            Vec3(0f, 1f, 0f),
        )
        assertEquals(0f, crossing.distanceM, 1e-6f)
        assertEquals(0.5f, crossing.firstFraction, 1e-6f)

        val skew = ClashGeometry.segmentToSegment(
            Vec3(0f, 0f, 0f),
            Vec3(1f, 0f, 0f),
            Vec3(0.5f, 1f, 1f),
            Vec3(0.5f, 2f, 1f),
        )
        assertEquals(1.4142135f, skew.distanceM, 1e-5f)

        val endToEnd = ClashGeometry.segmentToSegment(
            Vec3(0f, 0f, 0f),
            Vec3(1f, 0f, 0f),
            Vec3(2f, 0f, 0f),
            Vec3(3f, 0f, 0f),
        )
        assertEquals(1f, endToEnd.distanceM, 1e-6f)
        assertEquals(1f, endToEnd.firstFraction, 1e-6f)

        val points = ClashGeometry.segmentToSegment(
            Vec3(0f, 0f, 0f),
            Vec3(0f, 0f, 0f),
            Vec3(1f, 0f, 0f),
            Vec3(1f, 0f, 0f),
        )
        assertEquals(1f, points.distanceM, 1e-6f)
    }

    @Test
    fun `a run through a beam is a penetration and the hit points at the face`() {
        val report = checkClearance(
            listOf(Vec3(-2f, 0f, 0f), Vec3(2f, 0f, 0f)),
            listOf(Obstacle("beam", unitBox)),
        )
        assertTrue(report.valid)
        assertEquals(1, report.hits.size)
        val hit = report.hits[0]
        assertEquals(ClashKind.THROUGH_OBSTACLE, hit.kind)
        assertEquals("beam", hit.obstacleId)
        assertEquals(0, hit.segmentIndex)
        assertEquals(0f, hit.distanceM, 1e-6f)
        assertEquals(-0.5f, hit.atPoint.x, 1e-5f)
        assertEquals(0.375f, hit.segmentFraction, 1e-5f)
        assertEquals(0f, report.worstClearanceM ?: -1f, 1e-6f)
        assertTrue(report.hasClash)
    }

    @Test
    fun `a run that passes by closer than the clearance is a clash, and a further one is not`() {
        val route = listOf(Vec3(-2f, 0.8f, 0f), Vec3(2f, 0.8f, 0f))
        val relaxed = checkClearance(route, listOf(Obstacle("beam", unitBox)), defaultClearanceM = 0.05f)
        assertFalse(relaxed.hasClash)
        assertEquals(0.3f, relaxed.worstClearanceM ?: -1f, 1e-5f)

        val strict = checkClearance(route, listOf(Obstacle("beam", unitBox)), defaultClearanceM = 0.40f)
        assertTrue(strict.hasClash)
        assertEquals(ClashKind.BELOW_CLEARANCE, strict.hits[0].kind)
        assertEquals(0.3f, strict.hits[0].distanceM, 1e-5f)

        // An obstacle that carries its own clearance is held to that, not to the house default.
        val perObstacle = checkClearance(
            route,
            listOf(Obstacle("heating pipe", unitBox, clearanceM = 0.40f)),
            defaultClearanceM = 0.05f,
        )
        assertTrue(perObstacle.hasClash)
        assertEquals("heating pipe", perObstacle.hits[0].obstacleId)
    }

    @Test
    fun `hits are reported in route order, then by distance`() {
        val near = Obstacle("near", Aabb(Vec3(1.1f, -0.5f, -0.5f), Vec3(2.1f, 0.5f, 0.5f)))
        val far = Obstacle("far", Aabb(Vec3(0.9f, 0.9f, -0.5f), Vec3(1.9f, 1.9f, 0.5f)))
        val report = checkClearance(
            listOf(Vec3(0f, 0f, 0f), Vec3(3f, 0f, 0f)),
            listOf(far, near),
            defaultClearanceM = 1.0f,
        )
        assertEquals(2, report.hits.size)
        assertTrue(report.hits[0].distanceM <= report.hits[1].distanceM)
        assertEquals("near", report.hits[0].obstacleId)
        assertEquals(0f, report.hits[0].distanceM, 1e-6f)
    }

    @Test
    fun `nothing worth measuring is refused rather than reported as clear`() {
        val single = checkClearance(listOf(Vec3(0f, 0f, 0f)), listOf(Obstacle("beam", unitBox)))
        assertFalse(single.valid)
        assertTrue(single.hits.isEmpty())
        assertNull(single.worstClearanceM)
        assertEquals(0, single.checkedSegments)

        val notANumber = checkClearance(
            listOf(Vec3(0f, Float.NaN, 0f), Vec3(2f, 0f, 0f)),
            listOf(Obstacle("beam", unitBox)),
        )
        assertFalse(notANumber.valid)
        assertNull(notANumber.worstClearanceM)

        val insideOut = checkClearance(
            listOf(Vec3(-2f, 0f, 0f), Vec3(2f, 0f, 0f)),
            listOf(Obstacle("bad", Aabb(Vec3(0.5f, 0.5f, 0.5f), Vec3(-0.5f, -0.5f, -0.5f)))),
        )
        assertFalse(insideOut.valid)

        val negativeClearance = checkClearance(
            listOf(Vec3(-2f, 0f, 0f), Vec3(2f, 0f, 0f)),
            listOf(Obstacle("bad", unitBox, clearanceM = -0.1f)),
        )
        assertFalse(negativeClearance.valid)

        val negativeDefault = checkClearance(
            listOf(Vec3(-2f, 0f, 0f), Vec3(2f, 0f, 0f)),
            listOf(Obstacle("beam", unitBox)),
            defaultClearanceM = -0.1f,
        )
        assertFalse(negativeDefault.valid)
    }

    @Test
    fun `a run with no obstacles is checked and says it found nothing`() {
        val report = checkClearance(
            listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f)),
            emptyList(),
        )
        assertTrue(report.valid)
        assertTrue(report.hits.isEmpty())
        assertEquals(2, report.checkedSegments)
        assertEquals(0, report.checkedObstacles)
        assertNull(report.worstClearanceM)
        assertFalse(report.hasClash)
    }

    @Test
    fun `twelve edges are what a box contributes to the measurement`() {
        val edges = ClashGeometry.boundsEdges(unitBox)
        assertEquals(12, edges.size)
        // Every edge lies on the box: both ends share one coordinate pair with the bounds.
        edges.forEach { (a, b) ->
            assertTrue(unitBox.contains(a) && unitBox.contains(b))
        }
    }
}
