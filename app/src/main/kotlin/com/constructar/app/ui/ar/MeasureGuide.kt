/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * What the measure screen says, in which order, and why (finding 265).
 *
 * The device report was one sentence — *"man geht langsam durch den Raum, aber dabei scheint nichts
 * zu passieren"* — and the code explains it exactly. The HUD picked **one** line:
 *
 * ```
 * val statusLine = guidanceText ?: when (hint) { … ScanHint.PROGRESS -> scanProgressText … }
 * ```
 *
 * On a Pixel 3a in a hallway the tracking advisor answers `SEARCHING` or `LIMITED` most of the
 * time, and both of those sentences end in "move slowly". So the gauge that was climbing — the one
 * number that proves the walk is doing something — was the branch that never ran. The operator
 * walked, the app showed a fixed instruction, and no number ever moved: from the outside, nothing
 * happened, and he was right.
 *
 * A single `?:` between coaching and evidence is the bug. Coaching is *an instruction*, the gauge is
 * *the answer*, and a screen owes both: at most one coaching line, then the scan line, always, then
 * what the run currently is, then why the buttons that are grey are grey. This object decides that
 * list and nothing else — it holds no strings and no `android.*`, so the ordering is testable
 * without a phone.
 */
internal object MeasureGuide {

    /** Points the run needs before "Save this run" works — the number [com.constructar.arengine.route.RouteSession] enforces. */
    const val NODES_FOR_A_RUN: Int = 2

    /**
     * Where the operator is in the three numbered steps.
     *
     * `SCAN` until there is a first piece of mesh, because until then "move the phone across the
     * wall" is the instruction that produces a point at all; `FINISH` as soon as the run can be
     * saved, because the screen must stop asking for more than it needs. Everything in between is
     * `POINTS` — including the very first tap, which is where the "two points are a distance, five
     * are a run" question lives.
     */
    fun step(state: GuideState): MeasureStep = when {
        state.canFinish -> MeasureStep.FINISH
        state.nodeCount > 0 -> MeasureStep.POINTS
        state.scannerOn && state.scanReady && state.meshVersion == 0 -> MeasureStep.SCAN
        else -> MeasureStep.POINTS
    }

    /**
     * The lines the HUD owes, in reading order, without the step strip and without the live lengths.
     *
     * [GuideLine.BLOCK_RUN] and [GuideLine.BLOCK_RING] are last on purpose: they are the answer to
     * "why can I not tap that", and the answer belongs next to the question — the row of buttons
     * sits immediately under the status block.
     */
    fun lines(state: GuideState): List<GuideLine> = buildList {
        // Coaching first: it is the only line that changes what the operator does *right now*.
        if (state.coaching) add(GuideLine.COACHING)
        scanLine(state)?.let { add(it) }
        add(
            when {
                state.closedLoop -> GuideLine.RING
                state.nodeCount == 0 -> GuideLine.FIRST_POINT
                state.nodeCount == 1 -> GuideLine.SECOND_POINT
                else -> GuideLine.DISTANCE
            },
        )
        when {
            !state.canFinish -> add(GuideLine.BLOCK_RUN)
            state.nodeCount < ActionGating.NODES_FOR_A_LOOP -> add(GuideLine.BLOCK_RING)
            else -> Unit
        }
    }

    /**
     * How loud a line is.
     *
     * Three levels, and the screen owns the three colours: a line that shouts and a line that
     * whispers are the difference between "something is wrong" and "this is where you are".
     */
    fun tone(line: GuideLine): GuideTone = when (line) {
        GuideLine.COACHING,
        GuideLine.SCAN_FULL,
        GuideLine.SCAN_BEHIND,
        GuideLine.SCAN_UNAVAILABLE -> GuideTone.ATTENTION
        GuideLine.BLOCK_RUN,
        GuideLine.BLOCK_RING -> GuideTone.QUIET
        GuideLine.SCAN_EMPTY,
        GuideLine.SCAN_PROGRESS,
        GuideLine.FIRST_POINT,
        GuideLine.SECOND_POINT,
        GuideLine.DISTANCE,
        GuideLine.RING -> GuideTone.NORMAL
    }

