/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import com.constructar.core.math.Pose
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four things a marker dialog can say, decided once and testable without a phone.
 *
 * The number that matters here is not the factor: it is that the factor cannot arrive on a screen
 * without the evidence behind it (pairs, rejected pairs, baseline length), and that "outside the
 * window" is decided by `ReferenceCalibration` rather than by whatever the proposal believed about
 * itself.
 */
class MarkerStatusLineTest {

    private fun proposal(
        factor: Double,
        accepted: Boolean = ReferenceCalibration.accepted(factor),
        pairs: Int = 6,
        rejectedPairs: Int = 4,
        baselineMetres: Float = 3f,
        spread: Float = 0.01f,
    ) = MarkerScaleProposal(
        factor = factor,
        accepted = accepted,
        pairs = pairs,
        rejectedPairs = rejectedPairs,
        baselineMetres = baselineMetres,
        spread = spread,
    )

    @Test
    fun `a sweep that is not running has nothing to print`() {
        assertEquals(MarkerStatusLine.Off, MarkerStatusLine.of(MarkerSweepState.Off))
    }

    @Test
    fun `watching carries what the sweep has seen`() {
        val line = MarkerStatusLine.of(
            MarkerSweepState.Watching(sightings = 3, markerIds = 2, ignoredForeign = 1),
        )
        assertEquals(MarkerStatusLine.Watching(sightings = 3, markerIds = 2, ignoredForeign = 1), line)
    }

    @Test
    fun `a measured factor carries the pairs and the baseline it rests on`() {
        // The scene is the real arithmetic, not a hand-built proposal: the phone walks one metre
        // while the marker's own geometry says 1.05 m, so the factor is 1.05 from one pair over
        // one metre - and the line has to say both of those numbers, not just the factor.
        val observations = listOf(
            MarkerObservation(
                id = 3,
                tagInCamera = Vec3(0f, 0f, 2f),
                cameraPose = Pose(Quat.IDENTITY, Vec3(0f, 0f, 0f)),
            ),
            MarkerObservation(
                id = 3,
                tagInCamera = Vec3(-1.05f, 0f, 2f),
                cameraPose = Pose(Quat.IDENTITY, Vec3(1f, 0f, 0f)),
            ),
        )
        val estimate = MarkerBaseline.estimate(
            observations = observations,
            minBaselineMetres = 0.2f,
            maxRelativeDeviation = 0.04f,
        ) ?: error("a metre walked past a marker is a measurement")

        val line = MarkerStatusLine.of(MarkerSweepState.Ready(estimate, sightings = 2, ignoredForeign = 0))

        val measured = line as MarkerStatusLine.Measured
        assertEquals(1.05, measured.factor, 1e-4)
        assertEquals(1, measured.pairs)
        assertEquals(0, measured.rejectedPairs)
        assertEquals(1f, measured.baselineMetres, 1e-4f)
        // One pair is perfectly in agreement with itself, and the line has to say that rather than
        // stay silent: a spread that is only printed when it is large is a number the operator
        // learns to read as zero.
        assertEquals(0f, measured.spread, 1e-6f)
    }

    @Test
    fun `the spread travels with the factor`() {
        val line = MarkerStatusLine.of(
            MarkerSweepState.Ready(proposal(factor = 1.02, spread = 0.033f), sightings = 3, ignoredForeign = 0),
        )
        val measured = line as MarkerStatusLine.Measured
        assertEquals(1.02, measured.factor, 0.0)
        assertEquals(0.033f, measured.spread, 1e-6f)
    }

    @Test
    fun `three sightings that disagree print the worst disagreement`() {
        // One metre walked twice, with the marker's own geometry saying 1.05, 1.15 and 1.10: the
        // median ratio is 1.10, the worst accepted deviation 4.5 %, and the baseline-weighted mean
        // is 1.10 because the two-metre pair outweighs the two one-metre pairs together.
        val observations = listOf(
            MarkerObservation(
                id = 3,
                tagInCamera = Vec3(0f, 0f, 2f),
                cameraPose = Pose(Quat.IDENTITY, Vec3(0f, 0f, 0f)),
            ),
            MarkerObservation(
                id = 3,
                tagInCamera = Vec3(-1.05f, 0f, 2f),
                cameraPose = Pose(Quat.IDENTITY, Vec3(1f, 0f, 0f)),
            ),
            MarkerObservation(
                id = 3,
                tagInCamera = Vec3(-2.2f, 0f, 2f),
                cameraPose = Pose(Quat.IDENTITY, Vec3(2f, 0f, 0f)),
            ),
        )
        val estimate = MarkerBaseline.estimate(
            observations = observations,
            minBaselineMetres = 0.2f,
            maxRelativeDeviation = 0.10f,
        ) ?: error("three pairs over two metres are a measurement")

        val measured = MarkerStatusLine.of(
            MarkerSweepState.Ready(estimate, sightings = 3, ignoredForeign = 0),
        ) as MarkerStatusLine.Measured

        assertEquals(1.1, measured.factor, 1e-3)
        assertEquals(3, measured.pairs)
        assertEquals(0, measured.rejectedPairs)
        assertEquals(2f, measured.baselineMetres, 1e-4f)
        assertEquals(0.0454545f, measured.spread, 1e-4f)
    }

    @Test
    fun `a factor outside the window is said as such even when the proposal says accepted`() {
        val line = MarkerStatusLine.of(
            MarkerSweepState.Ready(proposal(factor = 1.3, accepted = true), sightings = 4, ignoredForeign = 0),
        )
        val outside = line as MarkerStatusLine.OutOfWindow
        assertEquals(1.3, outside.factor, 0.0)
        assertEquals(ReferenceCalibration.MIN_SCALE, outside.minScale, 0.0)
        assertEquals(ReferenceCalibration.MAX_SCALE, outside.maxScale, 0.0)
    }

    @Test
    fun `the window the line prints is the one the two-tap sweep uses`() {
        for (factor in listOf(0.85, 0.9, 1.0, 1.15)) {
            val line = MarkerStatusLine.of(
                MarkerSweepState.Ready(proposal(factor = factor), sightings = 2, ignoredForeign = 0),
            )
            assertTrue("$factor should be inside the window", line is MarkerStatusLine.Measured)
        }
        for (factor in listOf(0.84, 1.16, Float.NaN.toDouble())) {
            val line = MarkerStatusLine.of(
                MarkerSweepState.Ready(proposal(factor = factor), sightings = 2, ignoredForeign = 0),
            )
            assertTrue("$factor should be outside the window", line is MarkerStatusLine.OutOfWindow)
        }
    }
}
