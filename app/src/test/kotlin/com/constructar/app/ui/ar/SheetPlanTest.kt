/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Irgendwie macht das alles keinen Sinn" (finding 268).
 *
 * Eight groups of controls at once, of which two are used every metre. These are the rules that
 * replace the pile with a hierarchy, and they are asserted rather than eyeballed because the pile
 * grew back once already — each finding added one more justified button.
 */
class SheetPlanTest {

    @Test
    fun `the primary action is always the operator's next step`() {
        assertEquals(
            SheetPlan.Primary.FIRST_POINT,
            SheetPlan.primary(MeasureMode.MEASURE, nodeCount = 0),
        )
        assertEquals(SheetPlan.Primary.POINT, SheetPlan.primary(MeasureMode.MEASURE, nodeCount = 1))
        assertEquals(SheetPlan.Primary.POINT, SheetPlan.primary(MeasureMode.MEASURE, nodeCount = 7))
    }

    @Test
    fun `a walkthrough's primary action is its exit, never a dead point button`() {
        listOf(0, 1, 5).forEach { count ->
            assertEquals(
                "capture with $count points",
                SheetPlan.Primary.END_CAPTURE,
                SheetPlan.primary(MeasureMode.CAPTURE, count),
            )
        }
    }

    @Test
    fun `the run's own actions exist while points are being set`() {
        assertTrue(SheetPlan.showsRunActions(MeasureMode.MEASURE))
        assertFalse(SheetPlan.showsRunActions(MeasureMode.CAPTURE))
    }

    @Test
    fun `the cable is a measuring setting, not a walkthrough setting`() {
        assertTrue(SheetPlan.showsCableChip(MeasureMode.MEASURE))
        assertFalse(SheetPlan.showsCableChip(MeasureMode.CAPTURE))
    }

    @Test
    fun `the saved-run card stays until the next point, and never invents one`() {
        assertTrue(SheetPlan.showsSavedRun(nodeCount = 0, savedLabel = "Kabel Ø10 1"))
        assertFalse(
            "a card about nothing is not a confirmation",
            SheetPlan.showsSavedRun(nodeCount = 0, savedLabel = null),
        )
        assertFalse(
            "the next run has started, so the card is history",
            SheetPlan.showsSavedRun(nodeCount = 1, savedLabel = "Kabel Ø10 1"),
        )
    }

    @Test
    fun `the rare tools start closed`() {
        // A drawer that is open on entry gets closed on entry, every time, forever.
        assertFalse(SheetPlan.TOOLS_OPEN_ON_ENTRY)
    }

    @Test
    fun `a walkthrough measures nothing - not by tap and not by drawing`() {
        // Finding 273: the sheet said "no points are set during a walkthrough" while the tap surface
        // still added nodes, and the run swung towards the reticle while the operator walked the
        // room. Two questions, one answer, because they are the same claim made twice.
        for (mode in MeasureMode.entries) {
            assertEquals(
                "tap and drawing agree in $mode",
                SheetPlan.tapPlacesPoint(mode),
                SheetPlan.runFollowsReticle(mode),
            )
            assertEquals(
                "and both agree with the mode's own definition",
                mode.setsPoints,
                SheetPlan.tapPlacesPoint(mode),
            )
        }
        assertTrue(SheetPlan.tapPlacesPoint(MeasureMode.MEASURE))
        assertFalse(SheetPlan.tapPlacesPoint(MeasureMode.CAPTURE))
        assertFalse(SheetPlan.runFollowsReticle(MeasureMode.CAPTURE))
    }
}
