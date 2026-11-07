/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.geo.PolylineMetrics
import com.constructar.core.math.Vec3
import kotlinx.serialization.Serializable

/** ARCore feature points relative to one route anchor, never absolute session coordinates. */
@Serializable
data class FeatureCluster(
    val nodeId: String,
    val relativePoints: List<Vec3> = emptyList(),
)

/** A persisted graph edge and its nearby visual evidence for one route node. */
@Serializable
data class AnchorEvidence(
    val nodeId: String,
    val previousNodeId: String? = null,
    val relativeVectorMeters: Vec3? = null,
    val featureCluster: FeatureCluster? = null,
)

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
    /** Session-independent evidence used by the offline Kabsch/anchor-graph relocaliser. */
    val evidence: List<AnchorEvidence> = emptyList(),
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
    fun toRoute(
        id: String,
        label: String,
        createdAtEpochMs: Long,
        relocalisedNodes: List<RouteNodeRecord>? = null,
    ): ConduitRoute? {
        if (!isRecoverable) return null
        if (relocalisedNodes != null && relocalisedNodes.size != nodes.size) return null
        return ConduitRoute(
            id = id,
            label = label,
            spec = spec,
            // `anchorId` is transient on the model, so a draft read back from disk carries none:
            // the anchors of the session that measured it are gone with it (finding 165). The
            // stripping that used to happen here is now the model's own guarantee, for drafts and
            // for finished runs alike.
            nodes = relocalisedNodes ?: nodes,
            createdAtEpochMs = createdAtEpochMs,
            closedLoop = closedLoop,
            recovered = relocalisedNodes == null,
            relocalised = relocalisedNodes != null,
        )
    }
}
