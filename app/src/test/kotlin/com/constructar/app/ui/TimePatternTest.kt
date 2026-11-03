/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * The clock column: the phone's *setting* for the convention, Latin digits regardless of language.
 *
 * `DateFormat.getTimeInstance(SHORT)` decides both from the language. So a German fitter whose
 * phone speaks English read `10:00 PM` on a protocol whose other numbers are 24-hour, and a phone
 * set to Arabic fetched a time table written `٢:٠٥ م` beside lengths written `14.82 m` - the same
 * "a signed document may not change its digits" defect finding 31 fixed for durations and finding
 * 121 for dates, left open in the column next to them (finding 148).
 */
class TimePatternTest {

    private lateinit var originalLocale: Locale

    /** 2026-11-01T14:05:00Z - a Sunday in central Europe, after the DST change. */
    private val novemberFirstAt1405 = 1_793_541_900_000L

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Before
    fun rememberDefaults() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreDefaults() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `a 24-hour setting prints the clock the operator set`() {
        Locale.setDefault(Locale.GERMANY)

        assertEquals("14:05", formatTimeLabel(novemberFirstAt1405, timePattern(use24Hour = true), utc))
    }

    @Test
    fun `a 12-hour setting prints it even on a 24-hour language`() {
        // German is a 24-hour language; the owner of the phone chose otherwise, and the app follows
        // the owner - which the shipped `DateFormat` call did not.
        Locale.setDefault(Locale.GERMANY)

        assertEquals("2:05 PM", formatTimeLabel(novemberFirstAt1405, timePattern(use24Hour = false), utc))
    }

    @Test
    fun `an Arabic phone still gets Latin digits in a signed document`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))

        val twentyFour = formatTimeLabel(novemberFirstAt1405, timePattern(use24Hour = true), utc)
        val twelve = formatTimeLabel(novemberFirstAt1405, timePattern(use24Hour = false), utc)

        assertEquals("14:05", twentyFour)
        assertEquals("2:05 PM", twelve)
        // The digits and the separator, not just the shape of the string.
        assertTrue(twentyFour, twentyFour.all { it.isDigit() || it == ':' })
    }

    @Test
    fun `the zone still decides the wall clock`() {
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        val berlin = formatTimeLabel(
            novemberFirstAt1405,
            timePattern(use24Hour = true),
            TimeZone.getTimeZone("Europe/Berlin"),
        )

        assertEquals("15:05", berlin)
    }
}
