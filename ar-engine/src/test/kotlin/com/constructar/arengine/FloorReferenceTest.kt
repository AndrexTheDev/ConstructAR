/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.hit.FloorDecision
import com.constructar.arengine.hit.FloorReference
import com.constructar.arengine.hit.FloorReferenceStatus
import com.constructar.arengine.hit.PlaneCandidate
import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which plane is the floor - the reference every mounting height in the trade is measured from.
 *
 * Three mistakes are one line apart and all silent: taking ARCore's origin for the floor (a socket
 * then prints at 1.4 m), taking the lowest plane for the floor (a ceiling seen through a stairwell
 * is lower than nothing) and taking the *ceiling* for the floor (the normal points up for a floor
 * and down for a ceiling). Each of them is a case here.
 */
class FloorReferenceTest {

    private fun plane(
        normal: Vec3,
        height: Float,
        isTracking: Boolean = true,
        subsumedBy: String? = null,
        id: String = "p",
        areaM2: Float = 16f,
    ): PlaneCandidate {
        val centre = Vec3(0f, height, 0f)
        return PlaneCandidate(
            id = id,
            polygon = listOf(
                Vec3(-2f, height, -2f),
                Vec3(2f, height, -2f),
                Vec3(2f, height, 2f),
                Vec3(-2f, height, 2f),
            ),
            normal = normal,
            offset = normal dot centre,
            center = centre,
            areaSquareMeters = areaM2,
            isTracking = isTracking,
            subsumedBy = subsumedBy,
            isVertical = false,
        )
    }

    private fun floor(at: Float) = plane(Vec3(0f, 1f, 0f), at)

    @Test
    fun `the floor below the phone is found`() {
        // A phone at chest height, a floor 1.4 m below it: the reference is the floor's own height.
        val height = FloorReference.heightFrom(listOf(floor(-1.4f)), viewerHeightM = 0f)

        assertEquals(-1.4f, height ?: 0f, 1e-4f)
    }

    @Test
    fun `a ceiling is not a floor`() {
        val ceiling = plane(Vec3(0f, -1f, 0f), 1.1f)

        assertNull(FloorReference.heightFrom(listOf(ceiling), viewerHeightM = 0f))
    }

    @Test
    fun `the highest floor below the phone wins`() {
        // Two storeys seen at once: the operator stands on the upper one.
        val planes = listOf(floor(-4.2f), floor(-1.4f), floor(-2.6f))

        assertEquals(-1.4f, FloorReference.heightFrom(planes, viewerHeightM = 0f) ?: 0f, 1e-4f)
    }

    @Test
    fun `a floor above the phone or too far below it is not this room's floor`() {
        // Above the viewer: that is a shelf or a table, not what he stands on.
        assertNull(FloorReference.heightFrom(listOf(floor(0.4f)), viewerHeightM = 0f))
        // A stairwell's ground floor: more than one room below the phone.
        assertNull(FloorReference.heightFrom(listOf(floor(-5.2f)), viewerHeightM = 0f, maxDropM = 3.5f))
    }

    @Test
    fun `untracked and merged planes are ignored`() {
        val planes = listOf(
            floor(-1.4f).copy(isTracking = false),
            floor(-1.2f).copy(subsumedBy = "p0"),
        )

        assertNull(FloorReference.heightFrom(planes, viewerHeightM = 0f))
    }

    @Test
    fun `a table top is not the floor, however much higher it is`() {
        // The operator holds the phone at 1.40 m. A table top 0.75 m off the floor is 0.65 m below
        // the camera - higher than the floor, upward-facing and tracked, which is every condition
        // the first implementation looked at. The floor is 1.40 m below and 16 m²; the table is
        // 0.75 m below and under 2 m², and it loses on all three criteria (finding 218).
        val table = plane(Vec3(0f, 1f, 0f), -0.65f, id = "table", areaM2 = 1.5f)
        val floor = plane(Vec3(0f, 1f, 0f), -1.4f, id = "floor", areaM2 = 16f)

        val decision = FloorReference.decide(listOf(table, floor), viewerHeightM = 0f)

        assertEquals(-1.4f, decision.heightM ?: 0f, 1e-4f)
        assertEquals(FloorReferenceStatus.TRACKED, decision.status)
        assertTrue(decision.usable)
        assertEquals("floor", decision.candidates.first().planeId)
        assertEquals(2, decision.candidateCount)
    }

