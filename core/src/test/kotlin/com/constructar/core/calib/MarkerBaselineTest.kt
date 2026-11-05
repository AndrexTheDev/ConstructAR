/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import com.constructar.core.math.Pose
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two views of one marker, against a phone whose metre is wrong.
 *
 * The scene is the same in every test and it is a real one: a marker 2 m ahead of where the phone
 * starts, the phone walking sideways along a line, and ARCore reporting `arc` metres for every
 * `arc * factor` real metres it moved. `tagInCamera` is then the *real* vector from the phone to
 * the marker, because the decoder measures with the marker's printed size — so a correct
 * implementation has to recover `factor` and an implementation that forgot to rotate, or that
 * mixed the two metres, recovers something else.
 */
class MarkerBaselineTest {

    private val roomAhead = Vec3(0f, 0f, 2f)

    /** A sighting from `camera == arc` metres walked, with ARCore's metre `factor` short. */
    private fun sighting(
        arc: Float,
        factor: Float = 1.05f,
        id: Int = 7,
        rotation: Quat = Quat.IDENTITY,
        tagReal: Vec3 = roomAhead,
    ): MarkerObservation {
        val position = Vec3(arc, 0f, 0f)
        val cameraReal = Vec3(arc * factor, 0f, 0f)
        // The camera-frame vector is the real one, rotated back into the camera's own axes.
        val inCamera = rotation.conjugate.rotate(tagReal - cameraReal)
        return MarkerObservation(
            id = id,
            tagInCamera = inCamera,
            cameraPose = Pose(rotation, position),
            decisionMargin = 0.85f,
            hamming = 0,
        )
    }

    @Test
    fun `walking a metre past a marker recovers the phone's short metre`() {
        val proposal = MarkerBaseline.estimate(
            listOf(sighting(0f), sighting(1f)),
            minBaselineMetres = 0.2f,
            maxRelativeDeviation = 0.04f,
        )
        assertNotNull(proposal)
        assertEquals(1.05, proposal?.factor ?: 0.0, 1e-4)
        assertTrue(proposal?.accepted ?: false)
        assertEquals(1, proposal?.pairs ?: -1)
        assertEquals(0, proposal?.rejectedPairs ?: -1)
        assertEquals(1f, proposal?.baselineMetres ?: 0f, 1e-4f)
    }

    @Test
    fun `a phone whose metre is right gets a factor of one`() {
        val proposal = MarkerBaseline.estimate(
            listOf(sighting(0f, factor = 1f), sighting(1f, factor = 1f)),
            minBaselineMetres = 0.2f,
            maxRelativeDeviation = 0.04f,
        )
        assertEquals(1.0, proposal?.factor ?: 0.0, 1e-6)
        assertTrue(proposal?.accepted ?: false)
    }

    @Test
    fun `the camera's rotation is part of the measurement`() {
        // The phone turns a quarter turn between the two sightings. Subtracting the camera-frame
        // vectors without rotating them compares two different frames and yields ~2.2, which the
        // window then refuses - "recalibrate" for a phone that was right.
        val turned = Quat.fromAxisAngle(Vec3.Y_AXIS, (-PI / 2).toFloat())
        val second = sighting(1f, rotation = turned)
        val proposal = MarkerBaseline.estimate(
            listOf(sighting(0f), second),
            minBaselineMetres = 0.2f,
            maxRelativeDeviation = 0.04f,
        )
        assertEquals(1.05, proposal?.factor ?: 0.0, 1e-3)
        assertTrue(proposal?.accepted ?: false)
    }

    @Test
    fun `a baseline too short to be evidence is not a pair`() {
        assertNull(
            MarkerBaseline.estimate(
                listOf(sighting(0f), sighting(0.1f)),
                minBaselineMetres = 0.2f,
                maxRelativeDeviation = 0.04f,
            ),
        )
        val proposal = MarkerBaseline.estimate(
            listOf(sighting(0f), sighting(0.1f)),
            minBaselineMetres = 0.05f,
            maxRelativeDeviation = 0.04f,
        )
        assertNotNull(proposal)
        assertEquals(1.05, proposal?.factor ?: 0.0, 1e-3)
    }

