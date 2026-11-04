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
import java.util.TimeZone

/**
 * The numeric day label (`01.11.2026`).
 *
 * `SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())` looks harmless and is not: the formatter
 * takes its **calendar** from the locale too. On a Thai phone that is the Buddhist calendar, so
 * the project list showed the last-updated date 543 years in the future and the paywall offered a
 * licence "valid until 01.11.2569". On a `ja-JP-u-ca-japanese` device the year would be the era
 * year. `Locale.ROOT` pins it to Gregorian Latin digits, which is what a fixed-format field has
 * to be.
 */
class DayLabelTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalZone: TimeZone

    /** 2026-11-01T00:00:00Z. */
    private val novemberFirst2026 = 1_793_491_200_000L

    @Before
    fun rememberDefaults() {
        originalLocale = Locale.getDefault()
        originalZone = TimeZone.getDefault()
    }

    @After
    fun restoreDefaults() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `the day label is fixed format`() {
        assertEquals("01.11.2026", formatDayLabel(novemberFirst2026, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `a Buddhist-calendar phone still gets the Gregorian year`() {
        Locale.setDefault(Locale.forLanguageTag("th-TH"))

        assertEquals("01.11.2026", formatDayLabel(novemberFirst2026, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `a Japanese imperial-calendar phone still gets the Gregorian year`() {
        Locale.setDefault(Locale.forLanguageTag("ja-JP-u-ca-japanese"))

        assertEquals("01.11.2026", formatDayLabel(novemberFirst2026, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `the label follows the zone it is asked for`() {
        // 23:30 UTC on 31 October is already 1 November two hours east of it.
        val lateOctober = novemberFirst2026 - 30 * 60_000L
        val utc = formatDayLabel(lateOctober, TimeZone.getTimeZone("UTC"))
        val berlin = formatDayLabel(lateOctober, TimeZone.getTimeZone("Europe/Berlin"))

        assertEquals("31.10.2026", utc)
        assertEquals("01.11.2026", berlin)
    }
}
