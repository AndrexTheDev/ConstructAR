/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.hit.SurfaceHit
import com.constructar.arengine.route.AnchorFactory
import com.constructar.arengine.route.AnchorHandle
import com.constructar.arengine.route.RouteSession
import com.constructar.arengine.route.RouteWarning
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.constructar.core.model.HitSource
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing behaviour: Euclidean measuring, anchor binding, and the guard rails that keep a
 * gloved double-tap from turning into a zero-length segment.
 */
class RouteSessionTest {

    private class FakeAnchor(
        var currentPosition: Vec3,
        normal: Vec3,
        private val idPrefix: String,
        private val index: Int,
    ) : AnchorHandle {
        override val id: String = "$idPrefix-$index"
        override val position: Vec3 get() = currentPosition
        override val rotation: Quat = RouteSession.quaternionForNormal(normal)
        override val tracking: Boolean = true
        var detached: Boolean = false

        override fun detach() {
            detached = true
        }
    }

    private class FakeAnchorFactory : AnchorFactory {
        val anchors = mutableListOf<FakeAnchor>()

        override fun create(position: Vec3, normal: Vec3): AnchorHandle {
            val anchor = FakeAnchor(position, normal, "anchor", anchors.size)
            anchors += anchor
            return anchor
        }
    }

    private fun hit(
        x: Float,
        y: Float,
        z: Float,
        source: HitSource = HitSource.PLANE_POLYGON,
        normal: Vec3 = Vec3(0f, 0f, 1f),
    ) = SurfaceHit(
        point = Vec3(x, y, z),
        normal = normal,
        source = source,
        confidence = source.reliability,
        distanceMeters = z.let { if (it < 0f) -it else it },
        surfaceKind = SurfaceKind.WALL,
    )

    @Test
    fun `length is the strict euclidean sum of the node chain`() {
        val factory = FakeAnchorFactory()
        val session = RouteSession(factory)

        session.addNode(hit(0f, 0f, 0f))
        session.addNode(hit(1f, 0f, 0f))
        assertEquals(1f, session.state.value.lengthMeters, 1e-5f)

        session.addNode(hit(1f, 0f, 1f))
        assertEquals(2f, session.state.value.lengthMeters, 1e-5f)
        assertEquals("2.00 m", com.constructar.core.units.LengthFormatter.format(session.state.value.lengthMeters))
    }

    @Test
    fun `every node is bound to an anchor`() {
        val factory = FakeAnchorFactory()
        val session = RouteSession(factory)
        session.addNode(hit(0f, 1f, -2f))
        session.addNode(hit(1f, 1f, -2f))

        assertEquals(2, factory.anchors.size)
        assertEquals(2, session.state.value.anchorCount)
        assertNotNull(session.state.value.nodes[0].anchorId)
        assertNotNull(session.state.value.nodes[1].anchorId)
    }

    @Test
    fun `a double tap is rejected without creating a node`() {
        val factory = FakeAnchorFactory()
        val session = RouteSession(factory)
        session.addNode(hit(0f, 1f, -2f))
        val accepted = session.addNode(hit(0.005f, 1f, -2f))

        assertFalse(accepted)
        assertEquals(1, session.state.value.nodes.size)
        assertEquals(1, factory.anchors.size)
        assertEquals(RouteWarning.TOO_CLOSE, session.state.value.warning)
    }

    @Test
    fun `anchor refinement moves the measurement`() {
        val factory = FakeAnchorFactory()
        val session = RouteSession(factory)
        session.addNode(hit(0f, 0f, 0f))
        session.addNode(hit(1f, 0f, 0f))
        assertEquals(1f, session.state.value.lengthMeters, 1e-5f)

        // ARCore refines the second anchor by a metre.
        factory.anchors[1].currentPosition = Vec3(2f, 0f, 0f)
        session.onFrame()
        assertEquals(2f, session.state.value.lengthMeters, 1e-4f)
    }

