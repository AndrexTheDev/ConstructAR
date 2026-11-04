/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.math.Vec3
import kotlin.math.abs

/**
 * DIN 18015-3 mounting heights, and the snapping that puts a measured point on one.
 *
 * The standard fixes the heights a German electrician works to: sockets at 30 cm above the finished
 * floor, switches at 105 cm, and ceiling outlets 15 cm below the ceiling. A tap in AR never lands
 * exactly there - the reticle rests on a wall surface and the operator is aiming at a *height*, not
 * a millimetre - so every point arrives some centimetres off and has to be assigned to one of the
 * three zones or refused.
 *
 * What this deliberately does not do is invent a height. A point 25 cm from the socket line is not
 * a socket: it is either a measurement to take again or an installation the standard does not
 * cover, and snapping it silently would put a socket at 55 cm on a drawing that then claims
 * "DIN 18015". Only points inside the tolerance snap, refused ones are counted with a reason, and
 * the nearest zone is still named so a screen can say *which* line the point was near.
 *
 * The ceiling zone moves with the ceiling instead of being a fixed height, because 2.35 m is right
 * for a 2.50 m room and wrong for a 3.20 m one. A room lower than the drop itself has no ceiling
 * zone at all - the report says so, and the ceiling zone is simply not offered.
 */

/** Sockets, DIN 18015-3: 30 cm above the finished floor. */
const val SOCKET_HEIGHT_M: Float = 0.30f

/** Switches, DIN 18015-3: 105 cm above the finished floor. */
const val SWITCH_HEIGHT_M: Float = 1.05f

/** Ceiling outlets sit this far below the ceiling. */
const val CEILING_OUTLET_DROP_M: Float = 0.15f

private const val TIE_EPSILON = 1e-4f

/** The installation zones the app knows. */
enum class InstallationZone { SOCKET, SWITCH, CEILING }

/** Why a measured height was not assigned to a zone. */
enum class ZoneRejection {
    /** A height (or a ceiling height, or the tolerance) that is not a real number. */
    NON_FINITE,

    /** Below the finished floor. */
    BELOW_FLOOR,

    /** Above the ceiling. */
    ABOVE_CEILING,

    /** Real, inside the room, but not close enough to any zone to be one of them. */
    OUT_OF_TOLERANCE,
}

/** How far a measured height may be from a zone and still be snapped onto it. */
data class ZoneSnapOptions(
    /** Default 10 cm: the zones are 75 cm apart, so this can never steal one from another. */
    val toleranceM: Float = 0.10f,
)

/**
 * One measured height, assigned or refused.
 *
 * [zone] is null when [accepted] is false, and [targetHeightM] is then the zone the point was
 * *nearest* - the number a screen needs to say "that is 25 cm above the socket line".
 */
data class ZoneSnap(
    val zone: InstallationZone?,
    val targetHeightM: Float,
    /** Signed difference between the measured height and the target: positive is too high. */
    val offsetM: Float,
    val rejection: ZoneRejection?,
) {
    val accepted: Boolean get() = zone != null
}

/**
 * Assigns [measuredHeightM] to a DIN 18015 zone, or refuses it.
 *
 * @param ceilingHeightM the room's ceiling, which fixes the ceiling zone (ceiling − 15 cm)
 */
