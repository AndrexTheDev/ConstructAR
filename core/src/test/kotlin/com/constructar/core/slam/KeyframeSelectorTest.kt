/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.slam

import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * The keyframe policy as arithmetic (Step 3a).
 *
 * A keyframe is the unit of cost in the pose graph and the unit of trust in it: too few and the
 * drift has nothing to close against, too many and the graph is made of frames a centimetre apart
 * whose "relative pose" is mostly tracker noise. These tests pin the three branches that decide
 * (translation, rotation, evidence) and the two gates that hold them back (jitter, rate), plus the
 * quaternion trap that makes a sign flip look like a full turn.
 *
 * Every scene is a real placement in metres and degrees, so a failure reads as a site problem
 * rather than a flag problem.
 */
class KeyframeSelectorTest {

    /** A tracked frame at [at], yawed by [yawDegrees], at [atMillis], with [features] points. */
    private fun frame(
        at: Vec3 = Vec3.ZERO,
        yawDegrees: Float = 0f,
        atMillis: Long = 0L,
        tracked: Boolean = true,
        features: Int = 200,
    ): KeyframeCandidate = KeyframeCandidate(
        position = at,
        rotation = Quat.fromAxisAngle(Vec3.Y_AXIS, (yawDegrees * PI / 180.0).toFloat()),
        timestampMillis = atMillis,
        trackingValid = tracked,
        featurePoints = features,
    )

    /** A selector already anchored on a keyframe at the origin, taken at t = 0. */
    private fun anchored(config: KeyframeConfig = KeyframeConfig()): KeyframeSelector =
        KeyframeSelector(config).also { assertTrue(it.offer(frame()).accepted) }

    @Test
    fun `the first usable frame anchors the graph and is never measured against nothing`() {
        val selector = KeyframeSelector()
        val decision = selector.offer(frame(atMillis = 1_000L))
        assertEquals(KeyframeReason.FIRST, decision.reason)
        assertTrue(decision.accepted)
        assertEquals(1, selector.acceptedCount)
        assertNotNull(selector.lastKeyframe)
        assertEquals(0f, decision.translationMetres, 0f)
        assertEquals(0f, decision.rotationDegrees, 0f)
    }

    @Test
    fun `the first frame still needs evidence - an untracked or empty frame cannot anchor`() {
        val selector = KeyframeSelector()
        assertEquals(KeyframeReason.TRACKING_INVALID, selector.offer(frame(tracked = false)).reason)
        assertEquals(KeyframeReason.TOO_FEW_FEATURES, selector.offer(frame(features = 12)).reason)
        assertNull(selector.lastKeyframe)
        assertEquals(0, selector.acceptedCount)
    }

    @Test
    fun `half a step is not a keyframe - small motion is rejected as too close`() {
        val selector = anchored()
        val decision = selector.offer(frame(at = Vec3(0.2f, 0f, 0f), yawDegrees = 3f, atMillis = 500L))
        assertEquals(KeyframeReason.TOO_CLOSE, decision.reason)
        assertFalse(decision.accepted)
        assertEquals(0.2f, decision.translationMetres, 1e-4f)
        assertEquals(3f, decision.rotationDegrees, 0.01f)
        assertEquals(1, selector.acceptedCount)
    }

    @Test
    fun `a half-metre of translation alone is a keyframe`() {
        val selector = anchored()
        val decision = selector.offer(frame(at = Vec3(0.6f, 0f, 0f), atMillis = 500L))
        assertEquals(KeyframeReason.TRANSLATION, decision.reason)
        assertTrue(decision.accepted)
        assertEquals(0.6f, decision.translationMetres, 1e-4f)
        assertEquals(0f, decision.rotationDegrees, 0.01f)
        assertEquals(2, selector.acceptedCount)
    }

    @Test
    fun `a fifteen-degree turn in place is a keyframe - the view changed, the position did not`() {
        val selector = anchored()
        val decision = selector.offer(frame(yawDegrees = 20f, atMillis = 500L))
        assertEquals(KeyframeReason.ROTATION, decision.reason)
        assertTrue(decision.accepted)
        assertEquals(0f, decision.translationMetres, 1e-4f)
        assertEquals(20f, decision.rotationDegrees, 0.01f)
    }

    @Test
    fun `thirteen degrees is still not a keyframe`() {
        assertEquals(
            KeyframeReason.TOO_CLOSE,
            anchored().offer(frame(yawDegrees = 13f, atMillis = 500L)).reason,
        )
    }

