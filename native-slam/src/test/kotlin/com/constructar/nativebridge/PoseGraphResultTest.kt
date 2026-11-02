/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The optimiser's verdict, as it crosses the JNI bridge.
 *
 * `PoseGraphResult.from` reads one `FloatArray` laid out by `nativePoseGraphOptimize`
 * (`[initialCost, finalCost, rmsTranslation, rmsRotation, iterations, milliseconds, converged,
 * pose0(16), …]`). Two things can go wrong here and both are invisible until something acts on a
 * wrong answer: the flag can be *invented* on this side instead of carried, and the pose block can
 * start at the wrong offset. Until finding 112 the flag was `iterations > 0 && finalCost <=
 * initialCost`, which reported "not converged" for a graph that needed no work (zero iterations)
 * and "converged" for a run that merely ran out of budget.
 */
class PoseGraphResultTest {

    /** Six report floats, the converged flag, then [poses] 16-float matrices. */
    private fun payload(
        initialCost: Float,
        finalCost: Float,
        iterations: Int,
        converged: Boolean,
        vararg poses: FloatArray,
    ): FloatArray {
        val values = FloatArray(7 + poses.size * 16)
        values[0] = initialCost
        values[1] = finalCost
        values[2] = 0.25f
        values[3] = 0.05f
        values[4] = iterations.toFloat()
        values[5] = 12.5f
        values[6] = if (converged) 1f else 0f
        poses.forEachIndexed { index, pose -> pose.copyInto(values, 7 + index * 16) }
        return values
    }

    private fun identity(scale: Float): FloatArray = FloatArray(16) { i ->
        if (i % 5 == 0) scale else 0f
    }

    @Test
    fun `a run that used its budget without converging is not converged`() {
        // The cost fell from 10 to 2 - which the old derived flag read as "converged" - but the
        // optimiser stopped because it ran out of iterations, and only the optimiser can say so.
        val result = PoseGraphResult.from(payload(10f, 2f, 20, converged = false, identity(1f)))
        assertTrue("payload must parse", result != null)
        assertFalse("finalCost < initialCost is not convergence", result!!.converged)
        assertEquals("iterations", 20, result.iterations)
    }

    @Test
    fun `a graph that needed no work is converged with zero iterations`() {
        // Fewer than two nodes, or nothing left to optimise: the solver returns immediately and
        // says converged. The old derived flag required iterations > 0 and called this a failure.
        val result = PoseGraphResult.from(payload(0f, 0f, 0, converged = true))
        assertTrue("payload must parse", result != null)
        assertTrue("a no-op optimisation is converged", result!!.converged)
        assertEquals("no poses to report", 0, result.poses.size)
    }

    @Test
    fun `poses start after the converged flag`() {
        val first = identity(1f)
        val second = identity(2f)
        val result = PoseGraphResult.from(payload(5f, 1f, 4, converged = true, first, second))
        assertTrue("payload must parse", result != null)
        assertEquals("two poses", 2, result!!.poses.size)
        assertArrayEquals("the first pose keeps its own floats", first, result.poses[0], 0f)
        assertArrayEquals(
            "the second pose is not shifted by the flag",
            second,
            result.poses[1],
            0f,
        )
    }

    @Test
    fun `a payload without the flag is refused instead of guessed`() {
        // Six report floats and nothing else is what the bridge produced before the flag existed.
        // Reading it as a report would silently answer "not converged" to every graph.
        val old = FloatArray(6) { 1f }
        assertNull("a six-float payload carries no verdict", PoseGraphResult.from(old))
        assertNull("an empty payload carries nothing", PoseGraphResult.from(FloatArray(0)))
    }
}
