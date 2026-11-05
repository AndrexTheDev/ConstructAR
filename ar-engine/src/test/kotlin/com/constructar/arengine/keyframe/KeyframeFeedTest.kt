/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.keyframe

import com.constructar.core.math.Mat4
import com.constructar.core.math.Pose
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.constructar.core.slam.KeyframeReason
import com.constructar.core.slam.KeyframeSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * The path from "this frame is tracked" to "the graph has a node and an edge" (finding 243).
 *
 * The policy in `:core` is tested on its own; what is tested here is everything the graph needs
 * that the policy does not produce: a column-major matrix, a fixed first node, and an edge whose
 * measurement is the same convention the optimiser reads. A mistake in any of the three is silent
 * on a device - the graph builds, the optimiser converges, and the room is wrong.
 */
class KeyframeFeedTest {

    private class RecordingSink(private val returnIndex: (Int) -> Int = { it }) : PoseGraphSink {
        val nodes = ArrayList<Pair<FloatArray, Boolean>>()
        val edges = ArrayList<Triple<Int, Int, FloatArray>>()

        override fun addNode(poseColumnMajor: FloatArray, fixed: Boolean): Int {
            val index = returnIndex(nodes.size)
            if (index != PoseGraphSink.NO_NODE) nodes += poseColumnMajor to fixed
            return index
        }

        override fun addEdge(from: Int, to: Int, measurementColumnMajor: FloatArray) {
            edges += Triple(from, to, measurementColumnMajor)
        }
    }

    private fun pose(at: Vec3 = Vec3.ZERO, yawDegrees: Float = 0f) = Pose(
        rotation = Quat.fromAxisAngle(Vec3.Y_AXIS, (yawDegrees * PI / 180.0).toFloat()),
        translation = at,
    )

    private fun accepted(
        feed: KeyframeFeed,
        at: Vec3 = Vec3.ZERO,
        yawDegrees: Float = 0f,
        atMillis: Long = 0L,
        trackingValid: Boolean = true,
        featurePoints: Int = 200,
    ) = feed.onFrame(pose(at, yawDegrees), atMillis, trackingValid, featurePoints)

    @Test
    fun `the first keyframe becomes a fixed node with no edge`() {
        val sink = RecordingSink()
        val feed = KeyframeFeed(sink)
        val event = accepted(feed)

        assertTrue(event.recorded)
        assertFalse("a first node has nothing to connect to", event.edgeAdded)
        assertEquals(1, sink.nodes.size)
        assertTrue("the first node anchors the graph's frame", sink.nodes[0].second)
        assertTrue(sink.edges.isEmpty())
        assertEquals(1, feed.report.nodes)
        assertEquals(0, feed.report.edges)
    }

    @Test
    fun `a half-metre walk adds a node and an edge measured from the previous one`() {
        val sink = RecordingSink()
        val feed = KeyframeFeed(sink)
        accepted(feed)
        val event = accepted(feed, at = Vec3(0.6f, 0f, 0f), atMillis = 500L)

        assertEquals(2, sink.nodes.size)
        assertFalse("only the first node is fixed", sink.nodes[1].second)
        assertTrue(event.edgeAdded)
        assertEquals(1, sink.edges.size)

        val (from, to, measurement) = sink.edges[0]
        assertEquals(0, from)
        assertEquals(1, to)
        val relative = Mat4.fromColumnMajor(measurement)
        assertEquals(0.6f, relative.translation.x, 1e-3f)
        assertEquals(0f, relative.translation.y, 1e-3f)
        assertEquals(0f, relative.translation.z, 1e-3f)
        assertEquals(0f, KeyframeSelector.rotationDegrees(relative.rotationQuaternion(), Quat.IDENTITY), 0.05f)
    }

