/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.hit.FloorDecision
import com.constructar.arengine.hit.FloorReferenceStatus
import com.constructar.arengine.hit.InstallationZoneSnap
import com.constructar.arengine.hit.PlaneCandidate
import com.constructar.arengine.hit.RoomReference
import com.constructar.arengine.hit.SurfaceHit
import com.constructar.core.math.Vec3
import com.constructar.core.model.HitSource
import com.constructar.core.model.SurfaceKind
import com.constructar.core.wall.Din18015Zones
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstallationZoneTest {
    private fun plane(
        id: String,
        normal: Vec3,
        height: Float,
        area: Float = 16f,
        tracking: Boolean = true,
        subsumed: String? = null,
        vertical: Boolean = false,
    ) = PlaneCandidate(
        id = id,
        polygon = listOf(
            Vec3(-2f, height, -2f),
            Vec3(2f, height, -2f),
            Vec3(2f, height, 2f),
            Vec3(-2f, height, 2f),
        ),
        normal = normal,
        offset = normal.y * height,
        center = Vec3(0f, height, 0f),
        areaSquareMeters = area,
        isTracking = tracking,
        subsumedBy = subsumed,
        isVertical = vertical,
    )

    private val floor = FloorDecision(
        heightM = 0f,
        status = FloorReferenceStatus.TRACKED,
        confidence = 1f,
        gap = 1f,
        candidateCount = 1,
    )

    private fun hit(height: Float, x: Float = 1f) = SurfaceHit(
        point = Vec3(x, height, 0f),
        normal = Vec3(0f, 0f, 1f),
        source = HitSource.PLANE_POLYGON,
        confidence = 1f,
        distanceMeters = 2f,
        planeId = "wall",
        surfaceKind = SurfaceKind.WALL,
    )

    private val wall = PlaneCandidate(
        id = "wall",
        polygon = listOf(Vec3(0f, 0f, 0f), Vec3(4f, 0f, 0f), Vec3(4f, 2.6f, 0f), Vec3(0f, 2.6f, 0f)),
        normal = Vec3(0f, 0f, 1f),
        offset = 0f,
        center = Vec3(2f, 1.3f, 0f),
        areaSquareMeters = 10.4f,
        isVertical = true,
    )

    @Test
    fun `room height needs a usable floor and a downward ceiling above the viewer`() {
        val ceiling = plane("ceiling", Vec3(0f, -1f, 0f), 2.6f)
        assertEquals(2.6f, RoomReference.roomHeightMeters(listOf(ceiling), floor, 1.4f)!!, 1e-5f)

        val upwardShelf = plane("shelf", Vec3(0f, 1f, 0f), 2.6f)
        assertNull(RoomReference.roomHeightMeters(listOf(upwardShelf), floor, 1.4f))
        assertNull(RoomReference.roomHeightMeters(listOf(ceiling), floor.copy(status = FloorReferenceStatus.UNCERTAIN), 1.4f))
        assertNull(RoomReference.roomHeightMeters(listOf(ceiling), floor, 2.5f))
    }

    @Test
    fun `untracked merged tiny and implausible ceilings are refused`() {
        val valid = plane("ceiling", Vec3(0f, -1f, 0f), 2.6f)
        assertNull(RoomReference.roomHeightMeters(listOf(valid.copy(isTracking = false)), floor, 1.4f))
        assertNull(RoomReference.roomHeightMeters(listOf(valid.copy(subsumedBy = "other")), floor, 1.4f))
        assertNull(RoomReference.roomHeightMeters(listOf(valid.copy(areaSquareMeters = 0.2f)), floor, 1.4f))
        assertNull(RoomReference.roomHeightMeters(listOf(valid.copy(center = Vec3(0f, 1.8f, 0f))), floor, 1.4f))
        assertNull(RoomReference.roomHeightMeters(listOf(valid.copy(center = Vec3(0f, 4.8f, 0f))), floor, 1.4f))
    }

    @Test
    fun `accepted horizontal points snap and retain their DIN classification`() {
        val result = InstallationZoneSnap.apply(hit(0.324f), listOf(wall), floor, 2.6f)
        assertEquals(0.30f, result.point.y, 1e-5f)
        assertEquals(Din18015Zones.Horizontal.ZW_U, result.installationZone)
        assertEquals(Din18015Zones.Vertical.OUTSIDE, result.verticalInstallationZone)
        assertFalse(result.sonderverlegung)
        assertEquals(0.024f, result.snap?.distanceM ?: 0f, 1e-5f)
        assertEquals(result.installationZone, result.toRouteNode("n").installationZone)
    }

    @Test
    fun `Sonderverlegung stays exactly where it was measured`() {
        val original = hit(0.70f)
        val result = InstallationZoneSnap.apply(original, listOf(wall), floor, 2.6f)
        assertEquals(original.point, result.point)
        assertEquals(Din18015Zones.Horizontal.SONDER, result.installationZone)
        assertTrue(result.sonderverlegung)
        assertNull(result.snap)
        assertTrue(result.toRouteNode("n").sonderverlegung)
    }

    @Test
    fun `a vertical corner zone is valid without being snapped horizontally`() {
        val original = hit(0.70f, x = 0.10f)
        val result = InstallationZoneSnap.apply(original, listOf(wall), floor, 2.6f)
        assertEquals(original.point, result.point)
        assertEquals(Din18015Zones.Vertical.ZW_V, result.verticalInstallationZone)
        assertFalse(result.sonderverlegung)
        assertEquals(Din18015Zones.Vertical.ZW_V, result.toRouteNode("n").verticalInstallationZone)
    }

    @Test
    fun `an uncertain floor never acquires a DIN classification`() {
        val original = hit(0.324f)
        val result = InstallationZoneSnap.apply(
            original,
            listOf(wall),
            floor.copy(status = FloorReferenceStatus.UNCERTAIN),
            2.6f,
        )
        assertSame(original, result)
    }
}
