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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sweep as a state machine with something to *say* (finding 242).
 *
 * A sweep that only counts sightings cannot tell "the phone is not pointing at the marker" from
 * "you have walked a metre into the wall", and both used to print the same sentence. The optical
 * parallax is what separates them: walking at a marker moves the baseline almost entirely along
 * the sight ray, where depth noise dominates, while walking sideways moves it across the ray,
 * where the triangle is strong. `b_perp = || b - (b . v) v ||` with `b` the camera-to-camera
 * vector and `v` the unit sight ray is that separation, and these tests are the four things it
 * has to get right.
 *
 * The scenes are physical: the phone stands one metre from a marker two metres away and moves.
 */
class MarkerSweepStateMachineTest {

    private val session = 91L

    /** The marker sits at world (0, 0, 2); the camera is at [camera], looking down +z. */
    private fun sighting(camera: Vec3, id: Int = 5, sessionId: Long = session): MarkerObservation =
        MarkerObservation(
            id = id,
            tagInCamera = Vec3(0f, 0f, 2f) - camera,
            cameraPose = Pose(Quat.IDENTITY, camera),
            sessionId = sessionId,
        )

    /** The dialog's rules: three views, 35 cm across the ray, warn past 80 cm of walk. */
    private fun options(
        minPerpendicular: Float = MarkerSweepOptions.DEFAULT_MIN_PERPENDICULAR_METRES,
        minSightings: Int = MarkerSweepOptions.DEFAULT_MIN_SIGHTINGS,
    ) = MarkerSweepOptions(
        sessionId = session,
        minBaselineMetres = 0.2f,
        maxRelativeDeviation = 0.05f,
        minSightings = minSightings,
        minPerpendicularBaselineMetres = minPerpendicular,
    )

    private fun sweep(options: MarkerSweepOptions = options()): MarkerSweep =
        MarkerSweep.start(options) ?: error("the options describe a sweep")

    @Test
    fun `a start with no sighting is aiming, not walking`() {
        val state = MarkerSweep.start(options())!!.state as MarkerSweepState.Watching
        assertEquals(MarkerWatchingPhase.AIMING, state.phase)
        assertEquals(0, state.sightings)
        assertFalse(state.axialOnly)
    }

    @Test
    fun `the first sighting turns aiming into walking`() {
        val started = MarkerSweep.start(options())!!
        val state = started.observe(sighting(Vec3(0f, 0f, 0f))).state as MarkerSweepState.Watching
        assertEquals(MarkerWatchingPhase.WALKING, state.phase)
        assertEquals(1, state.sightings)
        assertEquals(1, state.markerIds)
        // One sighting is not a pair, so there is nothing to warn about yet.
        assertFalse(state.axialOnly)
    }

    @Test
    fun `walking into the wall is a warning, not progress`() {
        // Straight at the marker and back: 1.2 m of baseline, every centimetre of it along the
        // sight ray. A dialog that said "walk more" here would be telling the operator to repeat
        // the one movement that cannot help.
        var sweep = MarkerSweep.start(options())!!
        sweep = sweep.observe(sighting(Vec3(0f, 0f, 0f)))
        sweep = sweep.observe(sighting(Vec3(0f, 0f, 1.2f)))
        // Read once into a value of the interface type: `sweep.state` is stable enough for the
        // compiler to smart-cast it, and a second read would then be a cast that "can never
        // succeed" (which `-Werror` refuses).
        val published = sweep.state
        val state = published as MarkerSweepState.Watching

        assertTrue("1.2 m of axial walk should be called out", state.axialOnly)
        assertEquals(MarkerWatchingPhase.WALKING, state.phase)
        assertNull("an axial walk is no evidence, so no factor", published as? MarkerSweepState.Ready)
    }

