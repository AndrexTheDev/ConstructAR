/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.keyframe

import com.constructar.core.slam.ClosureCandidate
import com.constructar.core.slam.ClosureOffer
import com.constructar.core.slam.ClosurePolicy
import com.constructar.core.slam.CorrectionReport

/**
 * The three things the pose graph can be asked, as a seam a test can fake.
 *
 * Deliberately narrow: ask for candidates, add one closure, optimise. Everything the app decides
 * (when to ask, what to show, whether to apply) happens above it, in `:core` and in
 * [PoseGraphCloser], which is why the whole conversation is testable without a device or a
 * library.
 */
interface PoseGraphSource {

    /** Revisits the native proximity detector found in the *current* estimate. */
    fun candidates(): List<ClosureCandidate>

    /**
     * Tell the graph that these two nodes are the same place.
     *
     * The measurement is an assumption, not a measurement: the operator said so. That is exactly
     * what makes this the one write in the whole SLAM path that needs a human.
     */
    fun addLoopClosure(from: Int, to: Int)

    /** Run the optimiser. `null` when there is no graph to optimise. */
    fun optimise(): ClosureOptimisation?
}

/**
 * The pose the app itself fed in for a node, or `null` for a node it never added.
 *
 * The optimiser returns corrected poses by node index; to say *how far things moved* the app has
 * to remember where they were. A missing original is skipped rather than counted as zero - a node
 * that came from somewhere else has no "before" to compare with (finding 246).
 */
interface NodeHistory {
    fun originalPose(index: Int): FloatArray?
}

/** What the optimiser reported, in the numbers that matter to an operator. */
data class ClosureOptimisation(
    val initialCost: Float,
    val finalCost: Float,
    val converged: Boolean,
    /** Column-major camera-to-world, one per node, as the optimiser left them. */
    val poses: List<FloatArray> = emptyList(),
)

/** What the loop-closure conversation is currently saying. */
sealed interface ClosureState {

    /** Nothing asked, nothing found. */
    data object Idle : ClosureState

    /** Asked, and the detector has nothing above the policy's floor. */
    data object NothingFound : ClosureState

    /** One question on screen, waiting for the operator. */
    data class Offered(val offer: ClosureOffer) : ClosureState

    /** The closure was applied and the graph optimised - with what it did to the trajectory. */
    data class Closed(
        val optimisation: ClosureOptimisation,
        val correction: CorrectionReport,
    ) : ClosureState

    /** The closure was applied but there is no graph to optimise - counted, not hidden. */
    data object Failed : ClosureState
}

/**
 * Runs the loop-closure conversation: ask, offer, confirm or refuse, optimise.
 *
 * The rules in one place, because they are the rules that decide whether a room gets folded:
 *
 *  * **Nothing is applied without a confirmation.** [look] only *asks*; the graph is untouched
 *    until [confirm]. A proximity candidate is not evidence - two visits can be half a metre
 *    apart and be different corners of the same doorway - and a closure that is wrong cannot be
 *    seen afterwards: the optimiser simply distributes the error over the whole loop.
 *  * **The confirmation is an assumption and is treated as one.** The closure edge says "these
 *    nodes coincide"; its weight is high enough to move the trajectory, which is the point.
 *  * **What the optimiser produced is reported, not stored.** The corrected poses are *not*
 *    written back into the measured runs here: a run's metres were measured with taps the
 *    operator made, and silently moving them would replace a measurement with an inference
 *    (finding 217's rule, applied to the graph instead of the floor).
 *  * **After a closure the pair is not offered again.** The two nodes now *are* the same place,
 *    so the detector would keep proposing them for the rest of the session.
 *
 * Thread confinement: the pipeline worker, one instance, no locks - [PoseGraphSource] reaches the
 * JNI graph, which must be serialised by that thread.
 */
class PoseGraphCloser(
    private val source: PoseGraphSource,
    private val history: NodeHistory,
    private val policy: ClosurePolicy = ClosurePolicy(),
) {

    var state: ClosureState = ClosureState.Idle
        private set

    /** Ask the detector, filter through the policy, and put at most one question up. */
    fun look(nodeCount: Int, nowMillis: Long): ClosureState {
        val offer = policy.decide(source.candidates(), nodeCount, nowMillis)
        state = if (offer == null) ClosureState.NothingFound else ClosureState.Offered(offer)
        return state
    }

    /** The operator said yes: apply, optimise, report. A no-op when nothing is on offer. */
    fun confirm(nowMillis: Long): ClosureState {
        val offer = (state as? ClosureState.Offered)?.offer ?: return state
        source.addLoopClosure(offer.from, offer.to)
        // Same bookkeeping as a refusal: the pair is done for this run. Without it the detector
        // would re-propose the two nodes that now coincide, on every lookup, forever.
        policy.dismiss(offer, nowMillis)
        val result = source.optimise() ?: run {
            state = ClosureState.Failed
            return state
        }
        // What the closure did, in metres. The corrected poses are *not* stored anywhere: this is
        // the report the operator gets instead of a silently moved room (finding 246).
        val correction = CorrectionReport.of(result.poses) { index -> history.originalPose(index) }
        state = ClosureState.Closed(result, correction)
        return state
    }

    /** The operator said no: remembered, and the next prompt waits for the cooldown. */
    fun dismiss(nowMillis: Long): ClosureState {
        (state as? ClosureState.Offered)?.offer?.let { policy.dismiss(it, nowMillis) }
        state = ClosureState.NothingFound
        return state
    }

    /** A new graph or a new session. */
    fun reset() {
        policy.clear()
        state = ClosureState.Idle
    }
}
