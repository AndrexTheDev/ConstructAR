package com.constructar.arengine

import com.constructar.arengine.hit.SurfaceHit
import com.constructar.arengine.route.AnchorFactory
import com.constructar.arengine.route.AnchorHandle
import com.constructar.arengine.route.RouteSession
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

    private fun hit(x: Float, y: Float, z: Float, source: HitSource = HitSource.PLANE_POLYGON) = SurfaceHit(
        point = Vec3(x, y, z),
        normal = Vec3(0f, 0f, 1f),
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
        assertEquals("Length: 2.00 m", session.state.value.lengthLabel)
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
        assertNotNull(session.state.value.warning)
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
}
