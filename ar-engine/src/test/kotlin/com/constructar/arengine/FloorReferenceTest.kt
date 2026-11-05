/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.hit.FloorReference
import com.constructar.arengine.hit.PlaneCandidate
import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            areaSquareMeters = 16f,
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
    fun `a broken viewer height finds nothing`() {
        assertNull(FloorReference.heightFrom(listOf(floor(-1.4f)), viewerHeightM = Float.NaN))
        assertNull(FloorReference.heightFrom(listOf(floor(-1.4f)), viewerHeightM = 0f, maxDropM = 0f))
    }
}
