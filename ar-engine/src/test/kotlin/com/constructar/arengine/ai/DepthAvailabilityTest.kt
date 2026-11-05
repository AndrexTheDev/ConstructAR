/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which depth the app is measuring with, and what it may say about it.
 *
 * The three paths are not equally good and the operator cannot see the difference: a phone with
 * ARCore's depth API measures millimetres against a depth field, one with neither that nor the
 * model measures only the planes it has detected. A screen that says nothing leaves the second
 * case looking like a bug when a tap lands on a chair's edge.
 */
class DepthAvailabilityTest {

    @Test
    fun `a running model is the depth in use, even when ARCore has one too`() {
        val status = DepthAvailability.of(
            modelBundled = true,
            arcoreDepthSupported = true,
            aiRunning = true,
        )

        assertEquals(DepthSourceState.AI_FUSED, status.state)
        assertTrue(status.modelBundled)
        assertTrue(status.hasDepthField)
    }

    @Test
    fun `without the model, ARCore's own depth is the answer`() {
        val status = DepthAvailability.of(
            modelBundled = false,
            arcoreDepthSupported = true,
            aiRunning = false,
        )

        assertEquals(DepthSourceState.ARCORE_ONLY, status.state)
        assertFalse(status.modelBundled)
        assertTrue(status.hasDepthField)
    }

    @Test
    fun `a bundled model that has not produced a frame has not helped`() {
        // The APK carries the file and the load failed or is still running: the app is measuring
        // with ARCore's depth, and claiming the AI path would be a claim about the build, not the
        // measurement.
        val status = DepthAvailability.of(
            modelBundled = true,
            arcoreDepthSupported = true,
            aiRunning = false,
        )

        assertEquals(DepthSourceState.ARCORE_ONLY, status.state)
        assertTrue(status.modelBundled)
    }

    @Test
    fun `a model that is still loading is not a verdict`() {
        // A bundled model on a phone without a depth sensor: for the first second of a cold start
        // the only *known* answer is "planes only", and it is not final. A warning that appears
        // and vanishes on every start is a warning nobody reads (finding 224).
        val loading = DepthAvailability.of(
            modelBundled = true,
            arcoreDepthSupported = false,
            aiRunning = false,
            aiSettled = false,
        )

        assertEquals(DepthSourceState.PLANES_ONLY, loading.state)
        assertFalse(loading.isFinal)

        val answered = DepthAvailability.of(
            modelBundled = true,
            arcoreDepthSupported = false,
            aiRunning = false,
            aiSettled = true,
        )

        assertTrue(answered.isFinal)
        assertFalse(answered.hasDepthField)
    }

    @Test
    fun `a build without the model has nothing to wait for`() {
        // No model in the APK: the absence *is* the answer, so the line may be shown at once even
        // though no load attempt will ever report back.
        val status = DepthAvailability.of(
            modelBundled = false,
            arcoreDepthSupported = false,
            aiRunning = false,
            aiSettled = false,
        )

        assertTrue(status.isFinal)
        assertEquals(DepthSourceState.PLANES_ONLY, status.state)
    }

    @Test
    fun `a running model is final whatever the loader said`() {
        val status = DepthAvailability.of(
            modelBundled = true,
            arcoreDepthSupported = false,
            aiRunning = true,
            aiSettled = false,
        )

        assertEquals(DepthSourceState.AI_FUSED, status.state)
        assertTrue(status.isFinal)
    }

    @Test
    fun `no depth at all is said out loud`() {
        val status = DepthAvailability.of(
            modelBundled = false,
            arcoreDepthSupported = false,
            aiRunning = false,
        )

        assertEquals(DepthSourceState.PLANES_ONLY, status.state)
        assertFalse(status.hasDepthField)
    }
}
