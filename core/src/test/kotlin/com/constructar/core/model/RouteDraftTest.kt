/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.Vec3
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run a killed process left behind.
 *
 * Two things have to stay true, and they pull in opposite directions: the measurement survives
 * **exactly** (length, spec, closed loop), and the geometry is never allowed to pretend it is
 * still where it was measured (an ARCore world origin dies with the session).
 */
class RouteDraftTest {

    private val spec = ConduitSpec.cable(16f, "Cable Ø16")

    private fun node(id: String, x: Float) = RouteNodeRecord(
        id = id,
        position = Vec3(x, 0f, 0f),
        normal = Vec3(0f, 0f, 1f),
        surface = SurfaceKind.WALL,
        hitSource = HitSource.PLANE_POLYGON,
    )

    private fun draft(nodes: List<RouteNodeRecord>, closed: Boolean = false) = RouteDraft(
        projectId = "p1",
        spec = spec,
        nodes = nodes,
        closedLoop = closed,
        savedAtEpochMs = 1_793_491_200_000L,
    )

    @Test
    fun `a single tap is not a measurement and is never offered`() {
        assertFalse(draft(listOf(node("n1", 0f))).isRecoverable)
        assertNull(draft(listOf(node("n1", 0f))).toRoute("r1", "CABLE 16 #1", 0L))
    }

    @Test
    fun `the length is the one the operator saw`() {
        val value = draft(listOf(node("n1", 0f), node("n2", 3f), node("n3", 6f)))

        assertEquals(6f, value.lengthMeters, 0.001f)
        assertTrue(value.isRecoverable)
    }

    @Test
    fun `a closed draft includes the way back`() {
        // Three points in a line at 0, 3 and 6 m: 6 m open, and closing it adds the 6 m back.
        val nodes = listOf(
            node("n1", 0f),
            node("n2", 3f),
            node("n3", 6f),
        )

        assertEquals(12f, draft(nodes, closed = true).lengthMeters, 0.001f)
        assertEquals(6f, draft(nodes, closed = false).lengthMeters, 0.001f)
    }

    @Test
    fun `a recovered route is marked, and keeps spec and closure`() {
        val value = draft(listOf(node("n1", 0f), node("n2", 4f)), closed = true)

        val route = value.toRoute("route-1", "CABLE 16 #2", 42L)

        assertNotNull(route)
        requireNotNull(route)
        assertTrue(route.recovered)
        assertEquals("CABLE 16 #2", route.label)
        assertEquals(spec.sizeLabel, route.spec.sizeLabel)
        assertTrue(route.closedLoop)
        assertEquals(42L, route.createdAtEpochMs)
        assertEquals(4f, route.polylineLengthMeters, 0.001f)
        // The anchors of the dead session are not carried into the recovered run.
        assertTrue(route.nodes.all { it.anchorId == null })
    }

    @Test
    fun `the draft survives the round trip a killed process makes`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val value = draft(listOf(node("n1", 0f), node("n2", 2.5f), node("n3", 5f)), closed = true)

        val restored = json.decodeFromString(RouteDraft.serializer(), json.encodeToString(RouteDraft.serializer(), value))

        assertEquals(value, restored)
        assertEquals(value.lengthMeters, restored.lengthMeters, 0.001f)
        assertEquals(value.savedAtEpochMs, restored.savedAtEpochMs)
    }
}
