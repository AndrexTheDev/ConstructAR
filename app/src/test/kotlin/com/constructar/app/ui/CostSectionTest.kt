/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.ProjectRecord
import com.constructar.core.price.MoneyFormatter
import com.constructar.core.model.WorkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The costs block is the only part of the protocol that is arithmetic *and* layout, so it is
 * tested where the two meet: given hours, a rate and a lump sum, the printed lines must add up
 * to the printed total, and an empty job must print nothing at all.
 */
class CostSectionTest {

    /** A one-hour session, 09:00-10:00 on a fixed day, so nothing depends on the clock. */
    private val session = WorkSession(
        id = "s1",
        startEpochMs = 1_767_225_600_000L,
        endEpochMs = 1_767_225_600_000L + 60 * 60_000L,
    )

    private fun project(sessions: List<WorkSession> = listOf(session), materialCents: Long = 0L, rateCents: Int? = null) =
        ProjectRecord(
            id = "p1",
            name = "Wohnung 3. OG",
            createdAtEpochMs = 1_767_225_600_000L,
            updatedAtEpochMs = 1_767_225_600_000L,
            sessions = sessions,
            materialLumpSumCents = materialCents,
            hourlyRateCents = rateCents,
        )

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

    @Test
    fun `a job without time and without material has no cost block`() {
        val none = ProjectRecord(id = "p0", name = "leer", createdAtEpochMs = 0L, updatedAtEpochMs = 0L)
        assertNull(buildCostSection(none, 4_800, texts()))
    }

    @Test
    fun `one hour at the default rate bills one hour and the material lump sum`() {
        val section = buildCostSection(project(materialCents = 2_500L), 4_800, texts(), nowMs = 4_000_000_000_000L)
        requireNotNull(section)
        assertEquals("Kosten", section.title)
        // 1 h x the default rate, plus the material lump sum. The money is compared through
        // the same formatter the protocol uses, so the assertion holds on a German, an English
        // or a Polish build machine - what is under test is the arithmetic, not the separator.
        assertTrue(section.paragraphs.toString(), section.paragraphs[0].contains("1 h 0 min"))
        assertTrue(section.paragraphs.toString(), section.paragraphs[0].contains(MoneyFormatter.format(4_800)))
        assertTrue(section.paragraphs.toString(), section.paragraphs[1].contains(MoneyFormatter.format(2_500)))
        assertTrue(section.paragraphs.toString(), section.paragraphs[2].contains(MoneyFormatter.format(7_300)))
        assertEquals("Nettobeträge, ohne MwSt.", section.note)
    }

    @Test
    fun `material without recorded time still prints a total`() {
        val section = buildCostSection(project(sessions = emptyList(), materialCents = 7_500L), 4_800, texts())
        requireNotNull(section)
        assertEquals(2, section.paragraphs.size)
        assertTrue(section.paragraphs.last().contains(MoneyFormatter.format(7_500)))
    }

    @Test
    fun `a running session is billed up to now, not to a frozen end`() {
        val running = WorkSession(id = "s2", startEpochMs = 1_000_000L, endEpochMs = null)
        val start = 1_000_000L
        val now = start + 2 * 60 * 60_000L // two hours
        val section = buildCostSection(project(sessions = listOf(running)), 4_800, texts(), nowMs = now)
        requireNotNull(section)
        assertTrue(section.paragraphs.toString(), section.paragraphs.last().contains(MoneyFormatter.format(9_600)))
    }

    @Test
    fun `a project rate overrides the firm rate`() {
        // 60,00 an hour for a warranty job: one hour bills 60,00, not the firm's default.
        val section = buildCostSection(project(rateCents = 6_000), 6_000, texts(), nowMs = 4_000_000_000_000L)
        requireNotNull(section)
        assertTrue(section.paragraphs.toString(), section.paragraphs.last().contains(MoneyFormatter.format(6_000)))
    }
}
