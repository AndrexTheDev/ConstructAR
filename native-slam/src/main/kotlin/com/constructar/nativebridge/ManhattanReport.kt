/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

/**
 * What the floorplan's Manhattan step did to the room, as the native layer reports it.
 *
 * Squaring a floorplan is invisible by nature: the corners move by a few centimetres and the area
 * by a few per mille, so a caller that *assumes* it happened and a caller that *assumes* it did not
 * both produce plausible rooms. [applied] is the fact, [residualDegrees] is how far the room sat
 * off square before it was straightened, and [snappedRuns] / [movedCorners] say how much of the
 * outline was actually rebuilt - a plan with four runs on the frame and a chamfer left alone
 * reports exactly that.
 *
 * The values arrive as a separate `float[]` from `nativeLastManhattan`, not inside the floorplan
 * payload: that payload's layout is a pinned contract between C++ and Kotlin, and a build whose
 * library is older than its Kotlin would read statistics as wall runs. Four floats or nothing is a
 * contract that cannot be misread - [from] returns null rather than a half-filled report.
 */
data class ManhattanReport(
    /** True when the runs determined a frame and the outline was rebuilt on it. */
    val applied: Boolean,
    /** How far the runs sat off the frame before snapping, length-weighted, in degrees. */
    val residualDegrees: Float,
    /** Runs moved onto the frame. A run further off than the tolerance keeps its own angle. */
    val snappedRuns: Int,
) {

    companion object {

        /** Floats `nativeLastManhattan` writes: applied, residual, snapped runs, moved corners. */
        const val SIZE = 4

        /**
         * Reads the native array, or `null` when it cannot be one.
         *
         * Null covers every case that would otherwise become a number with the authority of a
         * measurement: a missing array (an older library), the wrong length, a flag that is not
         * 0 or 1, a non-finite or negative residual, and a negative count.
         */
        fun from(values: FloatArray?): ManhattanReport? {
            if (values == null || values.size != SIZE) return null
            val applied = when (values[0]) {
                0f -> false
                1f -> true
                else -> return null
            }
            val residual = values[1]
            if (!residual.isFinite() || residual < 0f) return null
            val snapped = countOf(values[2]) ?: return null
            // The corner count is parsed too, so a library that stops writing it is refused here
            // rather than in a screen that would print a moved-corner count of zero.
            if (countOf(values[3]) == null) return null
            return ManhattanReport(applied = applied, residualDegrees = residual, snappedRuns = snapped)
        }

        private fun countOf(value: Float): Int? {
            if (!value.isFinite() || value < 0f || value != kotlin.math.floor(value)) return null
            return value.toInt()
        }
    }
}
