/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import com.constructar.export.svg.FloorplanSvg
import kotlin.math.min

/**
 * The protocol's floorplan page: the room's outline, plus text the caller has already localised.
 *
 * The text is a parameter, never a sentence composed here: `:export` has no string table, and a
 * document that leaves the phone in six languages must get its words from the app (finding 100's
 * rule). What this module owns is the drawing, and the drawing has one job - put the room the app
 * measured on paper at a scale a tape measure can be checked against.
 */
data class FloorplanSection(
    /** The room's corners in world metres, in the order the ring runs. */
    val corners: List<FloorplanSvg.Corner>,
    /** Heading of the page, e.g. "Room plan". */
    val title: String = "",
    /** Line under the drawing: area, perimeter, and whatever the caller wants to state. */
    val caption: String = "",
    /** Small grey note under the caption, e.g. that a wall was drawn by hand. */
    val note: String = "",
)

/**
 * The room projected into a rectangle of the page, in PDF points.
 *
 * Three decisions, all of them borrowings from [FloorplanSvg] so paper and phone show one room:
 *
 *  * **One scale for both axes.** A stretch is exactly what makes opposite walls stop being
 *    parallel; the longer extent sets the scale and both axes use it.
 *  * **World `z` runs down the page**, as it runs down the card and the exported SVG. A floorplan
 *    that is mirrored relative to the phone is two rooms with one set of measurements.
 *  * **The wall is physical, not a hairline.** 10 cm at the page's scale, clamped to a printable
 *    range: a 2 m bathroom and a 20 m hall both have to read as rooms, and a wall thinner than a
 *    pixel disappears when the customer files a photocopy.
 *
 * The arithmetic lives in this class rather than in the writer because it is the part that can be
 * asserted without a `PdfDocument` - the same reason [PageFlow] exists (finding 174).
 */
class FloorplanLayout private constructor(
    /** Corners in page points, world `x` right and world `z` down. */
    val points: List<Pair<Float, Float>>,
    /** How many points one metre became. The number a scale bar would be printed from. */
    val metresToPoints: Float,
    /** Stroke width of a wall, points. */
    val wallWidthPoints: Float,
) {

    companion object {

        /** A wall is a physical ten centimetres, as in `FloorplanSvg`. */
        const val WALL_METERS: Float = 0.10f

        /**
         * The printable range of a wall.
         *
         * Below three quarters of a point a 300 dpi printer loses the wall; above six points a
         * small room turns into a solid block of ink. Both ends are the paper's limits, not ours.
         */
        const val MIN_WALL_POINTS: Float = 0.75f
        const val MAX_WALL_POINTS: Float = 6f

        /**
         * Fits [corners] into the box at ([left], [top]) of size [width] x [height].
         *
         * Returns `null` for anything that is not a room: fewer than three corners, a coordinate
         * that is not finite, a box without area, or an outline with no extent in either direction
         * (every corner on one spot - there is no scale to choose).
         */
        fun of(
            corners: List<FloorplanSvg.Corner>,
            left: Float,
            top: Float,
            width: Float,
            height: Float,
        ): FloorplanLayout? {
            if (corners.size < 3) return null
            if (!left.isFinite() || !top.isFinite()) return null
            if (!(width > 0f) || !(height > 0f)) return null

            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (corner in corners) {
                if (!corner.x.isFinite() || !corner.z.isFinite()) return null
                if (corner.x < minX) minX = corner.x
                if (corner.x > maxX) maxX = corner.x
                if (corner.z < minZ) minZ = corner.z
                if (corner.z > maxZ) maxZ = corner.z
            }
            val extentX = maxX - minX
            val extentZ = maxZ - minZ
            // Every corner on one spot: no extent on either axis, so there is no scale to choose.
            // Refused before the scale is computed, because two "unconstrained" axes would each
            // report `Float.MAX_VALUE` and their minimum is a number rather than a refusal.
            if (extentX <= 0f && extentZ <= 0f) return null
            // A room that is 4 m wide and 0 m deep has no scale of its own on one axis: that axis
            // does not constrain the fit, and `Float.MAX_VALUE` says so without a special case.
            val scaleX = if (extentX > 0f) width / extentX else Float.MAX_VALUE
            val scaleZ = if (extentZ > 0f) height / extentZ else Float.MAX_VALUE
            val scale = min(scaleX, scaleZ)
            if (!scale.isFinite() || scale <= 0f) return null

            val drawnWidth = extentX * scale
            val drawnHeight = extentZ * scale
            val offsetX = left + (width - drawnWidth) / 2f
            val offsetY = top + (height - drawnHeight) / 2f
            val points = corners.map { corner ->
                val x = offsetX + (corner.x - minX) * scale
                val y = offsetY + (corner.z - minZ) * scale
                x to y
            }
            val wall = (WALL_METERS * scale).coerceIn(MIN_WALL_POINTS, MAX_WALL_POINTS)
            return FloorplanLayout(points, scale, wall)
        }
    }
}
