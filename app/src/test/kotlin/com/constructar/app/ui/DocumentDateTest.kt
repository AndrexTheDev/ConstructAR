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
 * A **clock time** still follows the device's 12/24-hour convention on purpose: that setting is the
 * owner's, and a trade worker reads the clock the way the phone shows it. These tests pin the date
 * and leave the time alone - the assertions on the time only check that it is there.
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

    private fun texts() = SiteFileTexts(
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

    /** The clock time is still the phone's: only the date is pinned. */
    @Test
    fun `the time of day keeps the device convention`() {
        Locale.setDefault(Locale.GERMANY)
        val row = section("Working time").rows.single()

        assertEquals("12.05.2026", row[0])
        assertEquals("22:00", row[1])

        // Only the clock convention may differ - and only in the clock, never in the day.
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        val american = section("Working time").rows.single()

        assertEquals("12.05.2026", american[0])
        assertTrue(american[1], american[1].contains("10:00"))
        assertTrue(american[1], american[1] != "22:00")
    }
}
