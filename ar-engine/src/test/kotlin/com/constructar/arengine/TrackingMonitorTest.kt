/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.tracking.Guidance
import com.constructar.arengine.tracking.TrackingMonitor
import com.constructar.arengine.tracking.TrackingQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The monitor's steady state: a frame that changes nothing must publish nothing.
 *
 * The class promises it in its own doc - "the steady state allocates nothing, only a genuine
 * change builds a new status, and that happens a handful of times per scan" - and two things
 * broke that promise (finding 103). The raw ambient-light estimate was stored and compared field
 * by field, and ARCore's estimate moves on almost every frame; a share of usable depth pixels was
 * carried along too and read by nobody. A status that changes 30 times a second is a status the
 * AR screen recomposes on, on a phone an electrician holds up for an hour.
 *
 * The light is reduced to the one fact the guidance uses - dark enough for the torch - so these
 * tests check the guard holds, and that the fact still arrives when it matters.
 */
class TrackingMonitorTest {

    private val monitor = TrackingMonitor()

    private fun frame(lux: Float, confidence: Int = 90) = monitor.onFrame(
        tracking = TrackingQuality.GOOD,
        depthConfidence = confidence,
        depthSupported = true,
        ambientLux = lux,
    )

    @Test
    fun `a frame that changes nothing publishes nothing`() {
        frame(lux = 300f)
        val published = monitor.status.value

        frame(lux = 300f)

        assertSame(
            "an identical frame must not allocate and publish a new status",
            published,
            monitor.status.value,
        )
    }

    @Test
    fun `a light reading in the same bucket publishes nothing`() {
        frame(lux = 300f)
        val published = monitor.status.value

        frame(lux = 320f)

        assertSame(
            "the estimate moves every frame; a 6 % change cannot be a new status",
            published,
            monitor.status.value,
        )
    }

    @Test
    fun `crossing into the dark still reaches the operator`() {
        frame(lux = 300f)

        frame(lux = 10f)

        val status = monitor.status.value
        assertEquals("below the threshold the torch is asked for", Guidance.DARK, status.guidance)
        assertTrue("the status carries the fact the guidance used", status.dark)
    }
}
