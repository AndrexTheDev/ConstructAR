/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.arengine.hit.SnapFeatureKind
import com.constructar.arengine.hit.SnapInfo
import com.constructar.app.ui.snapMovedRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The number a snapped reticle states, and the four sentences it can choose from.
 *
 * The snap moves a point by up to ten centimetres and used to do it without a word. What the HUD
 * says now is a distance in centimetres and the kind of feature it landed on - and both halves have
 * to be boringly predictable: digits from the same formatter as every other length in the app, and
 * nothing at all when the distance is one a sentence cannot carry (finding 213).
 */
class SnapReadingTest {

    private fun snap(distanceM: Float, kind: SnapFeatureKind = SnapFeatureKind.CORNER) =
        SnapInfo(kind = kind, distanceM = distanceM)

    @Test
    fun `a moved point states its distance in centimetres`() {
        // 4.2 cm of movement reads as a number an operator can check against his own aim.
        assertEquals("4.2", SnapReading.moved(snap(0.042f)))
        // The tolerance's own edge, which is the largest move there can be.
        assertEquals("10.0", SnapReading.moved(snap(0.10f)))
        // A move of nothing is still a move: the point was already on the corner.
        assertEquals("0.0", SnapReading.moved(snap(0f)))
    }

    @Test
    fun `a distance that is not a length is not printed`() {
        // A NaN or a negative would print "NaN cm" or "-1.0 cm" on a live HUD; the marker and the
        // line both disappear instead, and the reticle does not claim to have moved.
        assertNull(SnapReading.moved(snap(Float.NaN)))
        assertNull(SnapReading.moved(snap(Float.POSITIVE_INFINITY)))
        assertNull(SnapReading.moved(snap(-0.01f)))
    }

    @Test
    fun `no snap means no line and no marker`() {
        assertNull(SnapReading.moved(null))
    }

    @Test
    fun `every kind of feature has its own sentence`() {
        val ids = SnapFeatureKind.entries.map { kind -> kind.snapMovedRes() }

        assertEquals(SnapFeatureKind.entries.size, ids.toSet().size)
        // ...and none of them is the fallback of another kind by accident.
        assertNotEquals(ids[0], ids[1])
    }
}