    @Test
    fun `sub-millimetre anchor jitter is ignored`() {
        val factory = FakeAnchorFactory()
        val session = RouteSession(factory)
        session.addNode(hit(0f, 0f, 0f))
        session.addNode(hit(1f, 0f, 0f))

        factory.anchors[1].currentPosition = Vec3(1.0001f, 0f, 0f)
        session.onFrame()
        // Below the 5 mm threshold the node must not move (no shimmering mesh).
        assertEquals(1f, session.state.value.lengthMeters, 1e-5f)
    }

    @Test
    fun `undo detaches the anchor and shortens the run`() {
        val factory = FakeAnchorFactory()
        val session = RouteSession(factory)
        session.addNode(hit(0f, 0f, 0f))
        session.addNode(hit(1f, 0f, 0f))
        session.addNode(hit(2f, 0f, 0f))

        val removed = session.undo()
        assertNotNull(removed)
        assertTrue(factory.anchors.last().detached)
        assertEquals(2, session.state.value.nodes.size)
        assertEquals(1f, session.state.value.lengthMeters, 1e-5f)
    }

    @Test
    fun `a run needs two nodes before it can be finished`() {
        val session = RouteSession(FakeAnchorFactory())
        assertNull(session.finish("empty"))
        session.addNode(hit(0f, 0f, 0f))
        assertNull(session.finish("single"))
        session.addNode(hit(1.5f, 0f, 0f))
        val route = session.finish("Test run")
        assertNotNull(route)
        assertEquals(1.5f, route!!.polylineLengthMeters, 1e-5f)
        assertEquals("Test run", route.label)
    }

