/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.PolygonMath
import com.constructar.core.math.Vec3
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** One corner of a room outline, in metres on the floor plane. */
@Serializable
data class SketchPoint(val xMeters: Float, val zMeters: Float)

/**
 * The room's outline, as the home screen's card draws it.
 *
 * Why this exists next to `files/projects/<id>/thumbnail.svg`: Android has no SVG renderer. The
 * card could not open the file we write without either a third-party library (this app has no
 * third-party dependencies at all) or a second parser for a format we only ever write. The outline
 * is the smallest true form of the picture - a rectangle is eight floats - it is exactly what the
 * SVG was drawn from, and it survives the file system: a card still draws after a backup, a
 * restore onto a new phone, or a user who cleared the app's cache.
 *
 * The SVG stays the artefact for the outside world (a laptop, a customer's mail, the protocol), and
 * this stays the one the phone draws. Both come from the same `FloorplanPayload` and the same
 * "largest closed loop" rule, so they cannot disagree about which room they show.
 *
 * A sketch that is not [isDrawable] is a plan in which no loop closed (a doorway leaves the ring
 * open) - the card falls back to its icon rather than inventing a wall across the door. It is still
 * stored: the corners are what the scan saw, and [closedByHand] is the one way a person may say the
 * gap is a wall.
 */
@Serializable
data class FloorplanSketch(
    val points: List<SketchPoint> = emptyList(),
    /**
     * Whether the ring is closed, and by what.
     *
     * A scan of a room with a doorway in it traces a ring that never closes, and that is worth
     * *keeping*: it is the only record of where the walls were, and the only thing an operator
     * could later close by hand. So an open ring is stored with [Closure.Open] - not drawn, not
     * measured, not priced - and the card offers to close it. Defaults to [Closure.Measured],
     * because every sketch written before this field existed came from a closed loop.
     */
    val closure: Closure = Closure.Measured,
) {

    /** How the ring's last wall came to be there. */
    @Serializable
    enum class Closure {
        /** The scan never closed the ring - a doorway, a glass wall, a room left half walked. */
        Open,

        /** The ring closed in the scan itself; the closing wall was measured like the others. */
        Measured,

        /**
         * The operator asserted the gap is a wall - a doorway to be boarded, a glazed partition.
         *
         * It counts everywhere a measured wall counts (area, perimeter, the card, the exported
         * SVG), because the operator walked the room and said so. What it may not do is pretend it
         * was measured: a reader of the file can tell the two apart, which is what a protocol that
         * states its accuracy needs.
         */
        DrawnByHand,
    }

    /** Three corners are the least that can enclose anything, and an open ring encloses nothing. */
    val isDrawable: Boolean
        get() = points.size >= 3 && points.size <= MAX_POINTS && closure != Closure.Open

    /**
     * Enclosed area, square metres, from the same `PolygonMath.area` the protocol prints - so the
     * picture and the printed area describe one shape.
     */
    val areaSquareMeters: Float
        get() = if (!isDrawable) 0f else PolygonMath.area(toFloorPolygon())

    /**
     * The room's perimeter in metres: the ring's length, closing edge included.
     *
     * A room outline *is* a ring - the last corner is joined back to the first, which is what
     * `FloorplanSvg` and the card both draw - so the closing edge is part of the walk. Without it a
     * 4 x 3 m room would measure 11 m instead of 14 m, and 3 m of wall would simply be missing from
     * the one number that prices skirting, trunking and the clips that hold them.
     *
     * Zero rather than a guess when there is no ring (fewer than three corners) or a coordinate is
     * not finite: a card that prints "0.00 m" for a room it cannot measure is honest, and a caller
     * can tell that apart from a small room.
     */
    val perimeterMeters: Float
        get() {
            if (!isDrawable) return 0f
            var total = 0f
            for (index in points.indices) {
                val current = points[index]
                val next = points[(index + 1) % points.size]
                val dx = next.xMeters - current.xMeters
                val dz = next.zMeters - current.zMeters
                if (!dx.isFinite() || !dz.isFinite()) return 0f
                total += sqrt(dx * dx + dz * dz)
            }
            return if (total.isFinite()) total else 0f
        }

    /**
     * This room with its gap closed by hand, or unchanged when there is no gap to close.
     *
     * Only an [Closure.Open] ring can be closed this way: a measured ring is already closed, and a
     * ring the operator already closed does not need a second assertion. The result keeps every
     * corner as scanned - closing a doorway adds a wall, it does not move one.
     */
    fun closedByHand(): FloorplanSketch =
        if (closure == Closure.Open) copy(closure = Closure.DrawnByHand) else this

    fun toFloorPolygon(): List<Vec3> = points.map { Vec3(it.xMeters, 0f, it.zMeters) }

    companion object {
        /**
         * A room outline is dozens of corners after simplification, not thousands.
         *
         * A trace with more is an un-simplified ring - a scan of a cluttered corridor - and storing
         * it would put kilobytes into every project file and every backup for a shape that is a
         * 56 dp square on a phone. Such a plan is not drawn; the card falls back to its icon.
         */
        const val MAX_POINTS: Int = 512
    }
}
