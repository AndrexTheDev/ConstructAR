/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.Vec3
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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

    /**
     * Below this a *single* point is not trusted at all: the tap is reported to the operator as it
     * happens, and the run is questioned again on the way out.
     *
     * This is the per-tap gate (`RouteSession.MIN_TRUSTED_CONFIDENCE`), not the bar a finished run
     * is judged by - see [RUN_TRUSTED].
     */
    const val TRUSTED: Float = 0.5f

    /**
     * The bar a **finished run** is measured against, in AR and in the paperwork.
     *
     * Every tier of the hit cascade is at or above it except the raycast (0.7) and the feature
     * point (0.4): 1.0 for a plane hit, 0.85 for a depth point. So a run whose weakest point is
     * below 0.85 has at least one point the app trusts less than a depth measurement.
     *
     * One number for both places on purpose. The HUD warned below 0.85 while the order list only
     * flagged a run below 0.5, so a run measured off a raycast was called out on the phone
     * ("weakest point 70 % - tap it again") and reached the wholesaler's list unflagged: the
     * paperwork was more optimistic than the screen the operator was looking at (finding 47).
     */
    const val RUN_TRUSTED: Float = 0.85f
}

/**
 * One vertex of a routed conduit, in ARCore world space (metres).
 *
 * While the run is being measured every node is also bound to an ARCore `Anchor`, and it is the
 * anchor's refined pose that the renderer and the measuring engine consume - that is how a run
 * keeps following the tracker. This record is what endures: at `finish()` the run's anchors are
 * released (finding 164) and the positions copied here are what every document, export and total
 * is computed from.
 */
@Serializable
data class RouteNodeRecord(
    val id: String,
    val position: Vec3,
    val normal: Vec3,
    val surface: SurfaceKind,
    val hitSource: HitSource,
    /**
     * The ARCore anchor this node sits on, for as long as the session that placed it is alive -
     * `null` for a node read from disk.
     *
     * `@Transient` because it is a **runtime handle, not data**: an ARCore anchor does not survive
     * its session, and this app releases a run's anchors the moment the run is finished, so a
     * saved id could only ever name something that is gone. Every project file used to carry one
     * per node anyway - a few kilobytes of dead uuid per flat - and a stored id is an invitation
     * to look one up later, which is exactly what the draft path already refuses to write
     * (finding 165). Position, normal, surface, source and confidence are the durable facts.
     */
    @Transient
    val anchorId: String? = null,
    /** Surface confidence at capture time (depth confidence or plane quality), 0..1. */
    val confidence: Float = 1f,
    /**
     * The AR session this point was measured in - the frame its coordinates belong to.
     *
     * ARCore's world origin is wherever the session started, and it does **not** survive the
     * process: two runs of the same room recorded a day apart live in two coordinate systems that
     * differ by a rotation and a translation nobody recorded. Every length is therefore still
     * exact (a distance is invariant under a rigid motion), but a *height above the floor* is not:
     * subtracting a floor measured today from a node measured yesterday mixes two frames and
     * produces a number that looks like a mounting height and is not one (finding 217).
     *
     * Empty for every node written before this field existed. A project with an empty id and
     * exactly one measured floor can still be resolved - there is only one answer it can have -
     * and the rule that does so lives in `:core` (`relativeNodes`), not in the screen.
     */
    val sessionId: String = "",
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
    /**
     * `true` when this run was recovered from a draft after the app was killed mid-measurement.
     *
     * Its **lengths, fitting counts and paperwork are exact** — they come from the recorded
     * points — but its *coordinates* are not: an ARCore world origin does not survive the
     * process, so the points are relative to a session that no longer exists. Therefore a
     * recovered run is deliberately kept out of the 3D export and out of the AR preview
     * (it would appear at a random place in the room) while counting fully in the bill of
     * materials and the protocol. Losing a measurement to a crash is the one failure this app
     * cannot accept; pretending the geometry is where it was would be the second.
     */
    val recovered: Boolean = false,
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
