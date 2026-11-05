/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.hit.ArchitecturalSnap
import com.constructar.arengine.hit.PlaneCandidate
import com.constructar.arengine.hit.SnapFeature
import com.constructar.arengine.hit.SnapFeatureKind
import com.constructar.arengine.hit.SnapOptions
import com.constructar.core.math.PolygonMath
import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corners and edges are the lines a fitter measures to, and they are the only exact thing in an
 * AR session - everything else is a plane with noise on it.
 *
 * The case that matters is the one the algebra gets wrong on its own: two walls that do *not* touch
 * still intersect in an infinite line, and snapping to a corner that exists only in the maths puts
 * a measurement on a wall that is not there. Every feature here is clipped to both polygons.
 */
class ArchitecturalSnapTest {

    private fun plane(
        id: String,
        normal: Vec3,
        offset: Float,
        polygon: List<Vec3>,
        isTracking: Boolean = true,
        subsumedBy: String? = null,
    ): PlaneCandidate {
        var sum = Vec3(0f, 0f, 0f)
        for (vertex in polygon) sum += vertex
        return PlaneCandidate(
            id = id,
            polygon = polygon,
            normal = normal,
            offset = offset,
            center = sum / polygon.size.toFloat(),
            areaSquareMeters = PolygonMath.area(polygon),
            isTracking = isTracking,
            subsumedBy = subsumedBy,
        )
    }

