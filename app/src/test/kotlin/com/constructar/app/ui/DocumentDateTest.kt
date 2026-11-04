/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.DefectItem
import com.constructar.core.model.DiaryEntry
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.WorkSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * The dates printed in the site file, under a phone that is not set to German.
 *
 * `DateFormat.getDateInstance` picks its **calendar** from the locale, not only its pattern, and it
 * translates the month name. On a Thai phone the protocol's date column therefore read
 * `12 พ.ค. 2569` - the Buddhist year, 543 years in the future, in the document the customer signs -
 * and on a Polish phone `12 maj 2026` in the middle of an English protocol. The home screen and the
 * paywall had been pinned to `dd.MM.yyyy` long before, so the same day showed up in two formats one
 * tap apart (finding 121).
 *
 * A **clock time** follows the phone's 12/24-hour **setting** on purpose: that setting is the
 * owner's, and a trade worker reads the clock the way the phone shows it. What it does not follow
 * any more is the phone's *language* - neither the convention nor the digits (finding 148) - so the
 * time assertions here pass the pattern explicitly and hold the language fixed.
 */
class DocumentDateTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalZone: TimeZone

    /** 2026-05-12 22:00 in Madrid; the same instant in every test. */
    private val may12At2200 = 1_778_616_000_000L

    @Before
    fun rememberDefaults() {
        originalLocale = Locale.getDefault()
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
    }

    @After
    fun restoreDefaults() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    private fun texts(timePattern: String = "HH:mm") = SiteFileTexts(
        customerTitle = "Customer",
        customerLabel = "Customer",
        addressLabel = "Site",
        phoneLabel = "Phone",
        timesTitle = "Working time",
        columnDate = "Date",
        columnStart = "From",
        columnEnd = "To",
        columnDuration = "Duration",
        totalLabel = "Total",
        runningLabel = "running",
        nextDayLabel = "%1\$s (next day)",
        openSessionNote = "The clock is still running (%1\$s so far) - the totals below count it until this export.",
        diaryTitle = "Site diary",
        defectsTitle = "Defects",
        columnDefect = "Defect",
        columnSeverity = "Severity",
        columnStatus = "Status",
        openStatus = "open",
        resolvedStatus = "resolved",
        severityLow = "low",
        severityMedium = "medium",
        severityHigh = "high",
        hoursMinutes = "%1\$d h %2\$d min",
        minutesOnly = "%1\$d min",
        costsTitle = "Costs",
        labourLabel = "Labour",
        materialLabel = "Material",
        netTotalLabel = "Net total",
        netNote = "Net amounts, excluding VAT.",
        photosTitle = "Photos",
        photoDiaryPrefix = "Diary",
        photoDefectPrefix = "Defect",
        timePattern = timePattern,
    )

    private val project = ProjectRecord(
        id = "p1",
        name = "Flat 3",
        createdAtEpochMs = 1_778_616_000_000L,
        updatedAtEpochMs = 1_778_616_000_000L,
        sessions = listOf(WorkSession(id = "s1", startEpochMs = 1_778_616_000_000L, endEpochMs = 1_778_619_600_000L)),
        diary = listOf(DiaryEntry(id = "d1", createdAtEpochMs = 1_778_616_000_000L, text = "Wall opened.")),
        defects = listOf(DefectItem(id = "f1", createdAtEpochMs = 1_778_616_000_000L, title = "Crack")),
    )

    private fun section(title: String) =
        buildSiteFileSections(project, texts(), nowMs = 1_778_620_000_000L).single { it.title == title }

    @Test
    fun `a Buddhist-calendar phone does not age the protocol by 543 years`() {
        Locale.setDefault(Locale.forLanguageTag("th-TH"))

        val date = section("Working time").rows.single()[0]

        assertEquals("12.05.2026", date)
        assertTrue(date, !date.contains("2569"))
    }

    @Test
    fun `a Polish phone does not translate the month inside an English document`() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))

        val date = section("Working time").rows.single()[0]

        assertEquals("12.05.2026", date)
        assertTrue(date, !date.any { it.isLetter() })
    }

    @Test
    fun `the defect row and the diary line use the same pinned date`() {
        Locale.setDefault(Locale.forLanguageTag("ja-JP-u-ca-japanese"))

        val defect = section("Defects").rows.single()[0]
        val diary = section("Site diary").paragraphs.single()

        assertEquals("12.05.2026", defect)
        assertTrue(diary, diary.startsWith("12.05.2026,"))
        assertTrue(diary, diary.contains("Wall opened."))
    }

    /** The clock convention comes from the phone's setting; the language may not move it. */
    @Test
    fun `the time of day follows the setting, not the language`() {
        // A German phone whose owner chose 24-hour time.
        Locale.setDefault(Locale.GERMANY)
        val german = buildSiteFileSections(project, texts("HH:mm"), nowMs = 1_778_620_000_000L)
            .single { it.title == "Working time" }.rows.single()

        assertEquals("12.05.2026", german[0])
        assertEquals("22:00", german[1])

        // The same phone set to English: the setting still decides, so the clock does not move.
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        val englishSpeaking = buildSiteFileSections(project, texts("HH:mm"), nowMs = 1_778_620_000_000L)
            .single { it.title == "Working time" }.rows.single()

        assertEquals("22:00", englishSpeaking[1])

        // And a 12-hour setting prints 12-hour time even on a language that never does.
        Locale.setDefault(Locale.GERMANY)
        val twelveHour = buildSiteFileSections(project, texts("h:mm a"), nowMs = 1_778_620_000_000L)
            .single { it.title == "Working time" }.rows.single()

        assertEquals("10:00 PM", twelveHour[1])
    }
}
