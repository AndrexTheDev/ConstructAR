/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The headline is the number being decided (finding 272).
 *
 * With one point the HUD led with `0.00 m` in the largest type on the screen - a total that cannot
 * be anything else yet - while the distance the operator was walking towards grew underneath it in
 * body text. These tests pin the swap, the guard that keeps an empty headline off the glass, and
 * the colour claim that separates a length he aimed at from one he tapped.
 */
class HudReadoutTest {

    @Test
    fun `with one point the segment is the headline`() {
        assertEquals(HudHeadline.SEGMENT, HudReadout.headline(nodeCount = 1, hasLiveSegment = true))
    }

    @Test
    fun `with a run the total is the headline again`() {
        assertEquals(HudHeadline.TOTAL, HudReadout.headline(nodeCount = 2, hasLiveSegment = true))
        assertEquals(HudHeadline.TOTAL, HudReadout.headline(nodeCount = 9, hasLiveSegment = true))
    }

    @Test
    fun `an empty run has nothing to lead with but its total`() {
        // Zero is honest here: the sheet's primary action says what to do, and a headline that
        // jumps to the room total would be a different measurement wearing the run's type.
        assertEquals(HudHeadline.TOTAL, HudReadout.headline(nodeCount = 0, hasLiveSegment = true))
        assertEquals(HudHeadline.TOTAL, HudReadout.headline(nodeCount = 0, hasLiveSegment = false))
    }

    @Test
    fun `a reticle with nothing under it never leaves an empty headline`() {
        // One point, no surface under the crosshair: no segment, so no segment headline. The total
        // is zero, and a zero in the measured colour is still better than a blank line.
        assertEquals(HudHeadline.TOTAL, HudReadout.headline(nodeCount = 1, hasLiveSegment = false))
    }

    @Test
    fun `a provisional segment is never inked like a measured one`() {
        assertEquals(HudInk.PROVISIONAL, HudReadout.ink(HudHeadline.SEGMENT))
        assertEquals(HudInk.MEASURED, HudReadout.ink(HudHeadline.TOTAL))
        // Two roles, two colours: if these ever collapse, the segment starts claiming a commitment
        // nobody made, which is the one claim this app must never print.
        assertTrue(HudInk.entries.size == 2)
    }

    @Test
    fun `the label in the room never prints a zero`() {
        // Nothing to say with no point, and nothing to say with one: both totals are 0.00 m.
        assertEquals(false, HudReadout.labelsLastPoint(0))
        assertEquals(false, HudReadout.labelsLastPoint(1))
        assertEquals(true, HudReadout.labelsLastPoint(2))
        assertEquals(true, HudReadout.labelsLastPoint(7))
    }
}
