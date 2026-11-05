/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.keyframe

import com.constructar.core.math.Pose
import com.constructar.core.slam.KeyframeCandidate
import com.constructar.core.slam.KeyframeDecision
import com.constructar.core.slam.KeyframeReason
import com.constructar.core.slam.KeyframeSelector

/**
 * Where an accepted keyframe goes.
 *
 * Deliberately *not* the native engine. `NativeSlamEngine`'s contract is that integrate, extract
 * and every pose-graph call are serialised by the one pipeline thread that owns it, and the frame
 * loop that decides a keyframe runs on ARCore's render thread. A sink is therefore the seam where
 * a decision can become a call on some other thread: [KeyframeQueue] does nothing but enqueue, and
 * the worker drains it. Tests pass a sink that just records, which is why this interface exists at
 * all rather than a direct `NativeSlamEngine` field.
 *
 * Indices are the sink's own numbering and opaque to [KeyframeFeed]: a queue hands out logical
 * indices, the engine hands out native ones, and only the sink knows which is which.
 */
interface PoseGraphSink {

    /** Adds a keyframe pose (column-major 4x4, camera-to-world). Returns the sink's index, or [NO_NODE]. */
    fun addNode(poseColumnMajor: FloatArray, fixed: Boolean): Int

    /** Adds the relative measurement between two nodes: `T_from⁻¹ · T_to`, column-major 4x4. */
    fun addEdge(from: Int, to: Int, measurementColumnMajor: FloatArray)

    companion object {
        /** "The sink did not take it" - a closed channel, or no native context. */
        const val NO_NODE: Int = -1
    }
}

/** What one offered frame did: the policy's answer, and what became of it. */
data class KeyframeFeedEvent(
    val decision: KeyframeDecision,
    /** The sink's node index, or [PoseGraphSink.NO_NODE]. */
    val nodeIndex: Int,
    /** True when this keyframe was also connected to the previous one. */
    val edgeAdded: Boolean,
) {
    val recorded: Boolean get() = nodeIndex != PoseGraphSink.NO_NODE
}

/** Running totals, for the debug HUD and for the next finding to be judged from evidence. */
data class KeyframeFeedReport(
    val nodes: Int = 0,
    val edges: Int = 0,
    /** Accepted by the policy but refused by the sink (queue full, no native context). */
    val refused: Int = 0,
    /** Frames the policy rejected, for whatever reason. */
    val rejected: Int = 0,
    val lastReason: KeyframeReason? = null,
)

/**
 * Turns a stream of tracked camera poses into pose-graph nodes and edges.
 *
 * The decision is [KeyframeSelector]'s, in `:core`, and it is pure. This class adds the three
 * things the graph needs and the policy does not have:
 *
 *  * **The pose as a matrix.** ARCore hands a `Pose`; the graph takes a column-major 4x4. Written
 *    once here rather than at each call site, so a row-major mistranslation cannot be introduced
 *    by the next caller.
 *  * **The first node fixed.** A graph whose every node is free can be optimised into any frame; a
 *    single fixed node is what makes the ARCore session's origin the answer's frame instead of an
 *    arbitrary one.
 *  * **The edge measurement.** Between consecutive keyframes the measurement is `T_prev⁻¹ · T_cur`,
 *    which is the same convention `PoseGraph::residual` reads (`measurement⁻¹ · relativePose`).
 *    The *source* is ARCore's own odometry: these edges say "the tracker says we moved this far",
 *    which is exactly the quantity the optimiser is meant to reconcile. **No loop closure is
 *    proposed here** - a closure needs appearance matching, and a false one folds a room in half
 *    (finding 98, and `docs/SETUP.md` 5.2).
 *
 * Thread confinement: the render thread, one instance, no locks. [reset] is for the cases where a
 * new world origin begins - a new ARCore session, a relocalisation - because an edge across two
 * origins is a subtraction of two different rooms (the same defect finding 217 removed from the
 * route nodes).
 */
class KeyframeFeed(
    private val sink: PoseGraphSink,
    private val selector: KeyframeSelector = KeyframeSelector(),
) {

    private var previousPose: Pose? = null
    private var previousNode: Int = PoseGraphSink.NO_NODE

    /**
     * Running totals. Volatile because the pipeline reads them from its worker to publish
     * `PoseGraphStatus` while this thread keeps feeding: the value is replaced wholesale, so a
     * reader sees a consistent snapshot rather than a half-written one (finding 244).
     */
    @Volatile
    var report: KeyframeFeedReport = KeyframeFeedReport()
        private set

    /**
     * Offer one tracked frame. Cheap enough for every frame: the policy compares two poses, and
     * only an accepted frame causes a sink call.
     */
    fun onFrame(
        pose: Pose,
        timestampMillis: Long,
        trackingValid: Boolean,
        featurePoints: Int,
    ): KeyframeFeedEvent {
        val decision = selector.offer(
            KeyframeCandidate(
                position = pose.translation,
                rotation = pose.rotation,
                timestampMillis = timestampMillis,
                trackingValid = trackingValid,
                featurePoints = featurePoints,
            ),
        )
        if (!decision.accepted) {
            report = report.copy(rejected = report.rejected + 1, lastReason = decision.reason)
            return KeyframeFeedEvent(decision, PoseGraphSink.NO_NODE, edgeAdded = false)
        }

        val node = sink.addNode(
            poseColumnMajor = pose.toMatrix().elements.copyOf(),
            fixed = previousNode == PoseGraphSink.NO_NODE,
        )
        if (node == PoseGraphSink.NO_NODE) {
            // Counted, not swallowed: a screen that shows "0 keyframes" has to be explainable as
            // "the queue was full" rather than "the policy never fired".
            report = report.copy(refused = report.refused + 1, lastReason = decision.reason)
            return KeyframeFeedEvent(decision, PoseGraphSink.NO_NODE, edgeAdded = false)
        }

        val from = previousNode
        val fromPose = previousPose
        val edgeAdded = if (from != PoseGraphSink.NO_NODE && fromPose != null) {
            val measurement = fromPose.toMatrix().inverse() * pose.toMatrix()
            sink.addEdge(from, node, measurement.elements.copyOf())
            true
        } else {
            false
        }

        previousNode = node
        previousPose = pose
        report = report.copy(
            nodes = report.nodes + 1,
            edges = report.edges + if (edgeAdded) 1 else 0,
            lastReason = decision.reason,
        )
        return KeyframeFeedEvent(decision, node, edgeAdded)
    }

    /** Forget the reference keyframe: a new session or a jumped pose means a new origin. */
    fun reset() {
        selector.reset()
        previousPose = null
        previousNode = PoseGraphSink.NO_NODE
        report = KeyframeFeedReport()
    }
}
