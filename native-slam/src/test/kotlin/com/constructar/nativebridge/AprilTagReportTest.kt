/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the vendored decoder's answer may look like once it reaches Kotlin.
 *
 * The native layer writes a header, then six floats per tag. Everything that turns those six floats
 * into a claim about a room is checked here: a tag behind the camera, a margin outside `[0, 1]`, a
 * header that promises more tags than the array holds or two sightings of one id - each of those
 * would be printed as a measurement.
 */
class AprilTagReportTest {

    private fun report(vararg tags: FloatArray): AprilTagReport? =
        AprilTagReport.from(floatArrayOf(tags.size.toFloat()) + tags.flatMap { it.toList() }.toFloatArray())

    private fun tag(
        id: Float = 7f,
        x: Float = 0f,
        y: Float = 0f,
        z: Float = 2f,
        margin: Float = 0.85f,
        hamming: Float = 0f,
    ) = floatArrayOf(id, x, y, z, margin, hamming)

    @Test
    fun `a clean read is a distance in metres`() {
        val parsed = report(tag())
        assertEquals(1, parsed?.tags?.size)
        val sighting = parsed?.tags?.single()
        assertEquals(7, sighting?.id)
        assertEquals(2f, sighting?.distanceMetres ?: 0f, 1e-4f)
        assertTrue(sighting?.isClean ?: false)
        assertEquals(sighting, parsed?.nearest)
        assertEquals(sighting, parsed?.strongest)
    }

    @Test
    fun `the nearest tag is not the strongest one`() {
        // A far tag can read more cleanly than a near one held at an angle; a scale check wants the
        // near one, because its corners are the sharpest, and the two are not the same question.
        val parsed = report(tag(id = 3f, z = 4f, margin = 0.95f), tag(id = 4f, z = 1f, margin = 0.62f))
        assertEquals(4, parsed?.nearest?.id)
        assertEquals(3, parsed?.strongest?.id)
        assertEquals(2, parsed?.tags?.size)
    }

    @Test
    fun `an empty frame is an answer, a broken array is not`() {
        assertEquals(0, AprilTagReport.from(floatArrayOf(0f))?.tags?.size)
        assertNull(AprilTagReport.from(null))
        assertNull(AprilTagReport.from(floatArrayOf()))
        // A header of one tag with no tag behind it, and a header that is not a whole number.
        assertNull(AprilTagReport.from(floatArrayOf(1f)))
        assertNull(AprilTagReport.from(floatArrayOf(0.5f)))
        assertNull(AprilTagReport.from(floatArrayOf(33f)))
        assertNull(report(tag(), tag()))
    }

    @Test
    fun `a sighting that cannot be a place is refused`() {
        assertNull(report(tag(z = 0f)))
        assertNull(report(tag(z = -1f)))
        assertNull(report(tag(margin = 1.4f)))
        assertNull(report(tag(margin = -0.1f)))
        assertNull(report(tag(z = Float.NaN)))
        assertNull(report(tag(hamming = 1.5f)))
        assertNull(report(tag(id = -1f)))
        // Two sightings of one tag are one tag seen twice, not two tags.
        assertNull(report(tag(id = 7f), tag(id = 7f, z = 3f)))
        assertEquals(AprilTagReport.FLOATS_PER_TAG, 6)
        val empty = AprilTagReport.from(floatArrayOf(0f))
        assertNull(empty?.nearest)
        assertNull(empty?.strongest)
    }
}
