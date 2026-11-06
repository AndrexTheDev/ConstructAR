/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.arengine.render.SceneViewMaterialProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Walking through the room, and nothing seems to happen" (finding 265) and "you cannot see what
 * has been scanned — and that is not the same as setting a measuring point" (finding 267).
 *
 * These are the rules that make something visible on the glass: the gauge is never replaced by an
 * instruction, the first tap is explained as one end of a distance, a grey button always has a
 * sentence next to it that names the count — and the two modes never borrow each other's words.
 */
class MeasureGuideTest {

    private fun state(
        mode: MeasureMode,
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
        mode = mode,
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

    private fun capture(
        nodeCount: Int = 0,
        canFinish: Boolean = false,
        coaching: Boolean = false,
        scannerOn: Boolean = true,
        scanReady: Boolean = true,
        meshVersion: Int = 1,
        hitBlockBudget: Boolean = false,
        droppedShare: Float = 0f,
    ) = state(
        mode = MeasureMode.CAPTURE,
        nodeCount = nodeCount,
        canFinish = canFinish,
        coaching = coaching,
        scannerOn = scannerOn,
        scanReady = scanReady,
        meshVersion = meshVersion,
        hitBlockBudget = hitBlockBudget,
        droppedShare = droppedShare,
    )

    private fun measuring(
        nodeCount: Int = 0,
        canFinish: Boolean = false,
        closedLoop: Boolean = false,
        coaching: Boolean = false,
    ) = state(
        mode = MeasureMode.MEASURE,
        nodeCount = nodeCount,
        canFinish = canFinish,
        closedLoop = closedLoop,
        coaching = coaching,
    )

    // ── Finding 265: the gauge survives the coaching line ───────────────────────────────────────

    @Test
    fun `the gauge is still there while the tracking advisor is talking`() {
        // The whole of finding 265: on the device the advice was shown *instead of* the gauge, and
        // the advice was the same sentence every frame.
        assertEquals(GuideLine.COACHING, MeasureGuide.lines(capture(coaching = true)).first())
        assertEquals(GuideLine.SCAN_PROGRESS, MeasureGuide.scanLine(capture(coaching = true)))
    }

    @Test
    fun `an empty scan says so instead of showing a zero gauge`() {
        assertEquals(GuideLine.SCAN_EMPTY, MeasureGuide.scanLine(capture(meshVersion = 0)))
    }

    @Test
    fun `a spent budget outranks a slow one`() {
        assertEquals(
            GuideLine.SCAN_FULL,
            MeasureGuide.scanLine(capture(hitBlockBudget = true, droppedShare = 0.5f)),
        )
    }

    @Test
    fun `a refused frame is worth a sentence`() {
        assertEquals(GuideLine.SCAN_BEHIND, MeasureGuide.scanLine(capture(droppedShare = BEHIND_SHARE)))
        assertEquals(
            GuideLine.SCAN_PROGRESS,
            MeasureGuide.scanLine(capture(droppedShare = BEHIND_SHARE / 2)),
        )
    }

    @Test
    fun `a phone without a scanner says nothing about scanning`() {
        assertNull(MeasureGuide.scanLine(capture(scannerOn = false)))
        assertNull(MeasureGuide.scanNotice(capture(scannerOn = false)))
    }

    @Test
    fun `only the states the operator can act on become a line of their own`() {
        // "Nothing captured yet" and "41 % captured" are what the pinned gauge is for. Repeating
        // them as scrolling text was the duplication that made the old screen unreadable.
        assertNull(MeasureGuide.scanNotice(capture(meshVersion = 0)))
        assertNull(MeasureGuide.scanNotice(capture()))
        assertEquals(GuideLine.SCAN_FULL, MeasureGuide.scanNotice(capture(hitBlockBudget = true)))
        assertEquals(GuideLine.SCAN_BEHIND, MeasureGuide.scanNotice(capture(droppedShare = 0.9f)))
        assertEquals(
            GuideLine.SCAN_UNAVAILABLE,
            MeasureGuide.scanNotice(capture(scanReady = false)),
        )
    }

    // ── Finding 267: the two modes never borrow each other's words ─────────────────────────────

    @Test
    fun `a walkthrough never asks for a point`() {
        val pointLines = setOf(
            GuideLine.FIRST_POINT,
            GuideLine.SECOND_POINT,
            GuideLine.DISTANCE,
            GuideLine.RING,
            GuideLine.BLOCK_RUN,
        )
        listOf(0, 1, 2, 5).forEach { count ->
            listOf(false, true).forEach { finish ->
                val lines = MeasureGuide.lines(capture(nodeCount = count, canFinish = finish))
                assertTrue(
                    "capture with $count points must not talk about points: $lines",
                    lines.none { it in pointLines },
                )
                assertEquals(
                    "the walkthrough instruction is always the last line",
                    GuideLine.CAPTURE_WALK,
                    lines.last(),
                )
            }
        }
    }

    @Test
    fun `measuring never says anything about a scan`() {
        val scanLines = setOf(
            GuideLine.SCAN_EMPTY,
            GuideLine.SCAN_PROGRESS,
            GuideLine.SCAN_FULL,
            GuideLine.SCAN_BEHIND,
            GuideLine.SCAN_UNAVAILABLE,
            GuideLine.CAPTURE_WALK,
        )
        listOf(0, 1, 2, 4).forEach { count ->
            val lines = MeasureGuide.lines(
                measuring(nodeCount = count, canFinish = count >= 2, coaching = true),
            )
            assertTrue("measuring must not talk about the room: $lines", lines.none { it in scanLines })
        }
        assertNull(
            "the gauge has no meaning while points are being set",
            MeasureGuide.scanLine(measuring()),
        )
    }

    @Test
    fun `exactly one mode draws the room and exactly one sets points`() {
        assertTrue(MeasureMode.CAPTURE.showsRoomOverlay)
        assertFalse(MeasureMode.CAPTURE.setsPoints)
        assertTrue(MeasureMode.MEASURE.setsPoints)
        assertFalse(MeasureMode.MEASURE.showsRoomOverlay)
    }

    @Test
    fun `the legend names the colour the room is actually drawn in`() {
        // Two constants in two modules that must be the same colour: the swatch in the legend and
        // the surface over the camera. This is the test that stops them drifting apart.
        assertEquals(SceneViewMaterialProvider.SCAN_OVERLAY_ARGB, ModeStyle.CAPTURE_OVERLAY_ARGB)
        assertEquals(
            "the swatch is the same hue, only opaque",
            ModeStyle.CAPTURE_OVERLAY_ARGB or 0xFF000000.toInt(),
            ModeStyle.CAPTURE_ARGB,
        )
    }

    @Test
    fun `the room colour is not one of the service colours`() {
        // A turquoise that already belongs to a conduit would make the captured room look like
        // installed hardware - the one thing the overlay must never do.
        assertFalse(
            "the room colour collides with a service preset",
            com.constructar.core.model.ConduitPresets.all
                .any { it.colorArgb == ModeStyle.CAPTURE_ARGB },
        )
    }

    // ── Finding 265: the first tap, the second tap, and the grey buttons ───────────────────────

    @Test
    fun `the first tap is one end of a distance`() {
        assertEquals(GuideLine.FIRST_POINT, MeasureGuide.lines(measuring()).last { it != GuideLine.BLOCK_RUN })
    }

    @Test
    fun `the second tap is the distance itself`() {
        assertEquals(
            GuideLine.SECOND_POINT,
            MeasureGuide.lines(measuring(nodeCount = 1)).last { it != GuideLine.BLOCK_RUN },
        )
    }

    @Test
    fun `two points can already be saved, and nothing is left to refuse`() {
        // The ring moved into the tools drawer in finding 268, so the sheet owes exactly one
        // refusal - the one about the button that is actually on the glass.
        val lines = MeasureGuide.lines(measuring(nodeCount = 2, canFinish = true))
        assertEquals(GuideLine.DISTANCE, lines.last())
        assertTrue(lines.none { it == GuideLine.BLOCK_RUN })
    }

    @Test
    fun `a ring is described as a ring`() {
        val lines = MeasureGuide.lines(measuring(nodeCount = 4, canFinish = true, closedLoop = true))
        assertEquals(GuideLine.RING, lines.last())
        assertTrue(lines.none { it == GuideLine.BLOCK_RUN })
    }

    @Test
    fun `the grey save button is explained while it is grey, and only then`() {
        listOf(0, 1).forEach { count ->
            val blocks = MeasureGuide.lines(measuring(nodeCount = count, canFinish = false))
                .filter { it == GuideLine.BLOCK_RUN }
            assertEquals("point count $count", listOf(GuideLine.BLOCK_RUN), blocks)
        }
        listOf(2, 3).forEach { count ->
            val lines = MeasureGuide.lines(measuring(nodeCount = count, canFinish = true))
            assertTrue("point count $count", lines.none { it == GuideLine.BLOCK_RUN })
        }
    }

    // ── The strip ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a walkthrough shows one chip, a measurement shows two`() {
        assertEquals(
            listOf(MeasureStep.SCAN to StepMark.NOW),
            MeasureGuide.strip(capture()),
        )
        assertEquals(
            listOf(MeasureStep.POINTS to StepMark.NOW, MeasureStep.FINISH to StepMark.LATER),
            MeasureGuide.strip(measuring(nodeCount = 1)),
        )
        assertEquals(
            listOf(MeasureStep.POINTS to StepMark.DONE, MeasureStep.FINISH to StepMark.NOW),
            MeasureGuide.strip(measuring(nodeCount = 2, canFinish = true)),
        )
    }

