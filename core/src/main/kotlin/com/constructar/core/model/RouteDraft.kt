/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.geo.PolylineMetrics
import kotlinx.serialization.Serializable

/**
 * A measurement that is still in progress, written to disk as it grows.
 *
 * Until now a run lived only in the AR session: `RouteSession` held the points, ARCore anchors
 * held the positions, and both died with the process. Android kills a backgrounded app whenever
 * it needs the memory - and a tradesman measures a wall, gets a phone call, answers it, and comes
 * back to an app that has been restarted. Everything he had tapped was gone, with no warning and
 * nothing to restore. That is the failure every competitor's review page mentions, and no other
 * feature compensates for it.
 *
 * So every committed point is also written here, atomically, keyed by project. On the next start
 * the run is offered back:
 *
 *  * what survives exactly is what the bill of materials and the protocol need - the points,
 *    the conduit spec and whether the loop was closed;
 *  * what cannot survive is the **frame**. An ARCore world origin belongs to one session, so the
 *    coordinates of a recovered run describe a room that no longer exists in that space. The
 *    recovered [ConduitRoute] is therefore marked [ConduitRoute.recovered]: it counts in every
 *    length and every fitting, and it is kept out of the 3D model and the AR preview, where it
 *    would be a segment floating in the wrong place.
 *
 * A draft with fewer than two points is not a measurement and is never offered.
 */
@Serializable
data class RouteDraft(
    val projectId: String,
    val spec: ConduitSpec,
    val nodes: List<RouteNodeRecord>,
    val closedLoop: Boolean = false,
    /** When the draft was last written, so the operator can tell which run this is. */
    val savedAtEpochMs: Long = 0L,
) {

    /** Two points make a measurement; one tap is a mis-tap. */
    val isRecoverable: Boolean get() = nodes.size >= 2

    /** The length the operator saw on the HUD when the app died. */
    val lengthMeters: Float
        get() = if (nodes.isEmpty()) 0f else PolylineMetrics.measure(nodes, spec, closedLoop).totalMeters

    /**
     * The draft as a finished route, or `null` when there is nothing worth keeping.
     *
     * The caller supplies the identity, so a recovered run is stored exactly like a measured one
     * (one id scheme, one label scheme) and differs only in [ConduitRoute.recovered].
     */
    fun toRoute(id: String, label: String, createdAtEpochMs: Long): ConduitRoute? {
        if (!isRecoverable) return null
        return ConduitRoute(
            id = id,
            label = label,
            spec = spec,
            // The anchor ids belong to the ARCore session that measured them; that session is
            // gone, and a dangling id invites a later caller to look one up.
            nodes = nodes.map { it.copy(anchorId = null) },
            createdAtEpochMs = createdAtEpochMs,
            closedLoop = closedLoop,
            recovered = true,
        )
    }
}
