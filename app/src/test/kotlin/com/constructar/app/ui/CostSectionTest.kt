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

    /**
     * The phone warns about a clock left running; the protocol used to say nothing while its
     * total kept growing. A customer reading "2 h 15 min" on a job that ended yesterday has no
     * way to know that number is still counting, so the document says it - and the sentence is
     * printed only while a session is actually open.
     */
    @Test
    fun `a clock that is still running is named in the protocol`() {
        val start = 1_767_225_600_000L
        val open = WorkSession(id = "open", startEpochMs = start, endEpochMs = null)
        val now = start + 2 * 60 * 60_000L + 15 * 60_000L

        val times = buildSiteFileSections(project(sessions = listOf(open)), texts(), nowMs = now)
            .first { it.title == "Arbeitszeit" }

        assertEquals(1, times.paragraphs.size)
        // The same duration the table prints, and the words that say the number keeps counting.
        assertTrue(times.paragraphs[0], times.paragraphs[0].contains("2 h 15 min"))
        assertTrue(times.paragraphs[0], times.paragraphs[0].contains("Export"))
        // The end column already says "läuft" - the sentence must not contradict it.
        assertTrue(times.rows.single()[2], times.rows.single()[2] == "läuft")

        // And the cost section keeps counting the same time, so the two agree.
        val cost = buildCostSection(project(sessions = listOf(open)), 4_800, texts(), nowMs = now)
        requireNotNull(cost)
        assertTrue(cost.paragraphs.toString(), cost.paragraphs[0].contains("2 h 15 min"))
    }

    @Test
    fun `a finished job carries no running-session sentence`() {
        val times = buildSiteFileSections(project(), texts(), nowMs = 4_000_000_000_000L)
            .first { it.title == "Arbeitszeit" }

        assertTrue(times.paragraphs.toString(), times.paragraphs.isEmpty())
    }

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
    fun `a job shorter than a minute has no cost block at all`() {
        // Forty seconds of work, no material: there is nothing to bill, so there is nothing to
        // print. A "0,00 €" block would only ask the customer whether the job was free.
        val brief = WorkSession(id = "brief", startEpochMs = 1_767_225_600_000L, endEpochMs = 1_767_225_600_000L + 40_000L)
        assertNull(buildCostSection(project(sessions = listOf(brief)), 4_800, texts()))
    }

    @Test
    fun `a sub-minute session is not printed as a labour line`() {
        // With material on the job the block exists - but a "0 min x 48,00 €" line whose factors
        // make nothing does not belong on a document the customer signs.
        val brief = WorkSession(id = "brief", startEpochMs = 1_767_225_600_000L, endEpochMs = 1_767_225_600_000L + 40_000L)
        val section = buildCostSection(project(sessions = listOf(brief), materialCents = 25_000L), 4_800, texts())
        requireNotNull(section)
        assertEquals(2, section.paragraphs.size)
        assertTrue(section.paragraphs.any { it.contains(MoneyFormatter.format(25_000)) })
        // org.junit's assertTrue takes the message first - the other order does not compile.
        assertTrue(section.paragraphs.toString(), section.paragraphs.none { it.contains("0 min") })
        assertTrue(section.paragraphs.last().contains(MoneyFormatter.format(25_000)))
    }

    @Test
    fun `a project rate overrides the firm rate`() {
        // 60,00 an hour for a warranty job: one hour bills 60,00, not the firm's default.
        val section = buildCostSection(project(rateCents = 6_000), 6_000, texts(), nowMs = 4_000_000_000_000L)
        requireNotNull(section)
        assertTrue(section.paragraphs.toString(), section.paragraphs.last().contains(MoneyFormatter.format(6_000)))
    }

    @Test
    fun `the labour line bills the minutes the time table adds up to`() {
        // 2 h 7 min 42 s and a 40-second interruption. The rows print "2 h 7 min" and "0 min";
        // a total truncated from the raw 2 h 8 min 22 s printed "2 h 8 min" and billed 102,40 €,
        // one minute more than the column the customer adds up (finding 81).
        val long = WorkSession(
            id = "s1",
            startEpochMs = 1_767_225_600_000L,
            endEpochMs = 1_767_225_600_000L + 2 * 3_600_000L + 7 * 60_000L + 42_000L,
        )
        val brief = WorkSession(
            id = "s2",
            startEpochMs = 1_767_235_600_000L,
            endEpochMs = 1_767_235_600_000L + 40_000L,
        )

        val section = buildCostSection(project(sessions = listOf(long, brief)), 4_800, texts())
        requireNotNull(section)

        val labour = section.paragraphs.first { it.startsWith("Arbeitslohn") }
        assertTrue(labour, labour.contains("2 h 7 min"))
        assertTrue(labour, !labour.contains("2 h 8 min"))
        assertEquals(MoneyFormatter.format(10_160), section.paragraphs.last().substringAfter(": "))
    }

    @Test
    fun `the time table's total is the sum of its rows`() {
        // Three 59-second interruptions: every row prints "0 min", so the total must as well.
        val briefs = (0 until 3).map { index ->
            val start = 1_767_225_600_000L + index * 10_000L
            WorkSession(id = "b$index", startEpochMs = start, endEpochMs = start + 59_000L)
        }

        val times = buildSiteFileSections(project(sessions = briefs), texts(), nowMs = 4_000_000_000_000L)
            .single { it.title == "Arbeitszeit" }

        assertEquals(3, times.rows.size)
        assertTrue(times.rows.toString(), times.rows.all { it.last() == "0 min" })
        assertEquals("Summe: 0 min", times.note)
    }
}
