/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.slam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the app asks "are you back where you were?" (finding 245).
 *
 * The geometry is the detector's, so what is tested here is the conversation: it does not start
 * before the graph holds a room, it offers the closest candidate and only one, and a "no" is
 * remembered for the pair *and* for the minute - because a prompt that returns on the next frame
 * trains the operator to dismiss prompts.
 */
class ClosurePolicyTest {

    private fun candidate(from: Int, to: Int, distance: Float) =
        ClosureCandidate(from = from, to = to, distanceMetres = distance)

    private fun policy(
        minNodes: Int = ClosurePolicyConfig.DEFAULT_MIN_NODES,
        cooldown: Long = ClosurePolicyConfig.DEFAULT_COOLDOWN_MILLIS,
    ) = ClosurePolicy(ClosurePolicyConfig(minNodes = minNodes, cooldownMillis = cooldown))

    @Test
    fun `a corridor is not a loop - nothing is offered before the graph holds a room`() {
        val offered = policy().decide(listOf(candidate(0, 30, 0.2f)), nodeCount = 59, nowMillis = 1_000L)
        assertNull(offered)
    }

    @Test
    fun `with enough nodes the closest candidate is the one offered`() {
        val offered = policy().decide(
            listOf(candidate(0, 40, 0.3f), candidate(5, 70, 0.1f), candidate(2, 60, 0.2f)),
            nodeCount = 100,
            nowMillis = 1_000L,
        )
        assertNotNull(offered)
        assertEquals(5, offered?.from)
        assertEquals(70, offered?.to)
        assertEquals(0.1f, offered?.distanceMetres ?: -1f, 1e-4f)
    }

    @Test
    fun `no candidates means no question, not an empty offer`() {
        assertNull(policy().decide(emptyList(), nodeCount = 500, nowMillis = 1_000L))
    }

    @Test
    fun `a dismissal silences the pair and the next minute`() {
        val policy = policy(minNodes = 10, cooldown = 60_000L)
        val first = policy.decide(listOf(candidate(0, 40, 0.2f)), nodeCount = 100, nowMillis = 1_000L)
        assertNotNull(first)
        first?.let { policy.dismiss(it, nowMillis = 1_000L) }

        // The same question a second later: still a nag.
        assertNull(policy.decide(listOf(candidate(0, 40, 0.2f)), nodeCount = 100, nowMillis = 2_000L))

        // After the cooldown the pair itself is still refused, but a *different* pair may be asked.
        assertNull(policy.decide(listOf(candidate(0, 40, 0.2f)), nodeCount = 100, nowMillis = 70_000L))
        assertNotNull(policy.decide(listOf(candidate(0, 41, 0.2f)), nodeCount = 100, nowMillis = 70_000L))
    }

    @Test
    fun `the pair is the same pair from either end`() {
        val policy = policy(minNodes = 10, cooldown = 0L)
        val offer = policy.decide(listOf(candidate(7, 99, 0.2f)), nodeCount = 100, nowMillis = 0L)
        offer?.let { policy.dismiss(it, nowMillis = 0L) }
        assertNull(policy.decide(listOf(candidate(99, 7, 0.1f)), nodeCount = 100, nowMillis = 0L))
    }

    @Test
    fun `a candidate with a broken distance is not a place`() {
        assertNull(
            policy(minNodes = 10).decide(
                listOf(candidate(0, 40, Float.NaN), candidate(1, 41, Float.POSITIVE_INFINITY)),
                nodeCount = 100,
                nowMillis = 0L,
            ),
        )
    }

    @Test
    fun `a new session forgets what was refused`() {
        val policy = policy(minNodes = 10, cooldown = 60_000L)
        val offer = policy.decide(listOf(candidate(0, 40, 0.2f)), nodeCount = 100, nowMillis = 1_000L)
        offer?.let { policy.dismiss(it, nowMillis = 1_000L) }
        policy.clear()
        assertNotNull(policy.decide(listOf(candidate(0, 40, 0.2f)), nodeCount = 100, nowMillis = 2_000L))
    }

    @Test
    fun `a policy that could never offer anything is refused at construction`() {
        assertThrows { ClosurePolicyConfig(minNodes = 0) }
        assertThrows { ClosurePolicyConfig(cooldownMillis = -1L) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