fun snapToInstallationZone(
    measuredHeightM: Float,
    ceilingHeightM: Float = DEFAULT_WALL_HEIGHT_M,
    options: ZoneSnapOptions = ZoneSnapOptions(),
): ZoneSnap {
    if (!measuredHeightM.isFinite() || !ceilingHeightM.isFinite() ||
        !options.toleranceM.isFinite() || options.toleranceM < 0f
    ) {
        return ZoneSnap(null, 0f, 0f, ZoneRejection.NON_FINITE)
    }

    val targets = zonesFor(ceilingHeightM)
    if (measuredHeightM < 0f) {
        val target = nearestTarget(measuredHeightM, targets)
        return ZoneSnap(null, target, measuredHeightM - target, ZoneRejection.BELOW_FLOOR)
    }
    if (measuredHeightM > ceilingHeightM) {
        val target = nearestTarget(measuredHeightM, targets)
        return ZoneSnap(null, target, measuredHeightM - target, ZoneRejection.ABOVE_CEILING)
    }

    var bestTarget = targets[0].first
    var bestZone = targets[0].second
    var bestDistance = abs(measuredHeightM - bestTarget)
    for (index in 1 until targets.size) {
        val (target, zone) = targets[index]
        val distance = abs(measuredHeightM - target)
        // A tie goes to the lower zone. The zones are 75 cm apart, so a genuine tie means the point
        // is not between two of them, and "the socket" is the safer of two wrong answers.
        if (distance < bestDistance - TIE_EPSILON) {
            bestTarget = target
            bestZone = zone
            bestDistance = distance
        }
    }
    return if (bestDistance <= options.toleranceM) {
        ZoneSnap(bestZone, bestTarget, measuredHeightM - bestTarget, null)
    } else {
        ZoneSnap(null, bestTarget, measuredHeightM - bestTarget, ZoneRejection.OUT_OF_TOLERANCE)
    }
}

/** The zones a room of this height has: the two wall lines, and a ceiling line when there is one. */
private fun zonesFor(ceilingHeightM: Float): List<Pair<Float, InstallationZone>> {
    val zones = ArrayList<Pair<Float, InstallationZone>>(3)
    zones += SOCKET_HEIGHT_M to InstallationZone.SOCKET
    zones += SWITCH_HEIGHT_M to InstallationZone.SWITCH
    val ceilingZone = ceilingHeightM - CEILING_OUTLET_DROP_M
    if (ceilingZone > 0f) zones += ceilingZone to InstallationZone.CEILING
    return zones
}

/** Nearest zone to a height that is outside the room, so a refusal can still name where it aimed. */
private fun nearestTarget(
    measuredHeightM: Float,
    targets: List<Pair<Float, InstallationZone>>,
): Float {
    var best = targets[0].first
    var bestDistance = abs(measuredHeightM - best)
    for (index in 1 until targets.size) {
        val target = targets[index].first
        val distance = abs(measuredHeightM - target)
        if (distance < bestDistance - TIE_EPSILON) {
            best = target
            bestDistance = distance
        }
    }
    return best
}

/** One point of an installation map. */
data class ZoneAssignment(val index: Int, val point: Vec3, val snap: ZoneSnap)

/** The zones a room's measured points landed in, with everything that did not land. */
data class InstallationPlan(
    val assignments: List<ZoneAssignment>,
    val socketCount: Int,
    val switchCount: Int,
    val ceilingCount: Int,
    val rejectedCount: Int,
    /** False in a room lower than the ceiling drop: there is no ceiling zone to offer. */
    val ceilingZoneAvailable: Boolean,
    val valid: Boolean,
)

/**
 * Assigns every measured point to a zone.
 *
 * Points are the route nodes the AR screen already collects; their `y` is the height. Nothing is
 * dropped: a refused point stays in [InstallationPlan.assignments] with its rejection, so a screen
 * can show the operator what it could not place instead of quietly returning a shorter list.
 */
fun classifyInstallations(
    points: List<Vec3>,
    ceilingHeightM: Float = DEFAULT_WALL_HEIGHT_M,
    options: ZoneSnapOptions = ZoneSnapOptions(),
): InstallationPlan {
    val assignments = points.mapIndexed { index, point ->
        ZoneAssignment(index, point, snapToInstallationZone(point.y, ceilingHeightM, options))
    }
    return InstallationPlan(
        assignments = assignments,
        socketCount = assignments.count { it.snap.zone == InstallationZone.SOCKET },
        switchCount = assignments.count { it.snap.zone == InstallationZone.SWITCH },
        ceilingCount = assignments.count { it.snap.zone == InstallationZone.CEILING },
        rejectedCount = assignments.count { !it.snap.accepted },
        ceilingZoneAvailable = ceilingHeightM.isFinite() && ceilingHeightM > CEILING_OUTLET_DROP_M,
        valid = points.isNotEmpty(),
    )
}
