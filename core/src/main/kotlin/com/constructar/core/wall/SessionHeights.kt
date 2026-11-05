/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.math.Vec3
import com.constructar.core.model.RouteNodeRecord

/**
 * Where a node's height came from - and whether it may be used at all.
 *
 * A distance is invariant under a rigid motion, so a run measured in *any* session still has an
 * exact length. A height above the finished floor is not invariant: it is the difference between
 * two points in **one** coordinate system, and ARCore starts a new one every time the session
 * begins. Mixing the two is how a socket ends up printed at 1.4 m (finding 217).
 */
enum class NodeHeightStatus {
    /** The node's session has a measured floor: its relative height is a real measurement. */
    MEASURED,

    /**
     * The node was measured in a session whose floor was never measured.
     *
     * There is no transformation between two ARCore world origins stored anywhere in this app, so
     * this node cannot be assigned a mounting height at all. It is *not* dropped: it stays in the
     * list with this status so a screen can say how many points are affected instead of quietly
     * showing a smaller count.
     */
    UNTRANSFORMED_FOREIGN_SESSION,

    /** No floor has been measured yet - the map is empty, so nothing can be resolved. */
    NO_FLOOR_TRACKED,

    /** The stored position is not a number - a corrupt file, not a measurement. */
    NOT_A_MEASUREMENT,
}

/** One node's verdict, with the point that may be used when [status] is [NodeHeightStatus.MEASURED]. */
data class RelativeNode(
    val status: NodeHeightStatus,
    /**
     * The node's position relative to its own session's floor, in metres, with the calibration
     * factor applied - or `null` when the status is not [NodeHeightStatus.MEASURED].
     */
    val point: Vec3?,
    /** The session the node belongs to, as stored (empty for a file written before the field). */
    val sessionId: String,
)

/** The whole run's worth of nodes, resolved against the floors that were actually measured. */
data class RelativeNodes(val nodes: List<RelativeNode>) {

    /** Every point that may be classified into a DIN 18015 zone. */
    val points: List<Vec3> get() = nodes.mapNotNull { it.point }

    fun count(status: NodeHeightStatus): Int = nodes.count { it.status == status }

    val measuredCount: Int get() = count(NodeHeightStatus.MEASURED)
    val foreignSessionCount: Int get() = count(NodeHeightStatus.UNTRANSFORMED_FOREIGN_SESSION)
    val noFloorCount: Int get() = count(NodeHeightStatus.NO_FLOOR_TRACKED)
    val invalidCount: Int get() = count(NodeHeightStatus.NOT_A_MEASUREMENT)

    /** True when the list is not empty and every node could be resolved. */
    val complete: Boolean get() = nodes.isNotEmpty() && measuredCount == nodes.size
}

/**
 * Resolves every node against the floor of **its own** session.
 *
 * @param sessionFloorHeights floor height per AR session, in that session's coordinates
 *   (`ProjectRecord.sessionFloorHeights`)
 * @param metresScale the project's calibration factor, applied once, here - a relative height is a
 *   measured length and nothing else in this chain may scale it again
 * @param legacyFloorHeightM the single floor an older project file carries (`ProjectRecord.floorHeightM`),
 *   used only through the legacy rule below
 *
 * ## The legacy rule, and why it is not a guess
 *
 * A node written before `sessionId` existed has an empty id, and a project measured before
 * `sessionFloorHeights` existed carries one floor in [legacyFloorHeightM]. When there is exactly
 * **one** floor to choose from, "which session is this node from" has exactly one answer, so an
 * empty id is resolved to it. With two or more floors the question has no answer, and the node is
 * refused rather than given the most recent one - which is precisely the mistake this type exists
 * to prevent.
 */
fun relativeNodes(
    nodes: List<RouteNodeRecord>,
    sessionFloorHeights: Map<String, Float> = emptyMap(),
    metresScale: Float = 1f,
    legacyFloorHeightM: Float? = null,
): RelativeNodes {
    val scale = if (metresScale.isFinite() && metresScale > 0f) metresScale else 1f
    val floors = sessionFloorHeights.filterValues { it.isFinite() }
    val legacy = legacyFloorHeightM?.takeIf { it.isFinite() }
    val resolved = ArrayList<RelativeNode>(nodes.size)
    for (node in nodes) {
        val position = node.position
        if (!position.isReal()) {
            resolved += RelativeNode(NodeHeightStatus.NOT_A_MEASUREMENT, null, node.sessionId)
            continue
        }
        val floor = floorFor(node.sessionId, floors, legacy)
        if (floor == null) {
            // Two different answers, and only one of them is a step the operator can still take:
            // "no floor has been measured in this session yet" is fixable by walking the room,
            // "this node belongs to a session nobody measured a floor in" is not - no
            // transformation between two ARCore origins exists in this app.
            val status = if (floors.isEmpty()) {
                NodeHeightStatus.NO_FLOOR_TRACKED
            } else {
                NodeHeightStatus.UNTRANSFORMED_FOREIGN_SESSION
            }
            resolved += RelativeNode(status, null, node.sessionId)
            continue
        }
        val relative = Vec3(
            x = position.x * scale,
            y = (position.y - floor) * scale,
            z = position.z * scale,
        )
        resolved += RelativeNode(NodeHeightStatus.MEASURED, relative, node.sessionId)
    }
    return RelativeNodes(resolved)
}

/**
 * The floor a node may be measured from, or `null` when it has none that can be used.
 *
 * An exact session match is the only certain answer. The legacy case - an empty id, or no map at
 * all - is answered only when there is exactly one floor in the project, because then every
 * candidate would give the same number.
 */
private fun floorFor(sessionId: String, floors: Map<String, Float>, legacy: Float?): Float? {
    floors[sessionId]?.let { return it }
    if (sessionId.isNotEmpty()) return null
    return when {
        floors.size == 1 -> floors.values.first()
        floors.isEmpty() -> legacy
        else -> null
    }
}

/** A non-finite coordinate is a corrupt file, not a measurement with a small error. */
private fun Vec3.isReal(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
