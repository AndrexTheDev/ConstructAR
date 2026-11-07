/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

import com.constructar.core.math.Vec3
import com.constructar.core.model.SurfaceKind
import com.constructar.core.wall.Din18015Zones

/** Applies DIN 18015 only after reliable floor/ceiling references have been resolved. */
object InstallationZoneSnap {
    fun apply(
        hit: SurfaceHit,
        planes: List<PlaneCandidate>,
        floor: FloorDecision,
        roomHeightMeters: Float?,
    ): SurfaceHit {
        val floorHeight = floor.heightM?.takeIf { floor.usable } ?: return hit
        if (hit.surfaceKind != SurfaceKind.WALL) return hit
        val height = hit.point.y - floorHeight
        val cornerDistance = planes.firstOrNull { it.id == hit.planeId }?.polygon
            ?.minOfOrNull { corner ->
                Vec3(corner.x - hit.point.x, 0f, corner.z - hit.point.z).length
            } ?: Float.NaN
        val assignment = Din18015Zones.point(height, roomHeightMeters, cornerDistance)
        val snappedHeight = assignment.horizontal.snappedHeightMeters
        if (snappedHeight == null) {
            return hit.copy(
                installationZone = assignment.horizontal.zone,
                verticalInstallationZone = assignment.vertical,
                sonderverlegung = assignment.sonderverlegung,
            )
        }
        val targetY = floorHeight + snappedHeight
        val moved = targetY - hit.point.y
        return hit.copy(
            point = hit.point.copy(y = targetY),
            installationZone = assignment.horizontal.zone,
            verticalInstallationZone = assignment.vertical,
            sonderverlegung = assignment.sonderverlegung,
            snap = if (kotlin.math.abs(moved) > 0.0001f) {
                SnapInfo(SnapFeatureKind.INSTALLATION_ZONE, kotlin.math.abs(moved))
            } else {
                hit.snap
            },
        )
    }
}
