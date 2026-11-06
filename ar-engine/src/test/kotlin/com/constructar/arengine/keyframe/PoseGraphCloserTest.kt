/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.keyframe

import com.constructar.core.math.Mat4
import com.constructar.core.math.Vec3
import com.constructar.core.slam.ClosureCandidate
import com.constructar.core.slam.ClosurePolicyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation that decides whether a room gets folded (finding 245).
 *
 * Two failures matter here and only one of them is visible: a closure that is applied without a
 * confirmation, and a closure that is *not* applied but looks as if it was. So the fake source
 * records what it was asked to do, and every test checks the graph was left alone until the
 * operator said yes.
 */
class PoseGraphCloserTest {

    private class FakeHistory(private val poses: Map<Int, FloatArray> = emptyMap()) : NodeHistory {
        override fun originalPose(index: Int): FloatArray? = poses[index]
    }

    private class FakeSource(
        private var found: List<ClosureCandidate> = emptyList(),
        private val result: ClosureOptimisation? = ClosureOptimisation(4.5f, 0.2f, converged = true),
    ) : PoseGraphSource {
        val closures = ArrayList<Pair<Int, Int>>()
        var optimiseCalls = 0
            private set

        override fun candidates(): List<ClosureCandidate> = found

        override fun addLoopClosure(from: Int, to: Int) {
            closures += from to to
        }

        override fun optimise(): ClosureOptimisation? {
            optimiseCalls += 1
            return result
        }

        fun offer(candidate: ClosureCandidate) {
            found = listOf(candidate)
        }
    }

    private fun closer(
        source: FakeSource,
        minNodes: Int = 10,
        history: NodeHistory = FakeHistory(),
    ) = PoseGraphCloser(
        source = source,
        history = history,
        policy = com.constructar.core.slam.ClosurePolicy(
            ClosurePolicyConfig(minNodes = minNodes, cooldownMillis = 60_000L),
        ),
    )

    private fun poseAt(x: Float): FloatArray =
        Mat4.fromRotationTranslation(com.constructar.core.math.Quat.IDENTITY, Vec3(x, 0f, 0f))
            .elements.copyOf()

    @Test
    fun `asking with an empty detector says so, and touches nothing`() {
        val source = FakeSource()
        val state = closer(source).look(nodeCount = 500, nowMillis = 0L)
        assertEquals(ClosureState.NothingFound, state)
        assertTrue(source.closures.isEmpty())
        assertEquals(0, source.optimiseCalls)
    }

    @Test
    fun `a candidate below the policy floor is not shown`() {
        val source = FakeSource()
        source.offer(ClosureCandidate(0, 40, 0.2f))
        assertEquals(ClosureState.NothingFound, closer(source, minNodes = 60).look(59, 0L))
        assertTrue(source.closures.isEmpty())
    }

    @Test
    fun `an offer carries the candidate and applies nothing`() {
        val source = FakeSource()
        source.offer(ClosureCandidate(3, 51, 0.18f))
        val state = closer(source).look(nodeCount = 200, nowMillis = 0L)

        assertTrue(state is ClosureState.Offered)
        val offer = (state as ClosureState.Offered).offer
        assertEquals(3, offer.from)
        assertEquals(51, offer.to)
        assertEquals(0.18f, offer.distanceMetres, 1e-4f)
        assertTrue("looking must not write to the graph", source.closures.isEmpty())
        assertEquals(0, source.optimiseCalls)
    }

    @Test
    fun `confirming applies the closure and reports what the optimiser did`() {
        val source = FakeSource()
        source.offer(ClosureCandidate(3, 51, 0.18f))
        val closer = closer(source)
        closer.look(nodeCount = 200, nowMillis = 0L)

        val state = closer.confirm(nowMillis = 1_000L)
        assertEquals(listOf(3 to 51), source.closures)
        assertEquals(1, source.optimiseCalls)
        val closed = state as ClosureState.Closed
        assertEquals(4.5f, closed.optimisation.initialCost, 1e-4f)
        assertEquals(0.2f, closed.optimisation.finalCost, 1e-4f)
        assertTrue(closed.optimisation.converged)
    }

    @Test
    fun `a confirmation with nothing on offer is a no-op, not a blind closure`() {
        val source = FakeSource()
        val closer = closer(source)
        assertEquals(ClosureState.Idle, closer.confirm(nowMillis = 0L))
        assertTrue(source.closures.isEmpty())
        assertEquals(0, source.optimiseCalls)
    }