    @Test
    fun `a metre of motion through a featureless wall is rejected for its evidence`() {
        val decision = anchored().offer(frame(at = Vec3(1f, 0f, 0f), atMillis = 500L, features = 12))
        assertEquals(KeyframeReason.TOO_FEW_FEATURES, decision.reason)
        assertFalse(decision.accepted)
    }

    @Test
    fun `a lost tracker rejects the frame however far it moved`() {
        val decision = anchored().offer(frame(at = Vec3(2f, 0f, 0f), atMillis = 500L, tracked = false))
        assertEquals(KeyframeReason.TRACKING_INVALID, decision.reason)
        assertFalse(decision.accepted)
    }

    @Test
    fun `two frames a hundred milliseconds apart are held back, and kept once the interval passes`() {
        val selector = anchored()
        val fast = frame(at = Vec3(0.6f, 0f, 0f), atMillis = 100L)
        assertEquals(KeyframeReason.TOO_SOON, selector.offer(fast).reason)
        // The same placement 200 ms later is the keyframe the fast frame would have been: the
        // hold-back delays the node, it does not lose it.
        val samePlaceLater = fast.copy(timestampMillis = 300L)
        val decision = selector.offer(samePlaceLater)
        assertEquals(KeyframeReason.TRANSLATION, decision.reason)
        assertEquals(0.6f, decision.translationMetres, 1e-4f)
    }

    @Test
    fun `a rejected frame never moves the reference - the baseline is measured from the last node`() {
        val selector = anchored()
        assertEquals(KeyframeReason.TOO_CLOSE, selector.offer(frame(at = Vec3(0.2f, 0f, 0f), atMillis = 300L)).reason)
        val decision = selector.offer(frame(at = Vec3(0.55f, 0f, 0f), atMillis = 600L))
        assertEquals(KeyframeReason.TRANSLATION, decision.reason)
        // 0.55 m from the first keyframe, not 0.35 m from the rejected frame that came between.
        assertEquals(0.55f, decision.translationMetres, 1e-4f)
        assertEquals(600L, decision.elapsedMillis)
    }

    @Test
    fun `a sign-flipped quaternion is the same rotation, not a full turn`() {
        val identity = Quat.IDENTITY
        val flipped = Quat(-1f, 0f, 0f, 0f)
        assertEquals(0f, KeyframeSelector.rotationDegrees(identity, flipped), 0.01f)

        val ninety = Quat.fromAxisAngle(Vec3.Y_AXIS, (90.0 * PI / 180.0).toFloat())
        val ninetyFlipped = Quat(-ninety.w, -ninety.x, -ninety.y, -ninety.z)
        assertEquals(90f, KeyframeSelector.rotationDegrees(identity, ninety), 0.01f)
        assertEquals(90f, KeyframeSelector.rotationDegrees(identity, ninetyFlipped), 0.01f)
        assertEquals(0f, KeyframeSelector.rotationDegrees(ninety, ninetyFlipped), 0.01f)
    }

    @Test
    fun `decide is pure - the same question gives the same answer and touches nothing`() {
        val selector = anchored()
        val reference = selector.lastKeyframe
        val candidate = frame(at = Vec3(0f, 0f, 0.9f), atMillis = 400L)
        val first = KeyframeSelector.decide(reference, candidate)
        val second = KeyframeSelector.decide(reference, candidate)
        assertEquals(first, second)
        assertEquals(KeyframeReason.TRANSLATION, first.reason)
        assertEquals(reference, selector.lastKeyframe)
        assertEquals(1, selector.acceptedCount)
    }

    @Test
    fun `a reset forgets the reference so the next frame anchors again`() {
        val selector = anchored()
        selector.reset()
        assertNull(selector.lastKeyframe)
        assertEquals(0, selector.acceptedCount)
        assertEquals(KeyframeReason.FIRST, selector.offer(frame(atMillis = 5_000L)).reason)
    }

    @Test
    fun `the policy refuses configurations that would make a branch unreachable or useless`() {
        // A motion floor above the translation threshold leaves the translation branch dead.
        assertThrows { KeyframeConfig(minTranslationMetres = 0.4f, minMotionMetres = 0.5f) }
        assertThrows { KeyframeConfig(minRotationDegrees = 0f) }
        assertThrows { KeyframeConfig(minIntervalMillis = -1L) }
        assertThrows { KeyframeConfig(minFeaturePoints = -1) }
        assertThrows { KeyframeConfig(minTranslationMetres = Float.NaN) }
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
