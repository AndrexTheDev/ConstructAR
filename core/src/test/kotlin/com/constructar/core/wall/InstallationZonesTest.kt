/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DIN 18015 snapping is the difference between "where the fitter drilled" and "where the standard
 * says", so the cases that matter are the refusals as much as the snaps.
 *
 * A silent snap is the defect this suite exists to prevent: a point 25 cm off the socket line
 * becoming a socket at 30 cm on a drawing that claims the standard, with nothing anywhere saying
 * the measurement was not trusted.
 */
class InstallationZonesTest {

    @Test
    fun `the three heights are the standard's`() {
        assertEquals(0.30f, SOCKET_HEIGHT_M, 1e-6f)
        assertEquals(1.05f, SWITCH_HEIGHT_M, 1e-6f)
        assertEquals(0.15f, CEILING_OUTLET_DROP_M, 1e-6f)
    }

    @Test
    fun `a point near the socket line snaps onto it`() {
        val snap = snapToInstallationZone(0.28f, ceilingHeightM = 2.50f)
        assertTrue(snap.accepted)
        assertEquals(InstallationZone.SOCKET, snap.zone)
        assertEquals(0.30f, snap.targetHeightM, 1e-6f)
        assertEquals(-0.02f, snap.offsetM, 1e-5f)
        assertNull(snap.rejection)
    }

    @Test
    fun `a point near the switch line snaps onto it`() {
        val snap = snapToInstallationZone(1.02f, ceilingHeightM = 2.50f)
        assertTrue(snap.accepted)
        assertEquals(InstallationZone.SWITCH, snap.zone)
        assertEquals(1.05f, snap.targetHeightM, 1e-6f)
        assertEquals(-0.03f, snap.offsetM, 1e-5f)
    }

    @Test
    fun `the ceiling line follows the ceiling instead of being a fixed height`() {
        val standard = snapToInstallationZone(2.35f, ceilingHeightM = 2.50f)
        assertEquals(InstallationZone.CEILING, standard.zone)
        assertEquals(2.35f, standard.targetHeightM, 1e-5f)

        // A 3.20 m room: the ceiling outlet is at 3.05 m, and 2.35 m is not it any more.
        val tall = snapToInstallationZone(3.05f, ceilingHeightM = 3.20f)
        assertEquals(InstallationZone.CEILING, tall.zone)
        assertEquals(3.05f, tall.targetHeightM, 1e-5f)
        val wrongLine = snapToInstallationZone(2.35f, ceilingHeightM = 3.20f)
        assertFalse(wrongLine.accepted)
        assertEquals(ZoneRejection.OUT_OF_TOLERANCE, wrongLine.rejection)
    }

    @Test
    fun `a point too far from every line is refused and names the line it was near`() {
        val snap = snapToInstallationZone(0.55f, ceilingHeightM = 2.50f)
        assertFalse(snap.accepted)
        assertNull(snap.zone)
        assertEquals(0.30f, snap.targetHeightM, 1e-6f)
        assertEquals(0.25f, snap.offsetM, 1e-5f)
        assertEquals(ZoneRejection.OUT_OF_TOLERANCE, snap.rejection)

        // 1.30 m is 25 cm above the switch line and 100 cm below the ceiling one.
        val high = snapToInstallationZone(1.30f, ceilingHeightM = 2.50f)
        assertEquals(1.05f, high.targetHeightM, 1e-6f)
        assertEquals(0.25f, high.offsetM, 1e-5f)
        assertEquals(ZoneRejection.OUT_OF_TOLERANCE, high.rejection)
    }

    @Test
    fun `a point outside the room is refused for the room, not for the tolerance`() {
        val below = snapToInstallationZone(-0.20f, ceilingHeightM = 2.50f)
        assertEquals(ZoneRejection.BELOW_FLOOR, below.rejection)
        assertFalse(below.accepted)

        val above = snapToInstallationZone(2.60f, ceilingHeightM = 2.50f)
        assertEquals(ZoneRejection.ABOVE_CEILING, above.rejection)
        assertEquals(2.35f, above.targetHeightM, 1e-5f)
    }

