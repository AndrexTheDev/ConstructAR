/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.slam

import kotlin.math.sqrt

/**
 * What a confirmed loop closure did to the trajectory, in metres (finding 246).
 *
 * The optimiser has returned corrected poses since the first confirmation, and the app threw them
 * away: `PoseGraphResult.poses` was read for nothing, so a closure that moved every wall in the
 * room by 20 cm and one that found the graph already consistent were the same sentence on screen -
 * the defect findings 237 and 238 removed from the marker dialog, in the one place where the
 * number is *the operator's own measurement* moving.
 *
 * Two things this value deliberately is not:
 *
 *  * **It is not a write-back.** Nothing here changes a stored node; it says how far the graph
 *    would move the poses it knows. Applying that to the measured runs needs a correspondence
 *    between a keyframe and a tapped point, and those are two different sets - choosing one is a
 *    decision, not an interpolation (finding 217).
 *  * **It is not a quality score.** A large shift may be a real correction or a false closure
 *    working as advertised; the number says how much moved, not whether the move was right.
 *
 * The mean is carried beside the maximum because one outlier node and a whole trajectory
 * drifting are different situations that a maximum alone cannot tell apart. [movedNodes] says
 * which of the two it was in the operator's own terms - "42 of 380 nodes moved" is a room that
 * was redistributed over the loop, "1 of 380" is one bad keyframe, and both have the same
 * maximum (finding 249). Deciding that with a threshold on the ratio would be inventing a number
 * to hide two real ones behind.
 */
data class CorrectionReport(
    /** Nodes the optimiser returned a pose for *and* the app had an original for. */
    val nodes: Int,
    /** Of those, the ones that moved further than [MOVED_THRESHOLD_METRES]. */
    val movedNodes: Int,
    val maxShiftMetres: Float,
    val meanShiftMetres: Float,
) {

    /**
     * Whether anything moved beyond the millimetre the optimiser's own arithmetic can produce.
     *
     * Below this the graph was already consistent and the honest thing to print is nothing - a
     * "0.000 m" line teaches the operator that closures are theatre.
     */
    val moved: Boolean get() = movedNodes > 0

    companion object {
        /** 1 mm: under the noise of a single ARCore pose, over the optimiser's rounding. */
        const val MOVED_THRESHOLD_METRES: Float = 0.001f

        /**
         * Compare the optimiser's poses with the ones the app fed in.
         *
         * [original] answers by **node index** and may return `null` for a node the app never
         * added (an RTAB-Map keyframe, a node from before a reset). Those are skipped rather than
         * assumed to have moved zero: a missing original is a missing measurement.
         */
        fun of(optimised: List<FloatArray>, original: (Int) -> FloatArray?): CorrectionReport {
            var nodes = 0
            var movedNodes = 0
            var max = 0f
            var total = 0.0
            for (index in optimised.indices) {
                val before = original(index) ?: continue
                val after = optimised[index]
                if (before.size < 16 || after.size < 16) continue
                val shift = shiftMetres(before, after)
                if (!shift.isFinite()) continue
                nodes += 1
                total += shift.toDouble()
                if (shift > MOVED_THRESHOLD_METRES) movedNodes += 1
                if (shift > max) max = shift
            }
            if (nodes == 0) {
                return CorrectionReport(nodes = 0, movedNodes = 0, maxShiftMetres = 0f, meanShiftMetres = 0f)
            }
            return CorrectionReport(
                nodes = nodes,
                movedNodes = movedNodes,
                maxShiftMetres = max,
                meanShiftMetres = (total / nodes).toFloat(),
            )
        }

        /**
         * The distance between the two translations, straight out of the matrices.
         *
         * Column-major, so the translation lives at 12, 13, 14 - the same layout `Pose.toMatrix`
         * writes and `PoseGraph` reads. Written out rather than routed through `Mat4` because this
         * runs over every node after every optimisation and needs nothing else from the matrix.
         */
        private fun shiftMetres(before: FloatArray, after: FloatArray): Float {
            val dx = after[12] - before[12]
            val dy = after[13] - before[13]
            val dz = after[14] - before[14]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}