    /** The wall at x = 0, 4 m long and 2.50 m high. */
    private fun wallAtXZero() = plane(
        "wall-x",
        Vec3(1f, 0f, 0f),
        0f,
        listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 4f), Vec3(0f, 2.5f, 4f), Vec3(0f, 2.5f, 0f)),
    )

    /** The wall at z = 0, 4 m long and 2.50 m high. */
    private fun wallAtZZero() = plane(
        "wall-z",
        Vec3(0f, 0f, 1f),
        0f,
        listOf(Vec3(0f, 0f, 0f), Vec3(4f, 0f, 0f), Vec3(4f, 2.5f, 0f), Vec3(0f, 2.5f, 0f)),
    )

    private fun floor() = plane(
        "floor",
        Vec3(0f, 1f, 0f),
        0f,
        listOf(Vec3(0f, 0f, 0f), Vec3(4f, 0f, 0f), Vec3(4f, 0f, 4f), Vec3(0f, 0f, 4f)),
    )

    private fun ceiling() = plane(
        "ceiling",
        Vec3(0f, -1f, 0f),
        -2.5f,
        listOf(Vec3(0f, 2.5f, 0f), Vec3(0f, 2.5f, 4f), Vec3(4f, 2.5f, 4f), Vec3(4f, 2.5f, 0f)),
    )

    @Test
    fun `two walls make a vertical corner, clipped to both of them`() {
        val features = ArchitecturalSnap.features(listOf(wallAtXZero(), wallAtZZero()))
        assertEquals(1, features.size)
        val corner = features[0]
        assertEquals(SnapFeatureKind.CORNER, corner.kind)
        assertEquals(0f, corner.start.x, 1e-5f)
        assertEquals(0f, corner.start.y, 1e-5f)
        assertEquals(0f, corner.start.z, 1e-5f)
        assertEquals(2.5f, corner.end.y, 1e-5f)
        assertEquals(2.5f, corner.lengthM, 1e-5f)
        assertEquals("wall-x", corner.firstPlaneId)
        assertEquals("wall-z", corner.secondPlaneId)
    }

    @Test
    fun `a wall and a floor make a floor edge`() {
        val features = ArchitecturalSnap.features(listOf(wallAtXZero(), floor()))
        assertEquals(1, features.size)
        val edge = features[0]
        assertEquals(SnapFeatureKind.FLOOR_EDGE, edge.kind)
        assertEquals(0f, edge.start.y, 1e-5f)
        assertEquals(0f, edge.end.y, 1e-5f)
        assertEquals(4f, edge.lengthM, 1e-5f)
        assertEquals(0f, edge.start.z, 1e-5f)
        assertEquals(4f, edge.end.z, 1e-5f)
    }

    @Test
    fun `a wall and a ceiling make a ceiling edge, labelled by the ceiling's own normal`() {
        val features = ArchitecturalSnap.features(listOf(wallAtXZero(), ceiling()))
        assertEquals(1, features.size)
        val edge = features[0]
        assertEquals(SnapFeatureKind.CEILING_EDGE, edge.kind)
        assertEquals(2.5f, edge.start.y, 1e-5f)
        assertEquals(2.5f, edge.end.y, 1e-5f)
        assertEquals(4f, edge.lengthM, 1e-5f)
        // A ceiling scanned from above would carry the opposite normal; the label follows the
        // normal, not the line, so a flipped plane cannot pass a ceiling edge off as a floor edge.
        val flipped = ceiling().copy(id = "ceiling-flipped", normal = Vec3(0f, 1f, 0f), offset = 2.5f)
        val flippedFeatures = ArchitecturalSnap.features(listOf(wallAtXZero(), flipped))
        assertEquals(SnapFeatureKind.FLOOR_EDGE, flippedFeatures[0].kind)
    }

    @Test
    fun `parallel planes produce no line at all`() {
        val opposite = wallAtXZero().copy(id = "wall-x-far", normal = Vec3(-1f, 0f, 0f), offset = -4f)
        assertTrue(ArchitecturalSnap.features(listOf(wallAtXZero(), opposite)).isEmpty())
    }

    @Test
    fun `coplanar planes produce no line`() {
        val same = wallAtZZero().copy(id = "wall-z-again")
        assertTrue(ArchitecturalSnap.features(listOf(wallAtZZero(), same)).isEmpty())
    }

    @Test
    fun `walls that do not reach each other produce no corner`() {
        // The z = 5 wall stops short of the x = 0 wall, which only runs to z = 4. Algebraically the
        // planes still intersect in a vertical line at (0, y, 5) - and it is a corner of nothing.
        val farWall = plane(
            "wall-z-far",
            Vec3(0f, 0f, 1f),
            5f,
            listOf(Vec3(0f, 0f, 5f), Vec3(4f, 0f, 5f), Vec3(4f, 2.5f, 5f), Vec3(0f, 2.5f, 5f)),
        )
        assertTrue(ArchitecturalSnap.features(listOf(wallAtXZero(), farWall)).isEmpty())
    }

    @Test
    fun `a sliver of overlap is not a corner worth snapping to`() {
        val stunted = plane(
            "wall-z-stunted",
            Vec3(0f, 0f, 1f),
            0f,
            listOf(Vec3(0f, 0f, 0f), Vec3(4f, 0f, 0f), Vec3(4f, 0.1f, 0f), Vec3(0f, 0.1f, 0f)),
        )
        assertTrue(ArchitecturalSnap.features(listOf(wallAtXZero(), stunted)).isEmpty())
        val relaxed = ArchitecturalSnap.features(
            listOf(wallAtXZero(), stunted),
            SnapOptions(minFeatureLengthM = 0.05f),
        )
        assertEquals(1, relaxed.size)
        assertEquals(0.1f, relaxed[0].lengthM, 1e-4f)
    }

    @Test
    fun `an untracked or subsumed plane contributes nothing`() {
        val untracked = wallAtZZero().copy(isTracking = false)
        assertTrue(ArchitecturalSnap.features(listOf(wallAtXZero(), untracked)).isEmpty())
        val subsumed = wallAtZZero().copy(subsumedBy = "wall-z-bigger")
        assertTrue(ArchitecturalSnap.features(listOf(wallAtXZero(), subsumed)).isEmpty())
    }

    @Test
    fun `the feature list is the same twice, and ordered`() {
        val planes = listOf(wallAtXZero(), wallAtZZero(), floor(), ceiling())
        val first = ArchitecturalSnap.features(planes)
        val second = ArchitecturalSnap.features(planes)
        assertEquals(first, second)
        first.forEach { feature ->
            assertTrue(feature.start.x < feature.end.x ||
                (feature.start.x == feature.end.x && feature.start.y < feature.end.y) ||
                (feature.start.x == feature.end.x && feature.start.y == feature.end.y &&
                    feature.start.z <= feature.end.z))
        }
    }

    @Test
    fun `a hit near a corner is pulled onto it`() {
        val features = ArchitecturalSnap.features(listOf(wallAtXZero(), wallAtZZero()))
        val result = ArchitecturalSnap.snapToFeatures(Vec3(0.05f, 1.2f, 0.03f), features)
        assertNotNull(result)
        val snap = result!!
        assertTrue(snap.snapped)
        assertEquals(SnapFeatureKind.CORNER, snap.feature?.kind)
        assertEquals(0f, snap.point.x, 1e-5f)
        assertEquals(0f, snap.point.z, 1e-5f)
        assertEquals(1.2f, snap.point.y, 1e-5f)
        assertEquals(0.0583f, snap.distanceM ?: -1f, 1e-3f)
    }

    @Test
    fun `a hit further than the tolerance is left where it was`() {
        val features = ArchitecturalSnap.features(listOf(wallAtXZero(), wallAtZZero()))
        val hit = Vec3(0.20f, 1.2f, 0f)
        val result = ArchitecturalSnap.snapToFeatures(hit, features)!!
        assertFalse(result.snapped)
        assertNull(result.feature)
        assertEquals(hit, result.point)
        assertEquals(0.2f, result.distanceM ?: -1f, 1e-5f)
    }

    @Test
    fun `the nearest feature wins`() {
        val features = listOf(
            SnapFeature(SnapFeatureKind.FLOOR_EDGE, Vec3(0f, 0f, 0f), Vec3(0f, 0f, 4f), "wall-x", "floor"),
            SnapFeature(SnapFeatureKind.CORNER, Vec3(0f, 0f, 0f), Vec3(0f, 2.5f, 0f), "wall-x", "wall-z"),
        )
        // 2.8 cm from the corner, 5.4 cm from the floor edge.
        val result = ArchitecturalSnap.snapToFeatures(Vec3(0.02f, 0.05f, 0.02f), features)!!
        assertEquals(SnapFeatureKind.CORNER, result.feature?.kind)
        assertEquals(0f, result.point.x, 1e-5f)
        assertEquals(0.05f, result.point.y, 1e-5f)
        assertEquals(0.0283f, result.distanceM ?: -1f, 1e-3f)
    }

    @Test
    fun `nothing to snap to leaves the hit alone and says so`() {
        val hit = Vec3(1f, 1f, 1f)
        val result = ArchitecturalSnap.snapToFeatures(hit, emptyList())!!
        assertFalse(result.snapped)
        assertEquals(hit, result.point)
        assertNull(result.distanceM)

        val noPlanes = ArchitecturalSnap.snap(hit, emptyList())!!
        assertFalse(noPlanes.snapped)
    }

    @Test
    fun `a hit that is not a point is refused rather than snapped`() {
        val features = ArchitecturalSnap.features(listOf(wallAtXZero(), wallAtZZero()))
        assertNull(ArchitecturalSnap.snapToFeatures(Vec3(Float.NaN, 1f, 0f), features))
        assertNull(ArchitecturalSnap.snapToFeatures(Vec3(0f, 1f, 0f), features, SnapOptions(toleranceM = -1f)))
        assertNull(
            ArchitecturalSnap.snapToFeatures(
                Vec3(0f, 1f, 0f),
                features,
                SnapOptions(toleranceM = Float.NaN),
            ),
        )
    }

    @Test
    fun `the house snap follows the room it is given`() {
        val planes = listOf(wallAtXZero(), wallAtZZero(), floor(), ceiling())
        val features = ArchitecturalSnap.features(planes)
        assertEquals(5, features.size)
        assertEquals(1, features.count { it.kind == SnapFeatureKind.CORNER })
        assertEquals(2, features.count { it.kind == SnapFeatureKind.FLOOR_EDGE })
        assertEquals(2, features.count { it.kind == SnapFeatureKind.CEILING_EDGE })

        // Two metres along the wall, 4 cm under the ceiling: the ceiling edge, not the corner.
        val nearCeiling = ArchitecturalSnap.snap(Vec3(0.03f, 2.47f, 2f), planes)!!
        assertEquals(SnapFeatureKind.CEILING_EDGE, nearCeiling.feature?.kind)
        assertEquals(2.5f, nearCeiling.point.y, 1e-5f)
        assertEquals(2f, nearCeiling.point.z, 1e-5f)
    }
}
