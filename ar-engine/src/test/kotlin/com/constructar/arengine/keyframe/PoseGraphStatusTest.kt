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
import com.constructar.core.slam.CorrectionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the pose graph says about itself (finding 244).
 *
 * The counters existed from finding 243 and nothing read them, so "the graph is empty" was three
 * different problems wearing one screenshot: the policy never fired, the queue overflowed, or the
 * engine refused the node. These tests pin the collapse of four tallies into one value and one of
 * three standings - and, at the end, that the value a screen prints is the value the graph has.
 */
class PoseGraphStatusTest {

    private class RecordingSink(private val refuseAfter: Int = Int.MAX_VALUE) : PoseGraphSink {
        override fun addNode(poseColumnMajor: FloatArray, fixed: Boolean): Int =
            if (nodes++ >= refuseAfter) PoseGraphSink.NO_NODE else nodes - 1

        override fun addEdge(from: Int, to: Int, measurementColumnMajor: FloatArray) = Unit

        private var nodes = 0
    }

    private fun drain(queue: KeyframeQueue, applier: KeyframeApplier) {
        while (true) {
            val command = queue.poll() ?: return
            applier.apply(command)
        }
    }

    private fun frame(x: Float, millis: Long) = Pose(
        rotation = Quat.IDENTITY,
        translation = Vec3(x, 0f, 0f),
    )

    @Test
    fun `no engine at all is empty, not an error`() {
        val status = PoseGraphStatus.of(null)
        assertEquals(PoseGraphStanding.EMPTY, status.standing)
        assertEquals(0, status.nodes)
        assertEquals(0, status.lost)
    }

    @Test
    fun `nothing in the graph and nothing lost is the honest zero`() {
        val status = PoseGraphStatus.of(KeyframeApplier(RecordingSink()), droppedByQueue = 0)
        assertEquals(PoseGraphStanding.EMPTY, status.standing)
        assertEquals(0, status.lost)
    }

    @Test
    fun `nodes and edges that landed are recording`() {
        val queue = KeyframeQueue()
        val applier = KeyframeApplier(RecordingSink())
        val feed = KeyframeFeed(queue)
        feed.onFrame(frame(0f, 0L), 0L, trackingValid = true, featurePoints = 200)
        feed.onFrame(frame(0.6f, 0L), 500L, trackingValid = true, featurePoints = 200)
        drain(queue, applier)

        val status = PoseGraphStatus.of(applier, queue.dropped)
        assertEquals(PoseGraphStanding.RECORDING, status.standing)
        assertEquals(2, status.nodes)
        assertEquals(1, status.edges)
        assertEquals(0, status.lost)
    }

    @Test
    fun `a node the engine refused makes the graph lossy`() {
        val queue = KeyframeQueue()
        val applier = KeyframeApplier(RecordingSink(refuseAfter = 1))
        applier.apply(KeyframeCommand.AddNode(0, Mat4.IDENTITY.elements.copyOf(), true))
        applier.apply(KeyframeCommand.AddNode(1, Mat4.IDENTITY.elements.copyOf(), false))

        val status = PoseGraphStatus.of(applier, droppedByQueue = 0)
        assertEquals(PoseGraphStanding.LOSSY, status.standing)
        assertEquals(1, status.nodes)
        assertEquals(1, status.lost)
    }

    @Test
    fun `an edge that never landed makes the graph lossy`() {
        val applier = KeyframeApplier(RecordingSink())
        applier.apply(KeyframeCommand.AddEdge(from = 0, to = 1, measurementColumnMajor = Mat4.IDENTITY.elements))

        val status = PoseGraphStatus.of(applier, droppedByQueue = 0)
        assertEquals(PoseGraphStanding.LOSSY, status.standing)
        assertEquals("no node landed either, but the loss is the fact", 0, status.nodes)
        assertEquals(1, status.lost)
    }

    @Test
    fun `a queue overflow counts as lost even when everything else worked`() {
        val queue = KeyframeQueue(capacity = 1)
        val applier = KeyframeApplier(RecordingSink())
        val feed = KeyframeFeed(queue)
        feed.onFrame(frame(0f, 0L), 0L, trackingValid = true, featurePoints = 200)
        feed.onFrame(frame(0.6f, 0L), 500L, trackingValid = true, featurePoints = 200)
        drain(queue, applier)

        val status = PoseGraphStatus.of(applier, queue.dropped)
        assertEquals(PoseGraphStanding.LOSSY, status.standing)
        assertEquals(1, status.nodes)
        assertTrue("a keyframe that never crossed the queue is a hole in the graph", status.lost >= 1)
    }

    @Test
    fun `the number a screen prints is the number of nodes the graph holds`() {
        val queue = KeyframeQueue()
        val applier = KeyframeApplier(RecordingSink())
        val feed = KeyframeFeed(queue)
        feed.onFrame(frame(0f, 0L), 0L, trackingValid = true, featurePoints = 200)
        feed.onFrame(frame(0.2f, 0L), 400L, trackingValid = true, featurePoints = 200) // too small
        feed.onFrame(frame(0.6f, 0L), 900L, trackingValid = true, featurePoints = 200)
        drain(queue, applier)

        assertEquals(1, feed.report.rejected)
        val status = PoseGraphStatus.of(applier, queue.dropped)
        assertEquals(2, status.nodes)
        assertEquals(1, status.edges)
        assertEquals(0, status.lost)
        assertEquals(2, feed.report.nodes)
    }

    @Test
    fun `a confirmed closure is part of the status`() {
        // Finding 250: the numbers used to exist only while the dialog was open, so a status line
        // could not tell a folded room from an open loop.
        val status = PoseGraphStatus.of(
            applier = KeyframeApplier(RecordingSink()),
            closures = 2,
            lastCorrection = CorrectionReport(
                nodes = 380,
                movedNodes = 42,
                maxShiftMetres = 0.183f,
                meanShiftMetres = 0.02f,
            ),
        )

        assertEquals(2, status.closures)
        assertEquals(42, status.lastCorrection?.movedNodes)
        assertEquals(380, status.lastCorrection?.nodes)
    }

    @Test
    fun `a closure whose solver stopped early is counted but has nothing to print`() {
        // The count is every confirmation - the edge is in the graph - while the report only
        // exists for a converged run (finding 247).
        val status = PoseGraphStatus.of(applier = KeyframeApplier(RecordingSink()), closures = 1)

        assertEquals(1, status.closures)
        assertEquals(null, status.lastCorrection)
    }

    @Test
    fun `a session without a closure says nothing about one`() {
        val status = PoseGraphStatus.of(KeyframeApplier(RecordingSink()))

        assertEquals(0, status.closures)
        assertEquals(null, status.lastCorrection)
    }

    @Test
    fun `a closure whose solver gave up is not a closure that moved nothing`() {
        // Finding 252: `closures > 0` with no report meant two things - "the graph was already
        // consistent" and "the optimiser stopped" - and the graph line could not tell them apart.
        val status = PoseGraphStatus.of(
            applier = KeyframeApplier(RecordingSink()),
            closures = 2,
            lastCorrection = null,
            lastClosureConverged = false,
        )

        assertEquals(2, status.closures)
        assertEquals(null, status.lastCorrection)
        assertEquals("the last closure has no result", false, status.lastClosureConverged)
    }
}
