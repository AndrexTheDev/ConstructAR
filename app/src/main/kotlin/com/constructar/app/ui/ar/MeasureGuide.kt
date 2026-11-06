/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * What the measure screen says, in which mode, in which order, and why (findings 265 and 267).
 *
 * The first device report was one sentence — *"man geht langsam durch den Raum, aber dabei scheint
 * nichts zu passieren"* — and the code explains it exactly. The HUD picked **one** line:
 *
 * ```
 * val statusLine = guidanceText ?: when (hint) { … ScanHint.PROGRESS -> scanProgressText … }
 * ```
 *
 * On a Pixel 3a in a hallway the tracking advisor answers `SEARCHING` or `LIMITED` most of the
 * time, and both of those sentences end in "move slowly". So the gauge that was climbing — the one
 * number that proves the walk is doing something — was the branch that never ran.
 *
 * The second report was worse and simpler: *"man sieht nicht, was gescannt ist"* and *"das ist
 * nicht dasselbe wie einen Messpunkt setzen"*. Both were right, and both are the same mistake —
 * one screen, one set of instructions, two jobs. So the lines are now chosen per [MeasureMode]
 * (finding 267), and the two sets are disjoint by construction:
 *
 * * [MeasureMode.MEASURE] gets the point/distance/ring lines and the one refusal line under the
 *   grey button, and never a scan sentence.
 * * [MeasureMode.CAPTURE] gets coaching, at most one scan *warning*, and the walking instruction,
 *   and never a point sentence.
 *
 * This object decides that list and nothing else — no strings, no `android.*` — so the ordering and
 * the disjointness are testable without a phone.
 */
internal object MeasureGuide {

    /** Points the run needs before "Save this run" works — the number [com.constructar.arengine.route.RouteSession] enforces. */
    const val NODES_FOR_A_RUN: Int = 2

    /**
     * The step the screen is asking for right now.
     *
     * A walkthrough is always step one, because that is the only thing it asks for; measuring is
     * step two until the run can be saved and step three after. The scan step is never "done" in
     * [MeasureMode.MEASURE]: the operator may have skipped it entirely, and a chip that says
     * "done" over a scan that never ran is the same small lie as a `Watching` badge over a dead
     * camera.
     */
    fun currentStep(state: GuideState): MeasureStep = when (state.mode) {
        MeasureMode.CAPTURE -> MeasureStep.SCAN
        MeasureMode.MEASURE -> if (state.canFinish) MeasureStep.FINISH else MeasureStep.POINTS
    }

    /**
     * The chips the strip shows, with their marks.
     *
     * Only the steps the mode owns: a walkthrough shows the scan chip alone, a measurement shows
     * the two it asks for. Two chips on the glass instead of three is the whole visual difference
     * between "you are capturing a room" and "you are placing points", and it costs no colour
     * vision to read.
     */
    fun strip(state: GuideState): List<Pair<MeasureStep, StepMark>> {
        val chips = when (state.mode) {
            MeasureMode.CAPTURE -> listOf(MeasureStep.SCAN)
            MeasureMode.MEASURE -> listOf(MeasureStep.POINTS, MeasureStep.FINISH)
        }
        val current = currentStep(state)
        return chips.map { step -> step to measureStepMark(step, current) }
    }

