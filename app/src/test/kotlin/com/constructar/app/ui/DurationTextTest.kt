/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The duration line in the protocol's time section.
 *
 * The document is signed and handed to a customer, and it already writes lengths and quantities
 * with `Locale.ROOT` (`14.82 m`). Formatting the hours with the *device* locale instead put the
 * digits of whatever language the phone was set to into the same paragraph - `٢ h ١٥ min` on an
 * Arabic-locale phone, in a document whose text was English, because all six shipped locales use
 * Latin digits and the fallback is English.
 */
class DurationTextTest {

    private lateinit var original: Locale

    @Before
    fun rememberLocale() {
        original = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `hours and minutes are split the way the screen shows them`() {
        val millis = 2 * 3_600_000L + 15 * 60_000L

        assertEquals("2 h 15 min", durationText(millis, "%1\$d h %2\$d min", "%1\$d min"))
    }

    @Test
    fun `a sub-hour job prints minutes only`() {
        assertEquals("45 min", durationText(45 * 60_000L, "%1\$d h %2\$d min", "%1\$d min"))
    }

    @Test
    fun `the device language does not change the digits of a signed document`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val millis = 2 * 3_600_000L + 15 * 60_000L

        val text = durationText(millis, "%1\$d h %2\$d min", "%1\$d min")

        assertEquals("2 h 15 min", text)
    }
}
