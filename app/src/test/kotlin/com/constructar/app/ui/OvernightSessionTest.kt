/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.WorkSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * A shift that runs past midnight.
 *
 * The time table prints the **start's** date once and then the two clock times, so a job from
 * 22:00 to 01:30 came out as `12.05.2026 | 22:00 | 01:30 | 3 h 30 min` - a row that ends an hour
 * and a half *before* it begins, on the page the customer signs, directly beside a duration that
 * contradicts it. The end cell now says "next day" (finding 120).
 *
 * The mark is decided by comparing the two **formatted local dates**, never by arithmetic on
 * 86 400 000 ms: the night a clock springs forward is 23 hours long and the night it falls back is
 * 25, and a zone with a half-hour offset would be off by exactly that. Two of the tests below pin
 * the two cases where the naive subtraction gives the wrong answer.
 */
class OvernightSessionTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalZone: TimeZone

    @Before
    fun rememberDefaults() {
        originalLocale = Locale.getDefault()
        originalZone = TimeZone.getDefault()
        // A 24-hour clock and a zone that changes its offset twice a year.
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
    }

    @After
    fun restoreDefaults() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    /** 2026-05-12 22:00 in Madrid. */
    private val may12At2200 = 1_778_616_000_000L

    /** 2026-05-13 01:30 in Madrid - three and a half hours after the start. */
    private val may13At0130 = 1_778_628_600_000L

    private fun texts() = SiteFileTexts(
        customerTitle = "Kunde",
        customerLabel = "Kunde",
        addressLabel = "Objekt",
        phoneLabel = "Telefon",
        timesTitle = "Arbeitszeit",
        columnDate = "Datum",
        columnStart = "Beginn",
        columnEnd = "Ende",
        columnDuration = "Dauer",
        totalLabel = "Summe",
        runningLabel = "läuft",
        nextDayLabel = "%1\$s (Folgetag)",
        openSessionNote = "Die Uhr läuft noch (%1\$s bisher) - Summe und Kosten unten rechnen sie bis zu diesem Export mit.",
        diaryTitle = "Bautagebuch",
        defectsTitle = "Mängel",
        columnDefect = "Mangel",
        columnSeverity = "Schwere",
        columnStatus = "Status",
        openStatus = "offen",
        resolvedStatus = "erledigt",
        severityLow = "gering",
        severityMedium = "mittel",
        severityHigh = "hoch",
        hoursMinutes = "%1\$d h %2\$d min",
        minutesOnly = "%1\$d min",
        costsTitle = "Kosten",
        labourLabel = "Arbeitslohn",
        materialLabel = "Material",
        netTotalLabel = "Summe netto",
        netNote = "Nettobeträge, ohne MwSt.",
        photosTitle = "Fotodokumentation",
        photoDiaryPrefix = "Bautagebuch",
        photoDefectPrefix = "Mängel",
    )

    private fun project(session: WorkSession) = ProjectRecord(
        id = "p1",
        name = "Wohnung 3. OG",
        createdAtEpochMs = session.startEpochMs,
        updatedAtEpochMs = session.startEpochMs,
        sessions = listOf(session),
        materialLumpSumCents = 0L,
        hourlyRateCents = null,
    )

    private fun endCellOf(startMs: Long, endMs: Long): String =
        buildSiteFileSections(
            project(WorkSession(id = "s1", startEpochMs = startMs, endEpochMs = endMs)),
            texts(),
            nowMs = endMs + 60 * 60_000L,
        ).single { it.title == "Arbeitszeit" }.rows.single()[2]

    @Test
    fun `a shift that ends after midnight says so`() {
        assertTrue(endsOnLaterDay(may12At2200, may13At0130))
        assertEquals("01:30 (Folgetag)", endCellOf(may12At2200, may13At0130))
    }

    @Test
    fun `a shift inside one day is not marked`() {
        val nine = 1_778_569_200_000L
        val ten = 1_778_572_800_000L

        assertFalse(endsOnLaterDay(nine, ten))
        assertEquals("10:00", endCellOf(nine, ten))
    }

    /** Two minutes apart, but the calendar moved: this is the whole point of the mark. */
    @Test
    fun `a start at 23 59 and an end at 00 01 straddles the day`() {
        assertTrue(endsOnLaterDay(1_778_623_140_000L, 1_778_623_260_000L))
        assertEquals("00:01 (Folgetag)", endCellOf(1_778_623_140_000L, 1_778_623_260_000L))
    }

    /** 29 March 2026: Madrid jumps 02:00 -> 03:00, so that local day is 23 hours long. */
    @Test
    fun `the spring change does not make a same-day shift look overnight`() {
        val oneAm = 1_774_742_400_000L
        val halfPastThree = 1_774_747_800_000L

        assertFalse(endsOnLaterDay(oneAm, halfPastThree))
        assertEquals("03:30", endCellOf(oneAm, halfPastThree))
    }

    /**
     * 25 October 2026: Madrid falls back, so that local day is 25 hours long. A session that spans
     * 24 of them is still one day's work and must not be marked - `end - start >= 86 400 000`
     * would have called it overnight.
     */
    @Test
    fun `a 24-hour session inside one local day is not marked`() {
        val halfPastMidnight = 1_792_881_000_000L
        val halfPastEleven = 1_792_967_400_000L

        assertEquals(24 * 60 * 60_000L, halfPastEleven - halfPastMidnight)
        assertFalse(endsOnLaterDay(halfPastMidnight, halfPastEleven))
        assertEquals("23:30", endCellOf(halfPastMidnight, halfPastEleven))
    }

    /** An open clock has no end to date, so the running note stays exactly as it was. */
    @Test
    fun `a running clock is still just running`() {
        val open = WorkSession(id = "open", startEpochMs = may12At2200, endEpochMs = null)

        val end = buildSiteFileSections(project(open), texts(), nowMs = may12At2200 + 90 * 60_000L)
            .single { it.title == "Arbeitszeit" }
            .rows.single()[2]

        assertEquals("läuft", end)
        assertFalse(end.contains("Folgetag"))
    }
}
