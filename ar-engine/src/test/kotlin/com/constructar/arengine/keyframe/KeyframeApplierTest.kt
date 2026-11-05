/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.keyframe

import com.constructar.core.math.Mat4
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.constructar.core.slam.KeyframeSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The translation between the queue's numbering and the engine's (finding 243).
 *
 * Two things can go wrong here and both are silent: an edge that references the wrong node (the
 * optimiser then spreads a measurement between two places that were never adjacent over the whole
 * trajectory) and an edge whose node was dropped (the optimistic version - "just use the index we
 * asked for" - produces exactly that, because the queue and the engine number independently).
 */
class KeyframeApplierTest {

    private class RecordingSink(
        private val firstIndex: Int = 0,
        /** Nodes beyond this many are refused - the drain happens later, so a flag would lie. */
        private val refuseAfter: Int = Int.MAX_VALUE,
    ) : PoseGraphSink {
        val nodes = ArrayList<Pair<FloatArray, Boolean>>()
        val edges = ArrayList<Triple<Int, Int, FloatArray>>()

        override fun addNode(poseColumnMajor: FloatArray, fixed: Boolean): Int {
            if (nodes.size >= refuseAfter) return PoseGraphSink.NO_NODE
            val index = firstIndex + nodes.size
            nodes += poseColumnMajor to fixed
            return index
        }

        override fun addEdge(from: Int, to: Int, measurementColumnMajor: FloatArray) {
            edges += Triple(from, to, measurementColumnMajor)
        }
    }

    private val identity = Mat4.IDENTITY.elements.copyOf()

    @Test
    fun `logical indices reach the graph as native ones`() {
        // The engine starts numbering at 100 here on purpose: the queue starts at 0, and an
        // applier that passed the queue's index through would address nodes that do not exist.
        val sink = RecordingSink(firstIndex = 100)
        val queue = KeyframeQueue()
        val applier = KeyframeApplier(sink)

        val first = queue.addNode(identity, fixed = true)
        val second = queue.addNode(identity, fixed = false)
        queue.addEdge(first, second, identity)

        while (true) {
            val command = queue.poll() ?: break
            applier.apply(command)
        }

        assertEquals(2, sink.nodes.size)
        assertEquals(1, sink.edges.size)
        assertEquals(100, sink.edges[0].first)
        assertEquals(101, sink.edges[0].second)
        assertEquals(2, applier.appliedNodes)
        assertEquals(1, applier.appliedEdges)
        assertEquals(0, applier.droppedEdges)
    }

    @Test
    fun `an edge whose node was refused is dropped, not guessed`() {
        // The sink takes one node and refuses the second one - refused *at drain time*, which is
        // why the fake refuses by count and not by a flag set before the drain.
        val sink = RecordingSink(refuseAfter = 1)
        val queue = KeyframeQueue()
        val applier = KeyframeApplier(sink)

        val first = queue.addNode(identity, fixed = true)
        val lost = queue.addNode(identity, fixed = false)
        queue.addEdge(first, lost, identity)

        while (true) {
            val command = queue.poll() ?: break
            applier.apply(command)
        }

        assertEquals(1, applier.appliedNodes)
        assertEquals(1, applier.refusedNodes)
        assertTrue("no edge may reference a node the graph never got", sink.edges.isEmpty())
        assertEquals(1, applier.droppedEdges)
    }

    @Test
    fun `an edge that arrives before its node is dropped`() {
        val sink = RecordingSink()
        val applier = KeyframeApplier(sink)
        applier.apply(KeyframeCommand.AddEdge(from = 0, to = 1, measurementColumnMajor = identity))

        assertTrue(sink.edges.isEmpty())
        assertEquals(1, applier.droppedEdges)
        assertEquals(0, applier.appliedEdges)
    }

    @Test
    fun `a full queue refuses the node and counts the drop`() {
        val queue = KeyframeQueue(capacity = 1)
        val first = queue.addNode(identity, fixed = true)
        val second = queue.addNode(identity, fixed = false)

        assertEquals(0, first)
        assertEquals("the queue is bounded so that a wedged worker cannot grow the heap", PoseGraphSink.NO_NODE, second)
        assertEquals(1, queue.dropped)
        assertEquals(1, queue.produced)
    }

    @Test
    fun `a cleared applier forgets the indices of a reset graph`() {
        val sink = RecordingSink()
        val queue = KeyframeQueue()
        val applier = KeyframeApplier(sink)

        val first = queue.addNode(identity, fixed = true)
        val second = queue.addNode(identity, fixed = false)
        while (true) {
            val command = queue.poll() ?: break
            applier.apply(command)
        }
        applier.clear()
        queue.addEdge(first, second, identity)
        while (true) {
            val command = queue.poll() ?: break
            applier.apply(command)
        }

        assertEquals(
            "the edge cannot be translated after the translation table was cleared",
            0,
            sink.edges.size,
        )
        assertEquals("so it is dropped and counted, not written against a stale index", 1, applier.droppedEdges)
    }

    @Test
    fun `the queue keeps the order it was given, node before its edge`() {
        val queue = KeyframeQueue()
        queue.addNode(identity, fixed = true)
        queue.addEdge(0, 0, identity)

        assertTrue(queue.poll() is KeyframeCommand.AddNode)
        assertTrue(queue.poll() is KeyframeCommand.AddEdge)
        assertNull(queue.poll())
    }

    @Test
    fun `a closed queue still refuses and counts`() {
        val queue = KeyframeQueue()
        queue.close()
        assertEquals(PoseGraphSink.NO_NODE, queue.addNode(identity, fixed = true))
        assertEquals(1, queue.dropped)
        assertNull(queue.poll())
    }

    @Test
    fun `the measurement travels through the queue unchanged`() {
        val sink = RecordingSink()
        val queue = KeyframeQueue()
        val applier = KeyframeApplier(sink)

        queue.addNode(identity, fixed = true)
        queue.addNode(identity, fixed = false)
        queue.addEdge(0, 1, Mat4.translation(Vec3(0.6f, 0f, 0f)).elements.copyOf())
        while (true) {
            val command = queue.poll() ?: break
            applier.apply(command)
        }

        val relative = Mat4.fromColumnMajor(sink.edges[0].third)
        assertEquals(0.6f, relative.translation.x, 1e-4f)
        assertEquals(0f, relative.translation.y, 1e-4f)
        assertEquals(0f, relative.translation.z, 1e-4f)
        assertEquals(0f, KeyframeSelector.rotationDegrees(relative.rotationQuaternion(), Quat.IDENTITY), 1e-4f)
    }
}