    /**
     * The one scan sentence, or `null` when the scanner is off.
     *
     * The order of the answers is [scanHint]'s, not a second copy of it: unavailable and a spent
     * budget cannot be fixed by walking, an empty mesh is the instruction itself, "behind" only
     * matters once something is in, and the gauge is what is left. What this function adds is the
     * one thing that was missing on the device - the promise that the answer is *always* printed,
     * whatever the tracking advisor is saying at the same time.
     */
    fun scanLine(state: GuideState): GuideLine? = when (
        scanHint(
            showScanner = state.scannerOn,
            ready = state.scanReady,
            hitBlockBudget = state.hitBlockBudget,
            meshVersion = state.meshVersion,
            droppedShare = state.droppedShare,
        )
    ) {
        ScanHint.NONE -> null
        ScanHint.UNAVAILABLE -> GuideLine.SCAN_UNAVAILABLE
        ScanHint.FULL -> GuideLine.SCAN_FULL
        ScanHint.INSTRUCTION -> GuideLine.SCAN_EMPTY
        ScanHint.BEHIND -> GuideLine.SCAN_BEHIND
        ScanHint.PROGRESS -> GuideLine.SCAN_PROGRESS
    }
}

/** How loudly a [GuideLine] is printed: the three colours live in the screen, not here. */
internal enum class GuideTone {
    /** A grey button's reason: present, quiet, and never an alarm. */
    QUIET,

    /** Where the operator is and what the run currently is. */
    NORMAL,

    /** Something the operator has to act on: coaching, a spent budget, a phone that is behind. */
    ATTENTION,
}

/** The three steps on the strip, in the order the operator does them. */
internal enum class MeasureStep { SCAN, POINTS, FINISH }

/** How one chip on the strip is marked. */
internal enum class StepMark {
    /** Behind the operator: this step is done. */
    DONE,

    /** The step the screen is asking for right now. */
    NOW,

    /** Not reached yet. */
    LATER,
}

/**
 * The mark on the chip at [index].
 *
 * A step is done when the operator is past it, which is not the same as "the thing it counts is
 * non-zero": the scan can legitimately be skipped on a phone without a scanner, and then step 1 is
 * never done — it is simply not the one being asked for. Saying "done" about a scan that never ran
 * would be the same kind of small lie as a `Watching` badge over a dead camera.
 */
internal fun measureStepMark(index: Int, current: MeasureStep): StepMark = when {
    index < current.ordinal -> StepMark.DONE
    index == current.ordinal -> StepMark.NOW
    else -> StepMark.LATER
}

/** One line of the status block. Resolved to a string at composition, never here. */
internal enum class GuideLine {
    /** The tracking advisor has something to say. At most one of these, and never instead of the gauge. */
    COACHING,

    /** Nothing scanned yet: the instruction that produces the first mesh. */
    SCAN_EMPTY,

    /** The detail gauge — the number that proves the walk is doing something. */
    SCAN_PROGRESS,

    /** The volume budget is spent; the gauge will not move again. */
    SCAN_FULL,

    /** The pipeline is refusing frames: walking slower is the one thing that helps. */
    SCAN_BEHIND,

    /** No scanner on this phone; measuring still works. */
    SCAN_UNAVAILABLE,

    /** No point yet: say where the first one goes. */
    FIRST_POINT,

    /** One point: say what the second one makes. */
    SECOND_POINT,

    /** Two or more: what the run currently is, and that saving is allowed. */
    DISTANCE,

    /** A ring: its length, and that saving is allowed. */
    RING,

    /** Why "Save this run" cannot be tapped. */
    BLOCK_RUN,

    /** Why "Close into a ring" cannot be tapped. */
    BLOCK_RING,
}

/**
 * Everything [MeasureGuide] needs, from the screen's own state.
 *
 * `coaching` is a boolean rather than a `Guidance`, because the guide only needs to know whether a
 * line is owed — which line is a resolution detail, and keeping the engine enum out of this object
 * keeps it free of `ar-engine` state that changes on every frame.
 */
internal data class GuideState(
    val nodeCount: Int = 0,
    val canFinish: Boolean = false,
    val closedLoop: Boolean = false,
    val coaching: Boolean = false,
    val scannerOn: Boolean = false,
    val scanReady: Boolean = false,
    val meshVersion: Int = 0,
    val hitBlockBudget: Boolean = false,
    val droppedShare: Float = 0f,
)