    @Test
    fun `a marker that moved is an outlier and the agreeing pairs still decide`() {
        // Four clean sightings and one whose tag vector is half a metre off - a marker knocked
        // askew, or an id misread. The median has to find the six agreeing pairs and drop the rest.
        val observations = (0..3).map { sighting(it.toFloat()) } +
            listOf(
                sighting(4f, tagReal = roomAhead + Vec3(0.5f, 0f, 0f)),
            )
        val proposal = MarkerBaseline.estimate(
            observations,
            minBaselineMetres = 0.2f,
            maxRelativeDeviation = 0.04f,
        )
        assertNotNull(proposal)
        assertEquals(1.05, proposal?.factor ?: 0.0, 1e-3)
        assertEquals(6, proposal?.pairs ?: -1)
        assertEquals(4, proposal?.rejectedPairs ?: -1)
        assertTrue((proposal?.spread ?: 1f) <= 0.04f)
        assertEquals(3f, proposal?.baselineMetres ?: 0f, 1e-4f)
    }

    @Test
    fun `a phone that is far out is measured and refused, not hidden`() {
        val proposal = MarkerBaseline.estimate(
            listOf(sighting(0f, factor = 1.3f), sighting(1f, factor = 1.3f)),
            minBaselineMetres = 0.2f,
            maxRelativeDeviation = 0.04f,
        )
        assertNotNull(proposal)
        assertEquals(1.3, proposal?.factor ?: 0.0, 1e-4)
        // The window is `ReferenceCalibration`'s, and this asserts the proposal asks it rather than
        // deciding for itself.
        assertFalse(proposal?.accepted ?: true)
        assertFalse(ReferenceCalibration.accepted(1.3))
    }

    @Test
    fun `one pair is not enough when the caller asked for two`() {
        assertNull(
            MarkerBaseline.estimate(
                listOf(sighting(0f), sighting(1f)),
                minBaselineMetres = 0.2f,
                maxRelativeDeviation = 0.04f,
                minPairs = 2,
            ),
        )
    }

    @Test
    fun `two different markers are not a pair, and a broken sighting is not a place`() {
        assertNull(
            MarkerBaseline.estimate(
                listOf(sighting(0f, id = 7), sighting(1f, id = 8)),
                minBaselineMetres = 0.2f,
                maxRelativeDeviation = 0.04f,
            ),
        )
        val broken = sighting(1f).copy(tagInCamera = Vec3(Float.NaN, 0f, 2f))
        assertNull(
            MarkerBaseline.estimate(
                listOf(sighting(0f), broken),
                minBaselineMetres = 0.2f,
                maxRelativeDeviation = 0.04f,
            ),
        )
        // A tag behind the camera is not a sighting either.
        val behind = sighting(1f).copy(tagInCamera = Vec3(0f, 0f, -2f))
        assertNull(
            MarkerBaseline.estimate(
                listOf(sighting(0f), behind),
                minBaselineMetres = 0.2f,
                maxRelativeDeviation = 0.04f,
            ),
        )
    }

    @Test
    fun `the same observations give the same proposal twice`() {
        val observations = (0..2).map { sighting(it.toFloat()) }
        val first = MarkerBaseline.estimate(observations, 0.2f, 0.04f)
        val second = MarkerBaseline.estimate(observations, 0.2f, 0.04f)
        assertEquals(first, second)
        assertNull(MarkerBaseline.estimate(emptyList(), 0.2f, 0.04f))
        assertNull(MarkerBaseline.estimate(listOf(sighting(0f)), 0.2f, 0.04f))
        assertNull(MarkerBaseline.estimate(observations, 0f, 0.04f))
        assertNull(MarkerBaseline.estimate(observations, 0.2f, 0f))
        assertNull(MarkerBaseline.estimate(observations, 0.2f, 0.04f, minPairs = 0))
    }
}
