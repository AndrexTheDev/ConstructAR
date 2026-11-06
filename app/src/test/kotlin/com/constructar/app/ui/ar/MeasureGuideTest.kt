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
 * "Walking through the room, and nothing seems to happen" (finding 265).
 *
 * These are the rules that make something happen on the glass: the gauge is never replaced by an
 * instruction, the first tap is explained as one end of a distance, and a grey button always has a
 * sentence next to it that names the count.
 */
class MeasureGuideTest {

    private fun scanned(
        nodeCount: Int = 0,
        canFinish: Boolean = false,
        closedLoop: Boolean = false,
        coaching: Boolean = false,
        scannerOn: Boolean = true,
        scanReady: Boolean = true,
        meshVersion: Int = 1,
        hitBlockBudget: Boolean = false,
        droppedShare: Float = 0f,
    ) = GuideState(
        nodeCount = nodeCount,
        canFinish = canFinish,
        closedLoop = closedLoop,
        coaching = coaching,
        scannerOn = scannerOn,
        scanReady = scanReady,
        meshVersion = meshVersion,
        hitBlockBudget = hitBlockBudget,
        droppedShare = droppedShare,
    )

    @Test
    fun `the gauge survives the coaching line`() {
        // The whole finding: on the device, tracking advice was shown *instead of* the gauge, and
        // the advice was the same sentence every frame. Both are owed.
        val lines = MeasureGuide.lines(scanned(coaching = true))
        assertEquals(GuideLine.COACHING, lines.first())
        assertTrue(lines.contains(GuideLine.SCAN_PROGRESS))
    }

    @Test
    fun `the gauge is shown even when nobody is coaching`() {
        assertTrue(MeasureGuide.lines(scanned()).contains(GuideLine.SCAN_PROGRESS))
    }

    @Test
    fun `an empty scan says so instead of showing a zero gauge`() {
        assertEquals(GuideLine.SCAN_EMPTY, MeasureGuide.scanLine(scanned(meshVersion = 0)))
    }

    @Test
    fun `a spent budget outranks a slow one`() {
        assertEquals(
            GuideLine.SCAN_FULL,
            MeasureGuide.scanLine(scanned(hitBlockBudget = true, droppedShare = 0.5f)),
        )
    }

    @Test
    fun `a refused frame is worth a sentence`() {
        assertEquals(GuideLine.SCAN_BEHIND, MeasureGuide.scanLine(scanned(droppedShare = BEHIND_SHARE)))
        assertEquals(GuideLine.SCAN_PROGRESS, MeasureGuide.scanLine(scanned(droppedShare = BEHIND_SHARE / 2)))
    }

    @Test
    fun `a phone without a scanner says nothing about scanning`() {
        assertNull(MeasureGuide.scanLine(scanned(scannerOn = false)))
        assertEquals(
            listOf(GuideLine.FIRST_POINT, GuideLine.BLOCK_RUN),
            MeasureGuide.lines(scanned(scannerOn = false)),
        )
    }

    @Test
    fun `the first tap is one end of a distance`() {
        assertEquals(GuideLine.FIRST_POINT, MeasureGuide.lines(scanned()).last { it != GuideLine.BLOCK_RUN })
    }

    @Test
    fun `the second tap is the distance itself`() {
        assertEquals(GuideLine.SECOND_POINT, MeasureGuide.lines(scanned(nodeCount = 1)).last { it != GuideLine.BLOCK_RUN })
    }

    @Test
    fun `two points can already be saved`() {
        val lines = MeasureGuide.lines(scanned(nodeCount = 2, canFinish = true))
        assertEquals(GuideLine.DISTANCE, lines.last { it != GuideLine.BLOCK_RING })
        // ...and the ring is the only thing still owed an explanation.
        assertEquals(GuideLine.BLOCK_RING, lines.last())
    }

    @Test
    fun `a ring is described as a ring`() {
        val lines = MeasureGuide.lines(scanned(nodeCount = 4, canFinish = true, closedLoop = true))
        assertEquals(GuideLine.RING, lines.last())
        assertTrue(lines.none { it == GuideLine.BLOCK_RING })
        assertTrue(lines.none { it == GuideLine.BLOCK_RUN })
    }

    @Test
    fun `every grey button has a line, and never two at once`() {
        // One reason at a time: the operator is reaching for one button, and a paragraph of two
        // refusals is how the first report got the buttons wrong in the first place.
        listOf(0, 1, 2, 3).forEach { count ->
            val lines = MeasureGuide.lines(scanned(nodeCount = count, canFinish = count >= 2))
            assertEquals(
                "point count $count",
                1,
                lines.count { it == GuideLine.BLOCK_RUN || it == GuideLine.BLOCK_RING },
            )
        }
    }

    @Test
    fun `the steps advance in order and stop at save`() {
        assertEquals(MeasureStep.SCAN, MeasureGuide.step(scanned(meshVersion = 0)))
        assertEquals(MeasureStep.POINTS, MeasureGuide.step(scanned(meshVersion = 0, nodeCount = 1)))
        assertEquals(MeasureStep.POINTS, MeasureGuide.step(scanned()))
        assertEquals(MeasureStep.FINISH, MeasureGuide.step(scanned(nodeCount = 2, canFinish = true)))
    }

    @Test
    fun `a phone that cannot scan starts at the points step`() {
        assertEquals(
            MeasureStep.POINTS,
            MeasureGuide.step(scanned(scannerOn = false, meshVersion = 0)),
        )
    }

    @Test
    fun `a refusal is quieter than a warning`() {
        // The block line explains a grey button; the scan warnings change what the operator does
        // with his hands right now. Same column, two volumes - otherwise both read as an alarm.
        assertEquals(GuideTone.QUIET, MeasureGuide.tone(GuideLine.BLOCK_RUN))
        assertEquals(GuideTone.QUIET, MeasureGuide.tone(GuideLine.BLOCK_RING))
        assertEquals(GuideTone.ATTENTION, MeasureGuide.tone(GuideLine.SCAN_FULL))
        assertEquals(GuideTone.ATTENTION, MeasureGuide.tone(GuideLine.COACHING))
        assertEquals(GuideTone.NORMAL, MeasureGuide.tone(GuideLine.SCAN_PROGRESS))
        assertEquals(GuideTone.NORMAL, MeasureGuide.tone(GuideLine.DISTANCE))
    }

    @Test
    fun `the strip marks the step being asked for`() {
        assertEquals(StepMark.NOW, measureStepMark(0, MeasureStep.SCAN))
        assertEquals(StepMark.LATER, measureStepMark(1, MeasureStep.SCAN))
        assertEquals(StepMark.DONE, measureStepMark(0, MeasureStep.POINTS))
        assertEquals(StepMark.NOW, measureStepMark(1, MeasureStep.POINTS))
        assertEquals(StepMark.DONE, measureStepMark(1, MeasureStep.FINISH))
        assertEquals(StepMark.NOW, measureStepMark(2, MeasureStep.FINISH))
    }

    @Test
    fun `the run threshold is the one the session finishes on`() {
        // `RouteSession` sets `canFinish = nodes.size >= 2`; `LeaveGuard` confirms a leave at the
        // same number. This copy exists to be asserted against both.
        assertEquals(LeaveGuard.MIN_NODES_FOR_A_RUN, MeasureGuide.NODES_FOR_A_RUN)
    }
}
