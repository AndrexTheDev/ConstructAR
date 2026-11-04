/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.licence.LicenceVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one string in the purchase flow that the operator has to *transmit*.
 *
 * He copies it (finding 133: neither screen offered a way, so the twelve characters were retyped
 * by hand from a phone into the same phone), pastes it into a mail, and the developer mints a key
 * bound to it. So what lands on the clipboard has to be exactly what `deviceMatches` accepts -
 * not the text with the font's hair space or a line break a narrow screen put in the middle.
 */
class DeviceCodeTest {

    /** A tag of the shape `LicenceVerifier.deviceTag` produces. */
    private val tag = "3F2A9C4B71DE"

    @Test
    fun `the displayed code is copied as it is`() {
        assertEquals(tag, DeviceCode.normalise(tag))
    }

    @Test
    fun `spacing and case a screen might add do not reach the clipboard`() {
        assertEquals(tag, DeviceCode.normalise(" 3f2a 9c4b\n71de "))
    }

    @Test
    fun `the copied text is one a licence for this phone matches`() {
        // `deviceMatches(bound, actual)` is what activation compares; the clipboard value is the
        // `actual` side, and it must match whatever the developer was told.
        val copied = DeviceCode.normalise(" 3f2a9c4b71de ")

        assertTrue(LicenceVerifier.deviceMatches(bound = tag, actual = copied))
    }
}