    @Test
    fun `an applied closure without a graph is reported as failed, not as closed`() {
        val source = FakeSource(result = null)
        source.offer(ClosureCandidate(3, 51, 0.18f))
        val closer = closer(source)
        closer.look(nodeCount = 200, nowMillis = 0L)
        assertEquals(ClosureState.Failed, closer.confirm(nowMillis = 1_000L))
    }

    @Test
    fun `a refusal is remembered, so the same pair is not offered again`() {
        val source = FakeSource()
        source.offer(ClosureCandidate(3, 51, 0.18f))
        val closer = closer(source)
        closer.look(nodeCount = 200, nowMillis = 0L)
        assertEquals(ClosureState.NothingFound, closer.dismiss(nowMillis = 1_000L))
        assertEquals(ClosureState.NothingFound, closer.look(nodeCount = 200, nowMillis = 61_000L))
        assertTrue(source.closures.isEmpty())
    }

    @Test
    fun `a confirmed pair is not offered again either`() {
        val source = FakeSource()
        source.offer(ClosureCandidate(3, 51, 0.18f))
        val closer = closer(source)
        closer.look(nodeCount = 200, nowMillis = 0L)
        closer.confirm(nowMillis = 1_000L)
        // The two nodes now coincide, so the detector keeps finding them: without the bookkeeping
        // the operator would be asked the same question for the rest of the session.
        assertEquals(ClosureState.NothingFound, closer.look(nodeCount = 200, nowMillis = 120_000L))
    }

    @Test
    fun `a reset forgets the conversation`() {
        val source = FakeSource()
        source.offer(ClosureCandidate(3, 51, 0.18f))
        val closer = closer(source)
        closer.look(nodeCount = 200, nowMillis = 0L)
        closer.dismiss(nowMillis = 1_000L)
        closer.reset()

        assertEquals(ClosureState.Idle, closer.state)
        assertFalse(closer.look(nodeCount = 200, nowMillis = 2_000L) is ClosureState.NothingFound)
    }

    @Test
    fun `a confirmation reports how far the optimiser moved the graph`() {
        val source = FakeSource(
            found = listOf(ClosureCandidate(0, 1, 0.2f)),
            result = ClosureOptimisation(
                initialCost = 9f,
                finalCost = 0.1f,
                converged = true,
                poses = listOf(poseAt(0f), poseAt(0.25f)),
            ),
        )
        val closer = closer(source, history = FakeHistory(mapOf(0 to poseAt(0f), 1 to poseAt(0f))))
        closer.look(nodeCount = 200, nowMillis = 0L)

        val closed = closer.confirm(nowMillis = 1_000L) as ClosureState.Closed
        val correction = requireNotNull(closed.correction) { "a converged run has a report" }
        assertEquals(2, correction.nodes)
        assertEquals(0.25f, correction.maxShiftMetres, 1e-4f)
        assertTrue("the report has to say that something moved", correction.moved)
    }

    @Test
    fun `a node the app never fed in is not part of the report`() {
        val source = FakeSource(
            found = listOf(ClosureCandidate(0, 1, 0.2f)),
            result = ClosureOptimisation(
                initialCost = 9f,
                finalCost = 0.1f,
                converged = true,
                poses = listOf(poseAt(0f), poseAt(5f)),
            ),
        )
        // Node 1 came from somewhere else: no original, so no shift to report.
        val closer = closer(source, history = FakeHistory(mapOf(0 to poseAt(0f))))
        closer.look(nodeCount = 200, nowMillis = 0L)

        val closed = closer.confirm(nowMillis = 1_000L) as ClosureState.Closed
        val correction = requireNotNull(closed.correction) { "a converged run has a report" }
        assertEquals(1, correction.nodes)
        assertEquals(0f, correction.maxShiftMetres, 1e-6f)
    }

    @Test
    fun `a run that did not converge reports no correction at all`() {
        val source = FakeSource(
            found = listOf(ClosureCandidate(0, 1, 0.2f)),
            result = ClosureOptimisation(
                initialCost = 9f,
                finalCost = 8.7f,
                converged = false,
                poses = listOf(poseAt(0f), poseAt(2f)),
            ),
        )
        val closer = closer(source, history = FakeHistory(mapOf(0 to poseAt(0f), 1 to poseAt(0f))))
        closer.look(nodeCount = 200, nowMillis = 0L)

        val closed = closer.confirm(nowMillis = 1_000L) as ClosureState.Closed
        // The graph was touched - the edge is in it - but the poses are a snapshot halfway down
        // the cost curve, so there is nothing to compare the operator's measurements against.
        assertFalse("a run that stopped early has no result", closed.optimisation.converged)
        assertNull("a half-descent is not a correction", closed.correction)
    }
}
