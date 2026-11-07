/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

/** Floor/ceiling references for DIN 18015 and automatic DIN 18181 room quantities. */
object RoomReference {
    fun roomHeightMeters(
        planes: List<PlaneCandidate>,
        floor: FloorDecision,
        viewerHeightMeters: Float,
    ): Float? {
        val floorHeight = floor.heightM?.takeIf { floor.usable && it.isFinite() } ?: return null
        val ceiling = planes.asSequence()
            .filter { it.isTracking && it.subsumedBy == null && !it.isVertical }
            // A ceiling faces down into the room; an upward plane above the phone is a shelf.
            .filter { it.normal.y < -0.6f && it.center.y > viewerHeightMeters + 0.20f }
            .filter { it.areaSquareMeters >= 0.25f }
            .map { it.center.y }
            .filter { it.isFinite() && it > floorHeight }
            .minOrNull() ?: return null
        return (ceiling - floorHeight).takeIf { it in 2.0f..4.5f }
    }
}
