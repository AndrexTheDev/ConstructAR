/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No grey button without a sentence (finding 271).
 *
 * The screen has one rule about disabled controls - they say why - and it was being kept for the
 * ring and broken for the other two tools in the same drawer. These tests pin the thresholds to the
 * ones that actually gate the buttons, and pin the rule itself over the whole state space, because a
 * rule that is only true where somebody remembered to look is not a rule.
 */
class ToolsPlanTest {

    @Test
    fun `the ring waits for the same three points the rest of the screen does`() {
        assertEquals(
            "one threshold, not two copies of it",
            ActionGating.NODES_FOR_A_LOOP,
            3,
        )
        assertEquals(ToolState(enabled = false, reason = ToolReason.RING_NEEDS_POINTS), ToolsPlan.ring(0))
        assertEquals(ToolState(enabled = false, reason = ToolReason.RING_NEEDS_POINTS), ToolsPlan.ring(2))
        assertEquals(ToolState(enabled = true), ToolsPlan.ring(3))
        assertEquals(ToolState(enabled = true), ToolsPlan.ring(9))
    }

    @Test
    fun `the same-place check needs a room before it can compare anything`() {
        val withoutRoom = ToolsPlan.loopCheck(0)
        assertEquals(false, withoutRoom.enabled)
        assertEquals(
            "the sentence has to name the walkthrough - nothing else can make the button work",
            ToolReason.LOOP_NEEDS_ROOM,
            withoutRoom.reason,
        )
        assertEquals(ToolState(enabled = true), ToolsPlan.loopCheck(1))
    }

    @Test
    fun `a phone without marker knobs says so instead of greying out silently`() {
        val unavailable = ToolsPlan.marker(available = false)
        assertEquals(false, unavailable.enabled)
        assertEquals(ToolReason.MARKER_NOT_AVAILABLE, unavailable.reason)
        assertEquals(ToolState(enabled = true), ToolsPlan.marker(available = true))
    }

    @Test
    fun `every disabled tool carries a reason and no usable one does`() {
        // The invariant, over the state space rather than over the examples above.
        val states = buildList {
            for (points in 0..6) add(ToolsPlan.ring(points))
            for (nodes in 0..4) add(ToolsPlan.loopCheck(nodes))
            for (available in listOf(true, false)) add(ToolsPlan.marker(available))
        }
        for (state in states) {
            if (state.enabled) {
                assertNull("a usable tool has nothing to explain", state.reason)
            } else {
                assertTrue("a grey tool must say why", state.reason != null)
            }
        }
    }

    @Test
    fun `a tool cannot be built as grey without a reason`() {
        var refused = false
        try {
            ToolState(enabled = false)
        } catch (expected: IllegalArgumentException) {
            refused = true
        }
        assertTrue("the type refuses the silent grey button", refused)
    }
}
