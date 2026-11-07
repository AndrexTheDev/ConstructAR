/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Range-based DIN 18015 installation zones, measured from the finished floor or ceiling. */
object Din18015Zones {
    const val SNAP_GRID_METERS: Float = 0.05f
    const val VERTICAL_CORNER_ZONE_METERS: Float = 0.15f

    @Serializable
    enum class Horizontal { ZW_U, ZW_M, ZW_O, SONDER }

    @Serializable
    enum class Vertical { ZW_V, OUTSIDE }
    enum class Reference { FINISHED_FLOOR, CEILING }

    data class Band(
        val zone: Horizontal,
        val minimumMeters: Float,
        val maximumMeters: Float,
        val reference: Reference,
    ) {
        val centreMeters: Float get() = (minimumMeters + maximumMeters) * 0.5f
        fun contains(distanceMeters: Float): Boolean =
            distanceMeters.isFinite() && distanceMeters >= minimumMeters && distanceMeters <= maximumMeters
    }

    data class Assignment(
        val zone: Horizontal,
        val distanceFromReferenceMeters: Float,
        val snappedHeightMeters: Float?,
        val sonderverlegung: Boolean,
    )

    data class PointAssignment(
        val horizontal: Assignment,
        val vertical: Vertical,
        val sonderverlegung: Boolean,
    )

    val lower = Band(Horizontal.ZW_U, 0.15f, 0.45f, Reference.FINISHED_FLOOR)
    val middle = Band(Horizontal.ZW_M, 1.00f, 1.30f, Reference.FINISHED_FLOOR)
    val upper = Band(Horizontal.ZW_O, 0.15f, 0.45f, Reference.CEILING)

    fun assign(heightMeters: Float, roomHeightMeters: Float?): Assignment {
        if (!heightMeters.isFinite()) return Assignment(Horizontal.SONDER, Float.NaN, null, true)
        if (lower.contains(heightMeters)) return snapped(lower, heightMeters, heightMeters)
        if (middle.contains(heightMeters)) return snapped(middle, heightMeters, heightMeters)
        val roomHeight = roomHeightMeters?.takeIf { it.isFinite() && it > 0f }
        if (roomHeight != null) {
            val belowCeiling = roomHeight - heightMeters
            if (upper.contains(belowCeiling)) return snapped(upper, belowCeiling, heightMeters)
            return Assignment(Horizontal.SONDER, belowCeiling, null, true)
        }
        return Assignment(Horizontal.SONDER, heightMeters, null, true)
    }

    fun point(
        heightMeters: Float,
        roomHeightMeters: Float?,
        distanceToCornerMeters: Float,
    ): PointAssignment {
        val horizontal = assign(heightMeters, roomHeightMeters)
        val vertical = if (distanceToCornerMeters.isFinite() &&
            kotlin.math.abs(distanceToCornerMeters) <= VERTICAL_CORNER_ZONE_METERS
        ) {
            Vertical.ZW_V
        } else {
            Vertical.OUTSIDE
        }
        // A vertical corner zone is a valid DIN placement even where it crosses a horizontal band.
        val sonder = horizontal.sonderverlegung && vertical != Vertical.ZW_V
        return PointAssignment(horizontal, vertical, sonder)
    }

    /** Snaps only inside a standard band; Sonderverlegung is flagged, never silently moved into DIN. */
    private fun snapped(band: Band, distance: Float, originalHeight: Float): Assignment {
        val snappedDistance = ((distance / SNAP_GRID_METERS).roundToInt()).coerceIn(
            (band.minimumMeters / SNAP_GRID_METERS).roundToInt(),
            (band.maximumMeters / SNAP_GRID_METERS).roundToInt(),
        ) * SNAP_GRID_METERS
        val snappedHeight = if (band.reference == Reference.FINISHED_FLOOR) {
            snappedDistance
        } else {
            originalHeight + (snappedDistance - distance)
        }
        return Assignment(band.zone, distance, snappedHeight, false)
    }
}
