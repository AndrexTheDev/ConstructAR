/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.keyframe

import com.constructar.core.math.Mat4
import com.constructar.core.slam.ClosureCandidate
import com.constructar.nativebridge.NativeSlamEngine

/**
 * [PoseGraphSource] over the JNI pose graph.
 *
 * Three delegations and one number that is not a delegation: the weight of the closure edge.
 * Loop closures start with 100× the odometry weight so a consistent revisit can remove drift.
 * That weight is a hypothesis, not a command: newest-loop pairwise cycle checks and native
 * GNC/TLS can reject a closure, and an independent odometry-deformation gate falls back to the
 * measured chain rather than folding it. PCM removes only the inconsistent newcomer.
 *
 * **Worker thread only** - `NativeSlamEngine` requires every pose-graph call to be serialised by
 * the pipeline's single worker, and [PoseGraphCloser] is run there.
 */
class NativePoseGraphSource(private val engine: NativeSlamEngine) : PoseGraphSource {

    override fun candidates(): List<ClosureCandidate> =
        engine.loopClosureCandidates().map { ClosureCandidate(it.from, it.to, it.distanceMeters) }

    override fun addLoopClosure(from: Int, to: Int) {
        // Identity: "these two nodes are one place". The 0.35 m the detector measured is not a
        // measurement to keep - it is the drift the closure is meant to remove.
        engine.addPoseEdge(
            from = from,
            to = to,
            measurement = Mat4.IDENTITY.elements.copyOf(),
            rotationWeight = LOOP_CLOSURE_WEIGHT,
            translationWeight = LOOP_CLOSURE_WEIGHT,
            loopClosure = true,
        )
    }

    override fun optimise(): ClosureOptimisation? =
        engine.optimizePoseGraph()?.let {
            ClosureOptimisation(
                initialCost = it.initialCost,
                finalCost = it.finalCost,
                rmsTranslationMeters = it.rmsTranslationMeters,
                rmsRotationRadians = it.rmsRotationRadians,
                converged = it.converged,
                poses = it.poses,
            )
        }

    companion object {
        /** 100× the odometry weight - see the class comment and `docs/ARCHITECTURE.md`. */
        const val LOOP_CLOSURE_WEIGHT: Double = 100.0
    }
}
