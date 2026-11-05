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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sweep a screen holds while an operator waves a phone at a marker.
 *
 * Each test is a thing that happens on site: standing still and waiting, walking a metre, coming
 * back to the screen after the phone re-localised, waving past the same marker for half a minute,
 * and pointing at two markers that happen to be in the same room.
 */
class MarkerSweepTest {

    private val session = 41L

    private fun options(
        sessionId: Long = session,
        maxSightings: Int = MarkerSweep.DEFAULT_MAX_SIGHTINGS_PER_ID,
        minPairs: Int = 1,
    ) = MarkerSweepOptions(
        sessionId = sessionId,
        minBaselineMetres = 0.2f,
        maxRelativeDeviation = 0.04f,
        minPairs = minPairs,
        maxSightingsPerId = maxSightings,
    )

    /** ARCore's metre 5 % short, as in `MarkerBaselineTest`: walking `arc` metres puts the tag there. */
    private fun sighting(
        arc: Float,
        factor: Float = 1.05f,
        id: Int = 7,
        sessionId: Long = session,
    ): MarkerObservation {
        val position = Vec3(arc, 0f, 0f)
        val cameraReal = Vec3(arc * factor, 0f, 0f)
        return MarkerObservation(
            id = id,
            tagInCamera = Quat.IDENTITY.rotate(Vec3(0f, 0f, 2f) - cameraReal),
            cameraPose = Pose(Quat.IDENTITY, position),
            sessionId = sessionId,
        )
    }

    private fun sweep(options: MarkerSweepOptions = options()): MarkerSweep = MarkerSweep.start(options)!!

    @Test
    fun `a sweep starts watching and answers after the second sighting`() {
        val started = sweep()
        assertEquals(MarkerSweepState.Watching(0, 0, 0), started.state)
        val one = started.observe(sighting(0f))
        assertEquals(MarkerSweepState.Watching(sightings = 1, markerIds = 1, ignoredForeign = 0), one.state)
        val two = one.observe(sighting(1f))
        val ready = two.state as MarkerSweepState.Ready
        assertEquals(1.05, ready.proposal.factor, 1e-4)
        assertEquals(1, ready.proposal.pairs)
        assertEquals(2, ready.sightings)
        assertEquals(0, ready.ignoredForeign)
    }

    @Test
    fun `standing still is not a measurement`() {
        val still = sweep().observe(sighting(0f)).observe(sighting(0f))
        assertEquals(MarkerSweepState.Watching(sightings = 2, markerIds = 1, ignoredForeign = 0), still.state)
    }

    @Test
    fun `a sighting from another session is counted and never paired`() {
        // The phone re-localised between the two looks: two world origins, so the pair would be a
        // subtraction of two different rooms (finding 217's defect, one layer up).
        val mixed = sweep()
            .observe(sighting(0f, sessionId = session))
            .observe(sighting(1f, sessionId = session + 1))
        assertEquals(MarkerSweepState.Watching(sightings = 1, markerIds = 1, ignoredForeign = 1), mixed.state)
        // And a published factor is not quietly withdrawn by one foreign frame either.
        val ready = sweep().observe(sighting(0f)).observe(sighting(1f))
        val stillReady = ready.observe(sighting(2f, sessionId = session + 1))
        assertTrue(stillReady.state is MarkerSweepState.Ready)
        assertEquals(1, (stillReady.state as MarkerSweepState.Ready).ignoredForeign)
    }

    @Test
    fun `a third sighting refines the factor`() {
        val three = sweep().observe(sighting(0f)).observe(sighting(1f)).observe(sighting(2f))
        val ready = three.state as MarkerSweepState.Ready
        assertEquals(3, ready.proposal.pairs)
        assertEquals(1.05, ready.proposal.factor, 1e-4)
        assertEquals(2f, ready.proposal.baselineMetres, 1e-4f)
    }

    @Test
    fun `old sightings are dropped so a marker that moved stops voting`() {
        val capped = sweep(options(maxSightings = 2))
            .observe(sighting(0f))
            .observe(sighting(1f))
            .observe(sighting(2f))
            .observe(sighting(3f))
        val ready = capped.state as MarkerSweepState.Ready
        // Only the two newest survive: one pair, one metre.
        assertEquals(2, ready.sightings)
        assertEquals(1, ready.proposal.pairs)
        assertEquals(1f, ready.proposal.baselineMetres, 1e-4f)
    }

    @Test
    fun `two markers in one room are two sweeps' worth of evidence`() {
        val both = sweep().observe(sighting(0f, id = 7)).observe(sighting(1f, id = 8))
        assertEquals(MarkerSweepState.Watching(sightings = 2, markerIds = 2, ignoredForeign = 0), both.state)
    }

    @Test
    fun `reset ends the sweep and forgets the sightings`() {
        val ended = sweep().observe(sighting(0f)).observe(sighting(1f)).reset()
        assertEquals(MarkerSweepState.Off, ended.state)
        assertEquals(
            MarkerSweepState.Watching(sightings = 1, markerIds = 1, ignoredForeign = 0),
            ended.observe(sighting(0f)).state,
        )
    }

    @Test
    fun `options that cannot describe a sweep are refused`() {
        assertNull(MarkerSweep.start(options(minPairs = 0)))
        assertNull(MarkerSweep.start(options(maxSightings = 0)))
        assertNull(MarkerSweep.start(this.options().copy(minBaselineMetres = 0f)))
        assertNull(MarkerSweep.start(this.options().copy(maxRelativeDeviation = 0f)))
        assertNull(MarkerSweep.start(this.options().copy(minBaselineMetres = Float.NaN)))
        // Two pairs required and only one available: still watching, not ready.
        val strict = MarkerSweep.start(options(minPairs = 2))!!
        val one = strict.observe(sighting(0f)).observe(sighting(1f))
        assertTrue(one.state is MarkerSweepState.Watching)
    }
}