    @Test
    fun `a lone table with no floor tracked is uncertain, not a floor`() {
        // One upward plane 0.75 m below the phone, and no floor in sight. There is nothing to
        // compare it with, so the score is 1.0 by construction - the distance from the expected
        // dropping height is the only evidence left, and 0.65 m off is not a floor.
        val table = plane(Vec3(0f, 1f, 0f), -0.75f)

        val decision = FloorReference.decide(listOf(table), viewerHeightM = 0f)

        assertEquals(FloorReferenceStatus.UNCERTAIN, decision.status)
        assertFalse(decision.usable)
        // The best guess still travels: the HUD asks the operator to point at the floor, and the
        // number is what a later walk will confirm or correct.
        assertEquals(-0.75f, decision.heightM ?: 0f, 1e-4f)
    }

    @Test
    fun `two indistinguishable planes are not a decision`() {
        // Two tracked fragments of the same height and the same area: the app cannot tell which is
        // the room, and printing one of them would be a coin toss with a mounting height on it.
        val left = plane(Vec3(0f, 1f, 0f), -1.4f, id = "left")
        val right = plane(Vec3(0f, 1f, 0f), -1.4f, id = "right")

        val decision = FloorReference.decide(listOf(left, right), viewerHeightM = 0f)

        assertEquals(FloorReferenceStatus.UNCERTAIN, decision.status)
        assertEquals(0f, decision.gap, 1e-4f)
    }

    @Test
    fun `the evidence behind the choice is kept`() {
        val floor = plane(Vec3(0f, 1f, 0f), -1.4f, id = "floor")
        val shelf = plane(Vec3(0f, 1f, 0f), -0.5f, id = "shelf", areaM2 = 0.4f)

        val decision = FloorReference.decide(listOf(shelf, floor), viewerHeightM = 0f)

        assertEquals(2, decision.candidates.size)
        assertTrue(decision.confidence in 0f..1f)
        assertTrue(decision.gap in 0f..1f)
        // Ordering is a rule, not ARCore's iteration order: score first, the deeper plane on a tie.
        assertEquals(listOf("floor", "shelf"), decision.candidates.map { it.planeId })
    }

    @Test
    fun `a plane a hand's width below the phone is a worktop`() {
        // 20 cm below the camera: inside the drop band's lower edge is 30 cm, below that is a
        // workbench the phone is lying on. A "floor" there puts every socket at two metres.
        val worktop = plane(Vec3(0f, 1f, 0f), -0.2f)

        assertNull(FloorReference.heightFrom(listOf(worktop), viewerHeightM = 0f))
    }

    @Test
    fun `only a tracked floor may be stored`() {
        // The screen hands a decision to the project only when `usable`, so this property is the
        // boundary between "the HUD may show it as a warning" and "every mounting height in the
        // room is measured from it".
        fun decision(status: FloorReferenceStatus, heightM: Float?) = FloorDecision(
            heightM = heightM,
            status = status,
            confidence = 0.9f,
            gap = 0.5f,
            candidateCount = 1,
        )

        assertTrue(decision(FloorReferenceStatus.TRACKED, -1.4f).usable)
        assertFalse(decision(FloorReferenceStatus.UNCERTAIN, -0.75f).usable)
        assertFalse(decision(FloorReferenceStatus.MISSING, null).usable)
        assertFalse(FloorDecision.MISSING.usable)
    }

    @Test
    fun `a broken viewer height finds nothing`() {
        assertNull(FloorReference.heightFrom(listOf(floor(-1.4f)), viewerHeightM = Float.NaN))
        assertNull(FloorReference.heightFrom(listOf(floor(-1.4f)), viewerHeightM = 0f, maxDropM = 0f))
    }
}