    @Test
    fun `closed loops add the closing segment`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f))
        session.addNode(hit(1f, 0f, 0f))
        session.addNode(hit(1f, 0f, 1f))
        session.closeLoop()
        // 1 + 1 + closing chord of √2
        assertEquals(2f + kotlin.math.sqrt(2f), session.state.value.lengthMeters, 1e-4f)
    }

    @Test
    fun `normal to quaternion maps plus Y onto the surface normal`() {
        val normal = Vec3(0f, 0f, 1f)
        val rotation = RouteSession.quaternionForNormal(normal)
        val mapped = rotation.rotate(Vec3.Y_AXIS)
        assertEquals(normal.x, mapped.x, 1e-5f)
        assertEquals(normal.y, mapped.y, 1e-5f)
        assertEquals(normal.z, mapped.z, 1e-5f)
    }

    // ── Live segment under the reticle ───────────────────────────────────────────────────

    @Test
    fun `the live segment is the distance from the last node to the reticle`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f))
        session.setLivePoint(hit(1.5f, 0f, 0f))
        assertEquals(1.5f, session.state.value.liveSegmentMeters, 1e-5f)

        // Walking on: the number follows the reticle without creating a node.
        session.setLivePoint(hit(2.25f, 0f, 0f))
        assertEquals(2.25f, session.state.value.liveSegmentMeters, 1e-5f)
        assertEquals(1, session.state.value.nodeCount)
    }

    @Test
    fun `the live segment restarts from the node that was just committed`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f))
        session.setLivePoint(hit(1f, 0f, 0f))
        session.addNode(hit(1f, 0f, 0f))
        // The reticle still sits on the node that was just placed: no phantom segment.
        assertEquals(0f, session.state.value.liveSegmentMeters, 1e-5f)

        session.setLivePoint(hit(1f, 0f, 0.8f))
        assertEquals(0.8f, session.state.value.liveSegmentMeters, 1e-5f)
    }

    @Test
    fun `a dead run reports no live segment`() {
        val session = RouteSession(FakeAnchorFactory())
        session.setLivePoint(hit(1f, 0f, 0f))
        assertEquals(0f, session.state.value.liveSegmentMeters, 1e-5f)
        // Losing the reticle (tap into nothing) must not leave a frozen number on screen.
        session.addNode(hit(0f, 0f, 0f))
        session.setLivePoint(hit(1f, 0f, 0f))
        session.setLivePoint(null)
        assertEquals(0f, session.state.value.liveSegmentMeters, 1e-5f)
    }

    // ── Measurement quality ──────────────────────────────────────────────────────────────

    @Test
    fun `the weakest node sets the quality of the run`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f, source = HitSource.PLANE_POLYGON))
        session.addNode(hit(1f, 0f, 0f, source = HitSource.FEATURE_POINT))
        val state = session.state.value
        assertEquals(HitSource.FEATURE_POINT.reliability, state.worstNodeConfidence, 1e-5f)
        assertEquals(HitSource.FEATURE_POINT.reliability, state.lastNodeConfidence, 1e-5f)
        assertTrue(state.lastNodeConfidence > 0f)
    }

    // ── Surface jump ────────────────────────────────────────────────────────────────────

    @Test
    fun `a parallel surface half a metre behind the wall is flagged`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f))
        // Same normal, but 0.5 m further away: the ray slipped through an opening.
        session.addNode(hit(1f, 0f, 0.5f))
        assertEquals(RouteWarning.SURFACE_JUMP, session.state.value.warning)
        // Flagged, not rejected: the operator decides, and the segment is still measured.
        assertEquals(2, session.state.value.nodeCount)
    }

    @Test
    fun `a right-angled corner is a turn, not a jump`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f))
        // The perpendicular wall: normal 90 degrees off, one metre along the first wall.
        session.addNode(hit(1f, 0f, 0f, normal = Vec3(1f, 0f, 0f)))
        assertEquals(RouteWarning.NONE, session.state.value.warning)
    }

    @Test
    fun `a parallel surface within the tolerance is the same wall`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f))
        // 5 cm of plaster unevenness on the same wall must not nag the operator.
        session.addNode(hit(1f, 0f, 0.05f))
        assertEquals(RouteWarning.NONE, session.state.value.warning)
    }

    // ── Closing a ring ───────────────────────────────────────────────────────────────────

    @Test
    fun `a ring whose ends meet is closed without a warning`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f))
        session.addNode(hit(1f, 0f, 0f))
        session.addNode(hit(0.03f, 0f, 0.02f))
        assertTrue(session.closeLoop())
        assertTrue(session.state.value.closedLoop)
        // A three-node ring has a 60° corner, so the *bend* warning may well fire - what must
        // not happen is a complaint about the closure itself.
        assertEquals(
            "a ring that meets must not be reported as open",
            false,
            session.state.value.warning == RouteWarning.LOOP_TOO_WIDE,
        )
        assertTrue(session.loopGap() < RouteSession.LOOP_TOLERANCE_METERS)
    }

    @Test
    fun `a ring that does not meet is flagged instead of silently invented`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f))
        session.addNode(hit(1f, 0f, 0f))
        session.addNode(hit(1f, 0f, 1f))
        assertTrue(session.closeLoop())
        // The last node is 1.4 m from the first: the closing segment would be measured nowhere.
        assertEquals(RouteWarning.LOOP_TOO_WIDE, session.state.value.warning)
        assertEquals(1.414f, session.loopGap(), 1e-3f)
    }

    @Test
    fun `opening the ring again clears the warning`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f))
        session.addNode(hit(1f, 0f, 0f))
        session.addNode(hit(1f, 0f, 1f))
        session.closeLoop()
        assertEquals(RouteWarning.LOOP_TOO_WIDE, session.state.value.warning)

        assertTrue(session.closeLoop())
        assertFalse(session.state.value.closedLoop)
        // Opening again drops the closure complaint; a bend warning is the preview's business.
        assertFalse(session.state.value.warning == RouteWarning.LOOP_TOO_WIDE)
    }

    @Test
    fun `a run with two nodes cannot be closed`() {
        val session = RouteSession(FakeAnchorFactory())
        session.addNode(hit(0f, 0f, 0f))
        session.addNode(hit(1f, 0f, 0f))
        assertFalse(session.closeLoop())
        assertFalse(session.state.value.closedLoop)
    }
}
