/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.PolygonMath
import com.constructar.core.math.Vec3
import kotlinx.serialization.Serializable

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
 * open) - the card falls back to its icon rather than inventing a wall across the door.
 */
@Serializable
data class FloorplanSketch(
    val points: List<SketchPoint> = emptyList(),
) {

    /** Three corners are the least that can enclose anything. */
    val isDrawable: Boolean get() = points.size >= 3 && points.size <= MAX_POINTS

    /**
     * Enclosed area, square metres, from the same `PolygonMath.area` the protocol prints - so the
     * picture and the printed area describe one shape.
     */
    val areaSquareMeters: Float
        get() = if (points.size < 3) 0f else PolygonMath.area(toFloorPolygon())

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
