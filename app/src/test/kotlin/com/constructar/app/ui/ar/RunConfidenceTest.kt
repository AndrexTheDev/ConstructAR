/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.core.geo.PolylineMetrics
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitPresets
import com.constructar.core.model.HitConfidence
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one number in the HUD that says whether the run is worth keeping.
 *
 * The threshold is the shared 0.60 score from type, incidence and depth uncertainty. A source
 * label alone no longer decides the warning. Saying it too often would train the operator to
 * ignore it, so an unstarted run and a run with no reported confidence say nothing at all.
 */
class RunConfidenceTest {

    @Test
    fun `the HUD and the order list draw their line at the same number`() {
        assertEquals(HitConfidence.RUN_TRUSTED, RunConfidence.WEAK_BELOW)
        assertEquals(59, RunConfidence.weakPointPercent(nodeCount = 3, worstNodeConfidence = 0.59f))
        assertNull(RunConfidence.weakPointPercent(nodeCount = 3, worstNodeConfidence = 0.70f))
    }

    @Test
    fun `a run of surface hits says nothing`() {
        assertNull(RunConfidence.weakPointPercent(nodeCount = 5, worstNodeConfidence = 0.92f))
        assertNull(RunConfidence.weakPointPercent(nodeCount = 5, worstNodeConfidence = 1f))
    }

    @Test
    fun `a weak point is named as a percentage`() {
        assertEquals(59, RunConfidence.weakPointPercent(nodeCount = 4, worstNodeConfidence = 0.59f))
        assertNull(RunConfidence.weakPointPercent(nodeCount = 4, worstNodeConfidence = 0.7f))
    }

    @Test
    fun `a run that has not started stays quiet`() {
        assertNull(RunConfidence.weakPointPercent(nodeCount = 0, worstNodeConfidence = 0.4f))
        assertNull(RunConfidence.weakPointPercent(nodeCount = 1, worstNodeConfidence = 0.4f))
    }

    @Test
    fun `a confidence of zero is the weakest point, not a missing one`() {
        // 0 is inside the range `RouteNodeRecord.confidence` documents, so it is a measurement the
        // app does not believe - not the absence of one. The HUD used to say nothing at all, while
        // the order list counted it with every other point below the bar (findings 47, 158).
        assertEquals(1, RunConfidence.weakPointPercent(nodeCount = 6, worstNodeConfidence = 0f))
    }

    @Test
    fun `the phone and the paperwork agree about a zero-confidence run`() {
        // The invariant the two share a constant for: if the HUD warns, the bill of materials
        // flags the run - and the other way round, at the one value where they used to disagree.
        fun node(id: String, x: Float, confidence: Float) = RouteNodeRecord(
            id = id,
            position = Vec3(x, 0f, 0f),
            normal = Vec3(0f, 0f, 1f),
            surface = SurfaceKind.WALL,
            hitSource = HitSource.PLANE_POLYGON,
            confidence = confidence,
        )
        val nodes = listOf(node("n1", 0f, 1f), node("n2", 3f, 0f))
        val metrics = PolylineMetrics.measure(nodes, ConduitPresets.byLabel("Cable Ø16")!!)

        assertEquals("the paperwork flags the run", 1, metrics.lowConfidenceNodes)
        assertEquals(
            "and the phone says so at the same bar",
            1,
            RunConfidence.weakPointPercent(nodes.size, metrics.minimumConfidence),
        )
    }

    @Test
    fun `the percentage stays inside what the badge can show`() {
        // A confidence of 0.001 is not "0 %" - that would read as no measurement at all.
        assertEquals(1, RunConfidence.weakPointPercent(nodeCount = 2, worstNodeConfidence = 0.001f))
    }
}