    @Test
    fun `the step being asked for follows the mode`() {
        assertEquals(MeasureStep.SCAN, MeasureGuide.currentStep(capture()))
        assertEquals(MeasureStep.POINTS, MeasureGuide.currentStep(measuring(nodeCount = 1)))
        assertEquals(
            MeasureStep.FINISH,
            MeasureGuide.currentStep(measuring(nodeCount = 2, canFinish = true)),
        )
        // The scan step is never "done" in measuring mode: it may have been skipped entirely, and
        // a chip that says done over a scan that never ran is the lie finding 267 is about.
        assertTrue(MeasureGuide.strip(measuring()).none { it.first == MeasureStep.SCAN })
    }

    @Test
    fun `the strip marks the step being asked for`() {
        assertEquals(StepMark.NOW, measureStepMark(MeasureStep.SCAN, MeasureStep.SCAN))
        assertEquals(StepMark.LATER, measureStepMark(MeasureStep.POINTS, MeasureStep.SCAN))
        assertEquals(StepMark.DONE, measureStepMark(MeasureStep.POINTS, MeasureStep.FINISH))
        assertEquals(StepMark.NOW, measureStepMark(MeasureStep.FINISH, MeasureStep.FINISH))
    }

    @Test
    fun `a refusal is quieter than a warning`() {
        assertEquals(GuideTone.QUIET, MeasureGuide.tone(GuideLine.BLOCK_RUN))
        assertEquals(GuideTone.ATTENTION, MeasureGuide.tone(GuideLine.SCAN_FULL))
        assertEquals(GuideTone.ATTENTION, MeasureGuide.tone(GuideLine.COACHING))
        assertEquals(GuideTone.NORMAL, MeasureGuide.tone(GuideLine.SCAN_PROGRESS))
        assertEquals(GuideTone.NORMAL, MeasureGuide.tone(GuideLine.DISTANCE))
        assertEquals(GuideTone.NORMAL, MeasureGuide.tone(GuideLine.CAPTURE_WALK))
    }

    @Test
    fun `the run threshold is the one the session finishes on`() {
        assertEquals(LeaveGuard.MIN_NODES_FOR_A_RUN, MeasureGuide.NODES_FOR_A_RUN)
    }
}
