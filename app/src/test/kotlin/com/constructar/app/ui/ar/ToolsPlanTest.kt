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
 *
 * The same lesson cost a second round (finding 276): the drawer was fixed and the action row
 * directly above it was not, because the rule had been written into the three tools instead of into
 * the shape every button has. Undo is the state space's newest member, and the sweep below covers it
 * from the first day rather than from the next bug report.
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
            for (points in 0..4) add(ToolsPlan.undo(points))
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
    fun `undo on an empty run says so instead of going quiet`() {
        // The button is grey the moment the screen opens - every other grey control in the sheet
        // had been given a sentence by findings 258, 265 and 271, and this one was missed because
        // the rule had been taught to the drawer rather than to the type (finding 276).
        val empty = ToolsPlan.undo(0)
        assertEquals(false, empty.enabled)
        assertEquals(ToolReason.UNDO_NOTHING_TO_REMOVE, empty.reason)
        assertEquals(ToolState(enabled = true), ToolsPlan.undo(1))
        assertEquals(ToolState(enabled = true), ToolsPlan.undo(4))
    }

    @Test
    fun `undo goes usable exactly when the screen stops asking for the first point`() {
        // Two answers about one state: the guide asks for the first point and the button that
        // would take a point back is grey. If either moves without the other, the sheet tells the
        // operator to do something it has already done - or offers to undo what never happened.
        val fresh = GuideState(nodeCount = 0)
        assertTrue("a fresh sheet asks for the first point", GuideLine.FIRST_POINT in MeasureGuide.lines(fresh))
        assertEquals(false, ToolsPlan.undo(fresh.nodeCount).enabled)

        val withOne = GuideState(nodeCount = 1)
        assertTrue("the first point is no longer asked for", GuideLine.FIRST_POINT !in MeasureGuide.lines(withOne))
        assertEquals(true, ToolsPlan.undo(withOne.nodeCount).enabled)
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
