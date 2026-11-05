/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

import com.constructar.core.math.Vec3

/**
 * The floor a session is standing on, in the session's own coordinates.
 *
 * Every mounting height in the trade is measured from the *finished floor*: DIN 18015-3 puts
 * sockets at 30 cm and switches at 105 cm, and the ceiling outlet 15 cm under the ceiling. ARCore's
 * world origin is not that floor - it is wherever the session was started, usually with the phone
 * at chest height - so a node's `y` is a height above nothing an electrician would recognise.
 *
 * The app's answer is not to assume the floor is at zero (a socket would then be printed at 1.4 m
 * on every job) and not to guess it from the lowest node (a run 30 cm above the floor is the
 * *lowest node* of a room whose floor is 30 cm lower). It is the one source that is a measurement:
 * a horizontal, upward-facing plane the session tracked, i.e. the floor itself.
 *
 * The choice among several such planes is "the highest one at or below the viewer", because that
 * is the floor he is standing on - a room above a corridor has two horizontal planes and the lower
 * one belongs to the storey below. A plane further below the viewer than [maxDropM] is not a floor
 * in the same room; it is a floor seen through a stairwell, or a mis-tracked plane.
 */
object FloorReference {

    /** Assumed eye height ceiling for a floor search: nothing below 3.5 m under the phone. */
    const val MAX_DROP_M: Float = 3.5f

    /** A plane this close to horizontal counts as a floor or a ceiling. */
    const val VERTICAL_TOLERANCE: Float = 0.6f

    /**
     * The height of the floor under [viewerHeightM], or `null` when no tracked plane can be it.
     *
     * A floor plane's normal points up (out of the material), so `normal.y >= VERTICAL_TOLERANCE`
     * is the test - the same classifier `PlaneCandidate` uses for the picture, so the floor this
     * function names and the floor the app draws as "Floor" cannot be two different planes.
     */
    fun heightFrom(
        planes: List<PlaneCandidate>,
        viewerHeightM: Float,
        maxDropM: Float = MAX_DROP_M,
    ): Float? {
        if (!viewerHeightM.isFinite()) return null
        if (!maxDropM.isFinite() || maxDropM <= 0f) return null
        var best: Float? = null
        for (plane in planes) {
            if (!plane.isTracking) continue
            if (plane.subsumedBy != null) continue
            if (plane.polygon.size < 3) continue
            if (!plane.isReal()) continue
            // Upward-facing: a ceiling has the same |y| and the opposite sign, and picking it
            // would put the floor above the operator's head.
            if (plane.normal.y < VERTICAL_TOLERANCE) continue
            val offset = plane.offset
            if (!offset.isFinite()) continue
            // At or below the viewer, and not further away than one room can be.
            if (offset > viewerHeightM + 1e-3f) continue
            if (viewerHeightM - offset > maxDropM) continue
            // The highest such plane: the storey the operator is standing on.
            if (best == null || offset > best) best = offset
        }
        return best
    }

    /** A plane with no usable geometry contributes nothing to a height. */
    private fun PlaneCandidate.isReal(): Boolean =
        normal.isReal() && offset.isFinite() && polygon.all { it.isReal() }

    private fun Vec3.isReal(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}
