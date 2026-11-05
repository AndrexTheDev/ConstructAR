/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

/** One axis of a recovered Manhattan frame: a direction and how much evidence supports it. */
data class ManhattanAxisReport(val x: Float, val y: Float, val z: Float) {

    /** True when this axis is horizontal - which is what a floorplan is squared onto. */
    val isHorizontal: Boolean get() = kotlin.math.abs(y) <= HORIZONTAL_LIMIT

    companion object {
        /** `cos(72°)`-ish: an axis this close to level is a wall direction, not the vertical. */
        const val HORIZONTAL_LIMIT = 0.30f
    }
}

/**
 * The frame the last floorplan was squared onto, as the native layer reports it.
 *
 * This is the *room's* frame, not the plan's: it is fitted from the walls the scan actually saw
 * (three bands of the volume, through `fitPlanes` and `manhattan_world.cpp`), and the floorplan is
 * then squared onto it instead of onto whichever run of a single slice happened to be longest. A
 * frame that is not [valid] - one wall only, a curved alcove, a scan that saw no wall at all -
 * means the plan was squared from its own runs, and a caller that reports "the plan follows the
 * room's axes" has to check this first.
 *
 * Thirteen floats arrive from `nativeAlignManhattanFrame`; anything else is `null`, for the same
 * reason [ManhattanReport] refuses a half-filled array: a zero-filled frame is a room that is
 * already square, and a plan drawn on it is a room that does not exist.
 */
data class ManhattanFrameReport(
    val valid: Boolean,
    /** How far the snapped walls sat off their axes, weighted by inliers, in degrees. */
    val residualDegrees: Float,
    /** Planes the fit found in the wall cloud. */
    val planes: Int,
    /** Planes that snapped onto one of the axes. */
    val snappedPlanes: Int,
    val axes: List<ManhattanAxisReport>,
) {

    /** The two horizontal axes, i.e. the directions a floorplan may be squared onto. */
    val horizontalAxes: List<ManhattanAxisReport> get() = axes.filter { it.isHorizontal }

    companion object {

        /** Floats `nativeAlignManhattanFrame` writes: four header values and three axes. */
        const val SIZE = 13

        private const val AXES = 3

        fun from(values: FloatArray?): ManhattanFrameReport? {
            if (values == null || values.size != SIZE) return null
            val valid = when (values[0]) {
                0f -> false
                1f -> true
                else -> return null
            }
            val residual = values[1]
            if (!residual.isFinite() || residual < 0f) return null
            val planes = countOf(values[2]) ?: return null
            val snapped = countOf(values[3]) ?: return null
            if (snapped > planes) return null
            val axes = ArrayList<ManhattanAxisReport>(AXES)
            for (index in 0 until AXES) {
                val base = 4 + index * 3
                val axis = ManhattanAxisReport(values[base], values[base + 1], values[base + 2])
                if (!axis.x.isFinite() || !axis.y.isFinite() || !axis.z.isFinite()) return null
                axes += axis
            }
            return ManhattanFrameReport(
                valid = valid,
                residualDegrees = residual,
                planes = planes,
                snappedPlanes = snapped,
                axes = axes,
            )
        }

        private fun countOf(value: Float): Int? {
            if (!value.isFinite() || value < 0f || value != kotlin.math.floor(value)) return null
            return value.toInt()
        }
    }
}
