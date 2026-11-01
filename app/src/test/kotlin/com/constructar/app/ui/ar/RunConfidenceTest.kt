/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.core.model.HitConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one number in the HUD that says whether the run is worth keeping.
 *
 * The threshold is the hit cascade's own tiers (plane 1.0, depth 0.85, raycast 0.7): at or above
 * 0.85 every point came from a surface, below it at least one came from something weaker. Saying
 * it too often would train the operator to ignore it, so an unstarted run and a run the engine
 * reported no confidence for say nothing at all.
 */
class RunConfidenceTest {

    @Test
    fun `the HUD and the order list draw their line at the same number`() {
        // Finding 47: the HUD warned below 0.85 while the order list flagged below 0.5, so a run
        // measured off a raycast was questioned on the phone and passed as certain into the
        // wholesaler's list. One constant, one bar.
        assertEquals(HitConfidence.RUN_TRUSTED, RunConfidence.WEAK_BELOW)
        // A raycast point (0.7) is exactly the case that used to fall between the two bars.
        assertEquals(70, RunConfidence.weakPointPercent(nodeCount = 3, worstNodeConfidence = 0.7f))
    }

    @Test
    fun `a run of surface hits says nothing`() {
        assertNull(RunConfidence.weakPointPercent(nodeCount = 5, worstNodeConfidence = 0.92f))
        assertNull(RunConfidence.weakPointPercent(nodeCount = 5, worstNodeConfidence = 1f))
    }

    @Test
    fun `a weak point is named as a percentage`() {
        assertEquals(62, RunConfidence.weakPointPercent(nodeCount = 4, worstNodeConfidence = 0.62f))
        assertEquals(70, RunConfidence.weakPointPercent(nodeCount = 4, worstNodeConfidence = 0.7f))
    }

    @Test
    fun `nothing measured and no reading both stay quiet`() {
        assertNull(RunConfidence.weakPointPercent(nodeCount = 0, worstNodeConfidence = 0.4f))
        assertNull(RunConfidence.weakPointPercent(nodeCount = 1, worstNodeConfidence = 0.4f))
        assertNull(RunConfidence.weakPointPercent(nodeCount = 6, worstNodeConfidence = 0f))
    }

    @Test
    fun `the percentage stays inside what the badge can show`() {
        // A confidence of 0.001 is not "0 %" - that would read as no measurement at all.
        assertEquals(1, RunConfidence.weakPointPercent(nodeCount = 2, worstNodeConfidence = 0.001f))
    }
}