    /**
     * The lines the status block owes, in reading order, without the pinned strip and gauge.
     *
     * [GuideLine.BLOCK_RUN] and [GuideLine.BLOCK_RING] are last on purpose: they are the answer to
     * "why can I not tap that", and the answer belongs next to the question — the row of buttons
     * sits immediately under the status block.
     */
    fun lines(state: GuideState): List<GuideLine> = when (state.mode) {
        MeasureMode.CAPTURE -> buildList {
            // Coaching first: it is the only line that changes what the operator does right now.
            if (state.coaching) add(GuideLine.COACHING)
            // A scan *warning* only. The gauge itself is pinned above this column (finding 267),
            // because a number that scrolls out of the budget is a number nobody watches.
            scanNotice(state)?.let { add(it) }
            add(GuideLine.CAPTURE_WALK)
        }
        MeasureMode.MEASURE -> buildList {
            if (state.coaching) add(GuideLine.COACHING)
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
        GuideLine.RING,
        GuideLine.CAPTURE_WALK -> GuideTone.NORMAL
    }

    /**
     * The scan state, for the pinned gauge; `null` when this mode has no scan at all.
     *
     * The order of the answers is [scanHint]'s, not a second copy of it: unavailable and a spent
     * budget cannot be fixed by walking, an empty mesh is the instruction itself, "behind" only
     * matters once something is in, and the gauge is what is left.
     */
    fun scanLine(state: GuideState): GuideLine? {
        if (!state.mode.showsRoomOverlay) return null
        return when (
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

    /**
     * The scan answer, but only when it needs a sentence of its own.
     *
     * "Nothing scanned yet" and "so much detail" are what the pinned gauge is *for*: it says both
     * with a bar, a percentage and a state word, in the same place every frame. Repeating them as
     * scrolling text underneath was the duplication that made the old screen unreadable — what the
     * block keeps is the three states the operator has to act on.
     */
    fun scanNotice(state: GuideState): GuideLine? = when (scanLine(state)) {
        GuideLine.SCAN_FULL, GuideLine.SCAN_BEHIND, GuideLine.SCAN_UNAVAILABLE -> scanLine(state)
        else -> null
    }
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
 * The mark on the chip of [step] when [current] is the step being asked for.
 *
 * A step is done when the operator is past it, which is not the same as "the thing it counts is
 * non-zero": the scan can legitimately be skipped, and then its chip is not drawn at all rather
 * than marked done.
 */
internal fun measureStepMark(step: MeasureStep, current: MeasureStep): StepMark = when {
    step.ordinal < current.ordinal -> StepMark.DONE
    step.ordinal == current.ordinal -> StepMark.NOW
    else -> StepMark.LATER
}

/** One line of the status block. Resolved to a string at composition, never here. */
internal enum class GuideLine {
    /** The tracking advisor has something to say. At most one of these, and never instead of the gauge. */
    COACHING,

    /** Nothing scanned yet. Only the pinned gauge prints this in a walkthrough. */
    SCAN_EMPTY,

    /** The detail gauge — the number that proves the walk is doing something. */
    SCAN_PROGRESS,

    /** The volume budget is spent; the gauge will not move again. */
    SCAN_FULL,

    /** The pipeline is refusing frames: walking slower is the one thing that helps. */
    SCAN_BEHIND,

    /** No scanner on this phone; measuring still works. */
    SCAN_UNAVAILABLE,

    /** Walkthrough: keep walking, and the violet surfaces are what has been captured. */
    CAPTURE_WALK,

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

/** How loudly a [GuideLine] is printed. */
internal enum class GuideTone {
    /** A grey button's reason: present, quiet, and never an alarm. */
    QUIET,

    /** Where the operator is and what the run currently is. */
    NORMAL,

    /** Something the operator has to act on: coaching, a spent budget, a phone that is behind. */
    ATTENTION,
}

/**
 * Everything [MeasureGuide] needs, from the screen's own state.
 *
 * `coaching` is a boolean rather than a `Guidance`, because the guide only needs to know whether a
 * line is owed — which line is a resolution detail, and keeping the engine enum out of this object
 * keeps it free of `ar-engine` state that changes on every frame.
 */
internal data class GuideState(
    /** Which of the two jobs this screen is doing. Owns the lines, the chips and the overlay. */
    val mode: MeasureMode = MeasureMode.MEASURE,
    val nodeCount: Int = 0,
    val canFinish: Boolean = false,
    val closedLoop: Boolean = false,
    val coaching: Boolean = false,
    /** True while the room pipeline is offered to the screen; false only before it is ready. */
    val scannerOn: Boolean = true,
    val scanReady: Boolean = false,
    val meshVersion: Int = 0,
    val hitBlockBudget: Boolean = false,
    val droppedShare: Float = 0f,
)
