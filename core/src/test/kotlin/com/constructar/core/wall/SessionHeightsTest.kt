/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.math.Vec3
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A height above the floor is a difference **inside one coordinate system** - and ARCore starts a
 * new one every session (finding 217).
 *
 * The defect these tests pin down: round 101 subtracted the floor of the *current* session from
 * every node in the project, including nodes measured in a session whose world origin was
 * somewhere else entirely. Nothing crashed; the zone counts simply described a room that was never
 * measured. Every case below is one way that number can be wrong.
 */
class SessionHeightsTest {

    private fun node(y: Float, sessionId: String, x: Float = 0f, z: Float = 0f) = RouteNodeRecord(
        id = "n-$sessionId-$y",
        position = Vec3(x, y, z),
        normal = Vec3(0f, 0f, 1f),
        surface = SurfaceKind.WALL,
        hitSource = HitSource.PLANE_POLYGON,
        sessionId = sessionId,
    )

    @Test
    fun `a node is measured from the floor of its own session`() {
        val result = relativeNodes(
            nodes = listOf(node(y = -1.1f, sessionId = "today")),
            sessionFloorHeights = mapOf("today" to -1.4f),
        )

        assertEquals(NodeHeightStatus.MEASURED, result.nodes.single().status)
        assertEquals(0.3f, result.nodes.single().point?.y ?: -1f, 1e-4f)
        assertTrue(result.complete)
    }

    @Test
    fun `a node from a session with no measured floor is refused, not subtracted`() {
        // Yesterday's session: the floor was never seen, so the origin is all we have and it is
        // not the floor. Subtracting today's floor from it would print a mounting height that
        // describes nothing.
        val result = relativeNodes(
            nodes = listOf(node(y = 2.2f, sessionId = "yesterday")),
            sessionFloorHeights = mapOf("today" to -1.4f),
        )

        assertEquals(NodeHeightStatus.UNTRANSFORMED_FOREIGN_SESSION, result.nodes.single().status)
        assertNull(result.nodes.single().point)
        assertEquals(1, result.foreignSessionCount)
        assertEquals(0, result.points.size)
        assertFalse(result.complete)
    }

    @Test
    fun `two floors keep their own sessions apart`() {
        val result = relativeNodes(
            nodes = listOf(
                node(y = -1.1f, sessionId = "monday"),
                node(y = 0.2f, sessionId = "friday"),
            ),
            sessionFloorHeights = mapOf("monday" to -1.4f, "friday" to -0.1f),
        )

        // Both are 30 cm above *their* floor: the same mounting height, in two coordinate systems.
        assertEquals(0.3f, result.nodes[0].point?.y ?: -1f, 1e-4f)
        assertEquals(0.3f, result.nodes[1].point?.y ?: -1f, 1e-4f)
        assertEquals(2, result.measuredCount)
        assertTrue(result.complete)
    }

    @Test
    fun `a file written before session ids existed is resolved when there is exactly one floor`() {
        // No session id, one floor: the question "which session is this node from" has exactly one
        // answer, so the legacy node is resolved rather than refused.
        val result = relativeNodes(
            nodes = listOf(node(y = -1.1f, sessionId = "")),
            sessionFloorHeights = mapOf("today" to -1.4f),
        )

        assertEquals(NodeHeightStatus.MEASURED, result.nodes.single().status)
        assertEquals(0.3f, result.nodes.single().point?.y ?: -1f, 1e-4f)
    }

    @Test
    fun `a legacy floor height is used for nodes that have no session id`() {
        val result = relativeNodes(
            nodes = listOf(node(y = -1.1f, sessionId = "")),
            sessionFloorHeights = emptyMap(),
            legacyFloorHeightM = -1.4f,
        )

        assertEquals(NodeHeightStatus.MEASURED, result.nodes.single().status)
        assertEquals(0.3f, result.nodes.single().point?.y ?: -1f, 1e-4f)
    }

    @Test
    fun `a node without a session id and two floors is refused - the answer would be a coin toss`() {
        val result = relativeNodes(
            nodes = listOf(node(y = -1.1f, sessionId = "")),
            sessionFloorHeights = mapOf("monday" to -1.4f, "friday" to -0.1f),
        )

        assertEquals(NodeHeightStatus.UNTRANSFORMED_FOREIGN_SESSION, result.nodes.single().status)
        assertNull(result.nodes.single().point)
    }

    @Test
    fun `with no floor at all the nodes are reported as waiting, not as foreign`() {
        // The distinction matters on a screen: "walk the room once" is a step the operator can
        // take, "this node belongs to a session nobody measured" is not.
        val result = relativeNodes(
            nodes = listOf(node(y = 1.5f, sessionId = "today")),
            sessionFloorHeights = emptyMap(),
        )

        assertEquals(NodeHeightStatus.NO_FLOOR_TRACKED, result.nodes.single().status)
        assertEquals(1, result.noFloorCount)
        assertEquals(0, result.foreignSessionCount)
    }

    @Test
    fun `the calibration factor scales the point, once`() {
        val result = relativeNodes(
            nodes = listOf(node(y = -1.1f, sessionId = "today", x = 2f, z = 3f)),
            sessionFloorHeights = mapOf("today" to -1.4f),
            metresScale = 1.1f,
        )

        val point = result.nodes.single().point ?: error("a measured point")
        assertEquals(2.2f, point.x, 1e-4f)
        assertEquals(0.33f, point.y, 1e-4f) // 0.30 m x 1.1
        assertEquals(3.3f, point.z, 1e-4f)
    }

    @Test
    fun `a non-finite position is not a measurement`() {
        val broken = node(y = 1f, sessionId = "today").copy(position = Vec3(Float.NaN, 1f, 0f))
        val result = relativeNodes(listOf(broken), sessionFloorHeights = mapOf("today" to 0f))

        assertEquals(NodeHeightStatus.NOT_A_MEASUREMENT, result.nodes.single().status)
        assertEquals(1, result.invalidCount)
        assertEquals(0, result.points.size)
    }

    @Test
    fun `a non-finite floor is ignored, so its session counts as unfloored`() {
        val result = relativeNodes(
            nodes = listOf(node(y = 0f, sessionId = "today")),
            sessionFloorHeights = mapOf("today" to Float.NaN),
        )

        assertEquals(NodeHeightStatus.NO_FLOOR_TRACKED, result.nodes.single().status)
    }

    @Test
    fun `an empty node list is not complete`() {
        // A project with no runs has no evidence of anything, and `complete` must not read as
        // "every measured point was resolved" when there are no measured points.
        assertFalse(relativeNodes(emptyList()).complete)
    }
}
