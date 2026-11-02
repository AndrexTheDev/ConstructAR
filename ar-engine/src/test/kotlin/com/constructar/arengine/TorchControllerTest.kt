/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.session.TorchController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The torch is the only light an electrician has in a cellar, and the button that offers it can
 * be refused by the camera: a tablet has no flash, and another camera client can hold the camera.
 *
 * The refusal used to be invisible - `isOn` stayed false, the tap had no effect, and nothing said
 * why - while the class quietly recorded the tap as a decision to remember, so the automatic path
 * kept re-asking the camera on every ambient sample for as long as the AR screen stayed open
 * (finding 106). These cases pin the outcome, the report, and the absence of the retry loop.
 */
class TorchControllerTest {

    private var now = 1_000L
    private var calls = 0

    private fun controller(accept: (Boolean) -> Boolean) = TorchController(
        switch = { desired ->
            calls++
            accept(desired)
        },
        clock = { now },
    )

    @Test
    fun `a tap the camera refuses is reported, not swallowed`() {
        val torch = controller { false }

        assertFalse("the torch is not on, so the tap did not succeed", torch.toggle())
        assertTrue("the screen has to be able to hide a button that cannot work", torch.unavailable)
    }

    @Test
    fun `a refused switch is not retried on every sample`() {
        val torch = controller { false }

        torch.toggle()
        // Thirty dark samples, ten seconds of scanning between them: a device whose camera refuses
        // the torch must be asked once, not once per sample.
        repeat(30) {
            now += 10_000
            torch.onAmbientIntensity(5f)
        }

        assertEquals("the camera was asked 1 time(s)", 1, calls)
        assertFalse(torch.isOn)
    }

    @Test
    fun `asking again gives the camera a second chance`() {
        var refuse = true
        val torch = controller { refuse }

        torch.toggle()
        assertTrue(torch.unavailable)

        refuse = false
        assertTrue("the second tap has to work once the camera accepts it", torch.toggle())
        assertFalse("and the refusal is not remembered against the device", torch.unavailable)
        assertTrue(torch.isOn)
    }

    @Test
    fun `the automatic path still lights a dark room`() {
        val torch = controller { true }

        torch.onAmbientIntensity(10f)

        assertTrue("below the threshold the light comes on", torch.isOn)
        assertEquals(1, calls)
    }

    @Test
    fun `an automatic light goes off again when the room is bright`() {
        val torch = controller { true }
        torch.onAmbientIntensity(10f)

        now += 10_000
        torch.onAmbientIntensity(300f)

        assertFalse("the dwell rule keeps it from flickering, not from switching off", torch.isOn)
        assertEquals(2, calls)
    }

    @Test
    fun `a manual tap is not undone by daylight`() {
        val torch = controller { true }
        assertTrue(torch.toggle())

        now += 60_000
        torch.onAmbientIntensity(300f)

        // "A manual tap wins until the user taps again" - the class's own words, and the reason
        // the operator can keep the light on in a lit hallway.
        assertTrue(torch.isOn)
        assertEquals("no further switching: the tap is the decision", 1, calls)
    }
}