    @Test
    fun `a turn in place becomes a node whose edge measures the rotation`() {
        val sink = RecordingSink()
        val feed = KeyframeFeed(sink)
        accepted(feed)
        accepted(feed, yawDegrees = 20f, atMillis = 500L)

        assertEquals(1, sink.edges.size)
        val relative = Mat4.fromColumnMajor(sink.edges[0].third)
        assertEquals(0f, relative.translation.length, 1e-3f)
        assertEquals(20f, KeyframeSelector.rotationDegrees(relative.rotationQuaternion(), Quat.IDENTITY), 0.05f)
    }

    @Test
    fun `a rejected frame writes nothing and says why`() {
        val sink = RecordingSink()
        val feed = KeyframeFeed(sink)
        accepted(feed)
        val event = accepted(feed, at = Vec3(0.2f, 0f, 0f), atMillis = 500L)

        assertFalse(event.recorded)
        assertEquals(KeyframeReason.TOO_CLOSE, event.decision.reason)
        assertEquals(1, sink.nodes.size)
        assertTrue(sink.edges.isEmpty())
        assertEquals(1, feed.report.rejected)
        assertEquals(1, feed.report.nodes)
        assertEquals(KeyframeReason.TOO_CLOSE, feed.report.lastReason)
    }

    @Test
    fun `a sink that refuses is counted, and the next keyframe anchors again`() {
        // The engine was closed, or the queue was full: the policy said yes and nothing was
        // written. The next accepted frame must then be fixed, not connected to a node that does
        // not exist.
        var accept = false
        val sink = RecordingSink { index ->
            if (accept) index else PoseGraphSink.NO_NODE
        }
        val feed = KeyframeFeed(sink)
        val refused = accepted(feed)
        assertFalse(refused.recorded)
        assertEquals(1, feed.report.refused)
        assertEquals(0, feed.report.nodes)

        accept = true
        val firstReal = accepted(feed, at = Vec3(1f, 0f, 0f), atMillis = 1_000L)
        assertTrue(firstReal.recorded)
        assertFalse("a node with no predecessor cannot carry an edge", firstReal.edgeAdded)
        assertTrue(sink.nodes[0].second)
    }

    @Test
    fun `a reset starts a new origin - fixed node, no edge across the gap`() {
        val sink = RecordingSink()
        val feed = KeyframeFeed(sink)
        accepted(feed)
        accepted(feed, at = Vec3(0.6f, 0f, 0f), atMillis = 500L)
        feed.reset()
        assertEquals(0, feed.report.nodes)

        val event = accepted(feed, at = Vec3(5f, 0f, 0f), atMillis = 9_000L)
        assertTrue(event.recorded)
        assertFalse(event.edgeAdded)
        assertEquals(3, sink.nodes.size)
        assertTrue("the first node of the new origin is fixed", sink.nodes[2].second)
        assertEquals(1, sink.edges.size)
    }

    @Test
    fun `the node pose is column-major, so the translation sits at 12, 13, 14`() {
        val sink = RecordingSink()
        val feed = KeyframeFeed(sink)
        accepted(feed, at = Vec3(1.5f, 2f, -3f))

        val matrix = sink.nodes[0].first
        assertEquals(16, matrix.size)
        assertEquals(1.5f, matrix[12], 1e-4f)
        assertEquals(2f, matrix[13], 1e-4f)
        assertEquals(-3f, matrix[14], 1e-4f)
        assertEquals(1f, matrix[15], 1e-4f)
        assertEquals(1f, matrix[0], 1e-4f)
        assertEquals(1f, matrix[5], 1e-4f)
        assertEquals(1f, matrix[10], 1e-4f)
    }

    @Test
    fun `an untracked frame is never written, however far it moved`() {
        val sink = RecordingSink()
        val feed = KeyframeFeed(sink)
        accepted(feed)
        val event = accepted(feed, at = Vec3(3f, 0f, 0f), atMillis = 500L, trackingValid = false)

        assertFalse(event.recorded)
        assertEquals(KeyframeReason.TRACKING_INVALID, event.decision.reason)
        assertEquals(1, sink.nodes.size)
    }
}
