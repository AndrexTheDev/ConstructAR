/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.Vec3
import kotlinx.serialization.Serializable

/**
 * Which tier of the hit-test cascade produced a node. Persisted so a reviewer can tell a
 * solid plane hit from a soft feature-point guess — and so the export can flag
 * low-confidence runs that need a manual re-check on site.
 */
@Serializable
enum class HitSource {
    /** Tier 1 — ARCore plane with `isPoseInPolygon` containment. */
    PLANE_POLYGON,

    /** Tier 2 — ARCore `DepthPoint` from `Frame.hitTest`. */
    AR_DEPTH_POINT,

    /** Tier 3 — direct raycast against the current depth image (`hitTestDepth`). */
    DIRECT_DEPTH_RAYCAST,

    /** Tier 4 — feature-point fallback. */
    FEATURE_POINT,

    /** Point typed/averaged by the operator when nothing else hit. */
    MANUAL,
    ;

    /** Confidence used for UI colouring and export warnings (0..1). */
    val reliability: Float
        get() = when (this) {
            PLANE_POLYGON -> 1.0f
            AR_DEPTH_POINT -> 0.85f
            DIRECT_DEPTH_RAYCAST -> 0.7f
            FEATURE_POINT -> 0.4f
            MANUAL -> 0.2f
        }
}

/**
 * The one number that decides whether a measured point may be trusted without review.
 *
 * Two unrelated places asked the same question and could have answered it differently: the AR
 * session warns while a point is being taken (`RouteWarning.LOW_CONFIDENCE`), and the bill of
 * materials decides whether an order line needs a second look. A run measured on a plane the
 * tracker itself only half-believes is exactly as soft as one measured on a feature point, so
 * both read the threshold from here.
 */
object HitConfidence {
    /** Below this, a hit is reported to the operator and flagged in the paperwork. */
    const val TRUSTED: Float = 0.5f
}

/**
 * One vertex of a routed conduit, in ARCore world space (metres).
 *
 * The live, runtime position is not read from here: every node is bound to an ARCore
 * `Anchor`, and the anchor's refined pose is what the renderer and the measuring engine
 * consume. This record is the durable fallback (and what gets exported when anchors are
 * gone after the session ends).
 */
@Serializable
data class RouteNodeRecord(
    val id: String,
    val position: Vec3,
    val normal: Vec3,
    val surface: SurfaceKind,
    val hitSource: HitSource,
    /** Runtime anchor identifier; not the ARCore `Anchor` itself (not serialisable). */
    val anchorId: String? = null,
    /** Surface confidence at capture time (depth confidence or plane quality), 0..1. */
    val confidence: Float = 1f,
) {
    fun withPosition(newPosition: Vec3) = copy(position = newPosition)
}

/**
 * A single routed run of one conduit type: cable, pipe or duct.
 *
 * Stored in the project file; geometry is generated on demand (see `TubeMeshGenerator`
 * and `DuctMeshGenerator`) so the project stays a few kilobytes instead of megabytes.
 */
@Serializable
data class ConduitRoute(
    val id: String,
    val label: String,
    val spec: ConduitSpec,
    val nodes: List<RouteNodeRecord>,
    val createdAtEpochMs: Long,
    /** `true` when the operator closed the loop (rings / closed circuits). */
    val closedLoop: Boolean = false,
    val notes: String = "",
) {
    val points: List<Vec3> get() = nodes.map { it.position }

    /** Straight-line length of the raw node chain, metres. */
    val polylineLengthMeters: Float
        get() {
            var sum = 0f
            for (i in 0 until nodes.size - 1) sum += nodes[i].position.distanceTo(nodes[i + 1].position)
            if (closedLoop && nodes.size > 2) sum += nodes.last().position.distanceTo(nodes.first().position)
            return sum
        }

    val isRoutable: Boolean get() = nodes.size >= 2
}