    @Test
    fun `a height that is not a number is not a measurement`() {
        assertEquals(ZoneRejection.NON_FINITE, snapToInstallationZone(Float.NaN, 2.50f).rejection)
        assertEquals(ZoneRejection.NON_FINITE, snapToInstallationZone(1f, Float.NaN).rejection)
        assertEquals(
            ZoneRejection.NON_FINITE,
            snapToInstallationZone(1f, 2.50f, ZoneSnapOptions(toleranceM = -0.1f)).rejection,
        )
        assertEquals(
            ZoneRejection.NON_FINITE,
            snapToInstallationZone(1f, 2.50f, ZoneSnapOptions(toleranceM = Float.NaN)).rejection,
        )
    }

    @Test
    fun `a zero tolerance keeps only points exactly on a line`() {
        val exact = snapToInstallationZone(0.30f, 2.50f, ZoneSnapOptions(toleranceM = 0f))
        assertEquals(InstallationZone.SOCKET, exact.zone)
        val near = snapToInstallationZone(0.31f, 2.50f, ZoneSnapOptions(toleranceM = 0f))
        assertFalse(near.accepted)
        assertEquals(ZoneRejection.OUT_OF_TOLERANCE, near.rejection)
    }

    @Test
    fun `a point exactly between two lines goes to the lower one`() {
        // 0.675 m is the midpoint of the socket and switch lines. The float arithmetic makes one
        // distance a rounding error smaller than the other, so the tie is decided by an epsilon
        // and not by which subtraction happened to round better.
        val snap = snapToInstallationZone(0.675f, 2.50f, ZoneSnapOptions(toleranceM = 0.50f))
        assertEquals(InstallationZone.SOCKET, snap.zone)
    }

    @Test
    fun `a room lower than the drop has no ceiling zone to offer`() {
        val snap = snapToInstallationZone(0.10f, ceilingHeightM = 0.14f)
        assertFalse(snap.accepted)
        assertEquals(ZoneRejection.OUT_OF_TOLERANCE, snap.rejection)
        assertEquals(0.30f, snap.targetHeightM, 1e-6f)

        val plan = classifyInstallations(listOf(Vec3(0f, 0.10f, 0f)), ceilingHeightM = 0.14f)
        assertFalse(plan.ceilingZoneAvailable)
        assertTrue(classifyInstallations(listOf(Vec3(0f, 1f, 0f)), 2.50f).ceilingZoneAvailable)
    }

    @Test
    fun `a room's points are counted per zone and nothing is dropped`() {
        val plan = classifyInstallations(
            listOf(
                Vec3(0f, 0.30f, 0f),
                Vec3(1f, 1.05f, 0f),
                Vec3(2f, 2.35f, 0f),
                Vec3(3f, 0.60f, 0f),
            ),
            ceilingHeightM = 2.50f,
        )
        assertTrue(plan.valid)
        assertEquals(4, plan.assignments.size)
        assertEquals(1, plan.socketCount)
        assertEquals(1, plan.switchCount)
        assertEquals(1, plan.ceilingCount)
        assertEquals(1, plan.rejectedCount)
        assertEquals(2, plan.assignments[2].index)
        assertEquals(InstallationZone.CEILING, plan.assignments[2].snap.zone)
        assertNull(plan.assignments[3].snap.zone)
    }

    @Test
    fun `a non-finite point is counted as refused rather than thrown out`() {
        val plan = classifyInstallations(listOf(Vec3(0f, Float.NaN, 0f)), 2.50f)
        assertEquals(1, plan.assignments.size)
        assertEquals(1, plan.rejectedCount)
        assertEquals(ZoneRejection.NON_FINITE, plan.assignments[0].snap.rejection)
    }

    @Test
    fun `no points is not a plan`() {
        val plan = classifyInstallations(emptyList(), 2.50f)
        assertFalse(plan.valid)
        assertTrue(plan.assignments.isEmpty())
        assertEquals(0, plan.rejectedCount)
    }

    @Test
    fun `an accepted height is always inside the room`() {
        // The property a drawing depends on: a snapped height can never be below the floor or above
        // the ceiling, whatever the ceiling is.
        var ceiling = 2.10f
        var checked = 0
        while (ceiling <= 3.30f) {
            var height = 0f
            while (height <= ceiling) {
                val snap = snapToInstallationZone(height, ceiling)
                if (snap.accepted) {
                    assertTrue(snap.targetHeightM >= 0f)
                    assertTrue(snap.targetHeightM <= ceiling)
                    checked++
                }
                height += 0.05f
            }
            ceiling += 0.10f
        }
        assertTrue(checked > 100)
    }
}
