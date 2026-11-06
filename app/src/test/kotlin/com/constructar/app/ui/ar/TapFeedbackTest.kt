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
 * The rule behind the tap feedback (finding 269).
 *
 * Every device report about this screen has the same shape - *"nichts passiert"*, *"nicht
 * antippbar"* - and every one of them was a tap that did something the screen did not admit to. So
 * the answer is not another label: it is a fixed precedence between the three results a tap can
 * have, and a single slot that either reports the last result or asks for one action, never both.
 * Both halves are pinned here, because both are the kind of rule that rots silently.
 */
class TapFeedbackTest {

    @Test
    fun `a tap with no surface is a miss, whatever else is true`() {
        assertEquals(TapEcho.MISS, TapFeedback.echo(hasSurface = false, added = false, snapped = false))
        // A snap cannot exist without a hit, but the function must not depend on the caller
        // getting that right: the miss is the strongest fact and wins.
        assertEquals(TapEcho.MISS, TapFeedback.echo(hasSurface = false, added = false, snapped = true))
    }

    @Test
    fun `a hit the run refused is never called a point`() {
        // The silent refusal (`TOO_CLOSE`, `UNPINNED`): the hit test succeeded, the run did not
        // grow, and before this the only evidence was a line in the scrolling block - or nothing.
        assertEquals(TapEcho.REFUSED, TapFeedback.echo(hasSurface = true, added = false, snapped = false))
        assertEquals(
            "a refused tap that also snapped is still a refusal",
            TapEcho.REFUSED,
            TapFeedback.echo(hasSurface = true, added = false, snapped = true),
        )
    }

    @Test
    fun `a snapped point says it was snapped, a plain one does not`() {
        assertEquals(TapEcho.SNAPPED, TapFeedback.echo(hasSurface = true, added = true, snapped = true))
        assertEquals(TapEcho.PLACED, TapFeedback.echo(hasSurface = true, added = true, snapped = false))
    }

    @Test
    fun `the slot reports the result first and the request second`() {
        assertEquals(
            "the answer to what he just did outranks what he should do next",
            TapSlot.RESULT,
            TapFeedback.slot(resultVisible = true, holding = true),
        )
        assertEquals(TapSlot.HOLD, TapFeedback.slot(resultVisible = false, holding = true))
        assertEquals(
            "silence is the resting state - a permanent 'ready' would be decoration",
            TapSlot.NONE,
            TapFeedback.slot(resultVisible = false, holding = false),
        )
    }

    @Test
    fun `a result outlives the tap but not the next one`() {
        assertTrue("long enough to read a sentence", TapFeedback.RESULT_VISIBLE_MS >= 1_500L)
        assertTrue(
            "short enough that it is the answer to the last tap, not a permanent line",
            TapFeedback.RESULT_VISIBLE_MS <= 4_000L,
        )
    }
}
