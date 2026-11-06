/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.keyframe

import com.constructar.core.slam.CorrectionReport

/** What the pose graph is doing, in the three states a status line can act on. */
enum class PoseGraphStanding {
    /** Nothing in the graph and nothing lost: the policy has not fired yet. */
    EMPTY,

    /** Nodes are landing and every one of them made it. */
    RECORDING,

    /** Something was dropped on the way in - a queue overflow, a refused node, a lost edge. */
    LOSSY,
}

/**
 * The counters of the pose-graph path, collapsed into something a screen can say (finding 244).
 *
 * Finding 243 built a chain in which four components each keep their own tally -
 * `KeyframeFeed.report`, `KeyframeQueue.dropped`, `KeyframeApplier.refusedNodes` and
 * `KeyframeApplier.droppedEdges` - and the only reader of any of them was a unit test. On a
 * device that makes "the graph is empty" three different problems that look identical: the policy
 * never fired (no keyframe was ever worth keeping), the queue overflowed (the worker was not
 * draining), or the engine refused the node (no native context). A support call cannot tell them
 * apart from a screenshot, and neither can the next developer.
 *
 * So this value is the one place that answers *how many landed* and *how many were lost*, and the
 * HUD line is what makes it observable. [standing] exists because the numbers alone do not say
 * which sentence to print: one node and no losses is healthy, one node and one dropped edge is a
 * graph with a hole in it.
 *
 * Nothing here is a statistic about accuracy - a node that landed is a node the graph has, and
 * whether the *walk* was measured well is a different question that no counter can answer.
 */
data class PoseGraphStatus(
    val nodes: Int = 0,
    val edges: Int = 0,
    /** Everything that was accepted by the policy and did not reach the graph, for any reason. */
    val lost: Int = 0,
    /**
     * Loop closures this session's operator has confirmed (finding 250).
     *
     * The graph line used to say "380 nodes, 379 edges" whether or not the walk had been folded
     * back, because the closure's own counters stopped at the dialog: `ClosureState.Closed`
     * carried the residual and the shift, the dialog printed them, and closing it threw them away.
     * An operator who wants to know *afterwards* whether the room was closed had nothing to look
     * at, and the geometry they were about to sign did not say either.
     */
    val closures: Int = 0,
    /**
     * What the most recent *converged* closure moved, or `null` before one has run.
     *
     * `null` here does not mean "nothing moved" - that is a report with `moved == false`. It means
     * there is no closure to report on, which is why the status line prints nothing at all
     * (finding 246's rule, one level up).
     */
    val lastCorrection: CorrectionReport? = null,
) {
    val standing: PoseGraphStanding
        get() = when {
            lost > 0 -> PoseGraphStanding.LOSSY
            nodes > 0 -> PoseGraphStanding.RECORDING
            else -> PoseGraphStanding.EMPTY
        }

    companion object {
        /**
         * Reads the counters that exist. `null` means the engine was never created - the graph
         * does not exist, which is [PoseGraphStanding.EMPTY] rather than an error.
         *
         * The three loss counters are deliberately summed into one number: the operator does not
         * need to know whether a keyframe died in the queue or at the JNI boundary, and a screen
         * that printed three counts would be a debug panel pretending to be a status line. The
         * split stays on the applier, where a test can still see it.
         */
        fun of(
            applier: KeyframeApplier?,
            droppedByQueue: Int = 0,
            closures: Int = 0,
            lastCorrection: CorrectionReport? = null,
        ): PoseGraphStatus {
            if (applier == null) {
                return PoseGraphStatus(lost = droppedByQueue, closures = closures, lastCorrection = lastCorrection)
            }
            return PoseGraphStatus(
                nodes = applier.appliedNodes,
                edges = applier.appliedEdges,
                lost = droppedByQueue + applier.refusedNodes + applier.droppedEdges,
                closures = closures,
                lastCorrection = lastCorrection,
            )
        }
    }
}