    @Test
    fun `sideways movement publishes the factor once it is far enough across the ray`() {
        // 40 cm sideways from a metre away: b_perp = 0.40 m, past the 0.35 m the dialog asks for.
        var sweep = MarkerSweep.start(options())!!
        sweep = sweep.observe(sighting(Vec3(0f, 0f, 0f)))
        sweep = sweep.observe(sighting(Vec3(0.4f, 0f, 0f)))
        sweep = sweep.observe(sighting(Vec3(0.8f, 0f, 0f)))

        val ready = sweep.state as MarkerSweepState.Ready
        assertEquals(3, ready.sightings)
        assertTrue(
            "the proposal has to know how much of the baseline was across the ray",
            ready.proposal.perpendicularMetres >= MarkerSweepOptions.DEFAULT_MIN_PERPENDICULAR_METRES,
        )
        assertEquals(1.0, ready.proposal.factor, 1e-4)
    }

    @Test
    fun `a small sideways step is walking, and only the third view may publish it`() {
        // 20 cm across the ray is a real direction change and not yet the evidence the dialog
        // asks for: with a 35 cm requirement the sweep stays in WALKING.
        var sweep = MarkerSweep.start(options())!!
        sweep = sweep.observe(sighting(Vec3(0f, 0f, 0f)))
        sweep = sweep.observe(sighting(Vec3(0.2f, 0f, 0f)))
        val state = sweep.state as MarkerSweepState.Watching
        assertEquals(MarkerWatchingPhase.WALKING, state.phase)
        assertFalse("20 cm across is not an axial walk", state.axialOnly)
    }

    @Test
    fun `three sightings are the floor even when the parallax is there`() {
        var sweep = MarkerSweep.start(options(minSightings = 3))!!
        sweep = sweep.observe(sighting(Vec3(0f, 0f, 0f)))
        sweep = sweep.observe(sighting(Vec3(0.5f, 0f, 0f)))
        val two = sweep.state as MarkerSweepState.Watching
        assertEquals(2, two.sightings)
        val three = sweep.observe(sighting(Vec3(1f, 0f, 0f))).state
        assertTrue("a third view is what the dialog asks for", three is MarkerSweepState.Ready)
    }

    @Test
    fun `a sweep opened without a parallax requirement behaves as before`() {
        // The screenshot-free default: `minPerpendicularBaselineMetres = 0` means "do not ask",
        // so the two-sighting scene from finding 235 still publishes after the second view.
        val plain = MarkerSweepOptions(
            sessionId = session,
            minBaselineMetres = 0.2f,
            maxRelativeDeviation = 0.04f,
        )
        var sweep = MarkerSweep.start(plain)!!
        sweep = sweep.observe(sighting(Vec3(0f, 0f, 0f)))
        sweep = sweep.observe(sighting(Vec3(1f, 0f, 0f)))
        assertTrue(sweep.state is MarkerSweepState.Ready)
        assertFalse((MarkerSweepState.Watching(1, 1, 0)).axialOnly)
    }

    @Test
    fun `the state machine refuses options it cannot honour`() {
        assertNull(MarkerSweep.start(options().copy(minSightings = 0)))
        assertNull(MarkerSweep.start(options().copy(minPerpendicularBaselineMetres = -1f)))
        assertNull(MarkerSweep.start(options().copy(minPerpendicularBaselineMetres = Float.NaN)))
        assertNull(MarkerSweep.start(options().copy(axialBaselineMetres = 0f)))
        assertNull(MarkerSweep.start(options().copy(axialBaselineMetres = Float.NaN)))
    }

    @Test
    fun `the status line carries the phase and the warning through`() {
        val aiming = MarkerStatusLine.of(MarkerSweepState.Watching(0, 0, 0))
        assertEquals(
            MarkerStatusLine.Watching(0, 0, 0, MarkerWatchingPhase.AIMING, false),
            aiming,
        )
        val axial = MarkerStatusLine.of(
            MarkerSweepState.Watching(
                sightings = 2,
                markerIds = 1,
                ignoredForeign = 0,
                phase = MarkerWatchingPhase.WALKING,
                axialOnly = true,
            ),
        ) as MarkerStatusLine.Watching
        assertTrue(axial.axialOnly)
        assertEquals(MarkerWatchingPhase.WALKING, axial.phase)
    }
}
