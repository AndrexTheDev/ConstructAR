/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock is the one piece of the site file that costs money when it is wrong, so it is
 * tested on the JVM rather than trusted to the UI.
 */
class WorkTimeTest {

    private fun session(start: Long, end: Long? = null) = WorkSession(id = "s$start", startEpochMs = start, endEpochMs = end)

    @Test
    fun `a closed session contributes exactly its duration`() {
        val sessions = listOf(session(1_000L, 1_000L + 90 * WorkTime.MINUTE_MS))
        assertEquals(90 * WorkTime.MINUTE_MS, WorkTime.totalMillis(sessions, nowMs = 9_999_999L))
    }

    @Test
    fun `a running session is counted up to now`() {
        val start = 5_000L
        val now = start + 125 * WorkTime.MINUTE_MS
        val sessions = listOf(session(start))
        assertTrue(WorkTime.isRunning(sessions))
        assertEquals(125 * WorkTime.MINUTE_MS, WorkTime.totalMillis(sessions, now))
    }

    @Test
    fun `a clock that went backwards never produces negative hours`() {
        val sessions = listOf(session(10_000L, end = 4_000L))
        assertEquals(0L, WorkTime.totalMillis(sessions, nowMs = 20_000L))
    }

    @Test
    fun `hours and minutes are split the way a timesheet is written`() {
        val millis = 2 * 3_600_000L + 7 * WorkTime.MINUTE_MS + 42_000L
        assertEquals(2L, WorkTime.hours(millis))
        assertEquals(7L, WorkTime.minutes(millis))
    }

    @Test
    fun `the billed quantity is the whole minutes the page prints`() {
        // 2 h 7 min 42 s is printed as "2 h 7 min" and priced as 127 minutes - the same number,
        // so a customer with a calculator reaches the total that is on the page.
        val millis = 2 * 3_600_000L + 7 * WorkTime.MINUTE_MS + 42_000L
        assertEquals(127L, WorkTime.billableMinutes(millis))
        assertEquals(2L, WorkTime.billableMinutes(millis) / 60L)
        assertEquals(7L, WorkTime.billableMinutes(millis) % 60L)
    }

    @Test
    fun `a remainder under a minute is not a minute`() {
        assertEquals(0L, WorkTime.billableMinutes(59_999L))
        assertEquals(1L, WorkTime.billableMinutes(60_000L))
        assertEquals(0L, WorkTime.billableMinutes(-5_000L))
    }

    @Test
    fun `stopping the clock closes the open session and keeps the closed ones`() {
        val project = ProjectRecord.empty(id = "p", name = "Keller")
            .startSession(session(0L))
            .stopSession(nowMs = 60 * WorkTime.MINUTE_MS)

        assertFalse(WorkTime.isRunning(project.sessions))
        assertEquals(60 * WorkTime.MINUTE_MS, project.totalWorkMillis())
    }

    @Test
    fun `a clock that stepped backwards does not close a session before it began`() {
        // Start at 09:00, the phone is corrected to 08:40 while the session runs. The stored end
        // must not be earlier than the start: the protocol would otherwise show a run of work that
        // ends before it begins, on a page the customer signs.
        val start = 1_767_229_200_000L
        val project = ProjectRecord.empty(id = "p", name = "Keller")
            .startSession(session(start))
            .stopSession(nowMs = start - 20 * WorkTime.MINUTE_MS)

        val closed = project.sessions.single()
        assertEquals(start, closed.endEpochMs)
        assertEquals(0L, project.totalWorkMillis(nowMs = start))
    }

    @Test
    fun `a second start does not open a second clock`() {
        val project = ProjectRecord.empty(id = "p", name = "Keller")
            .startSession(session(0L))
            .startSession(session(1_000L))

        assertEquals(1, project.sessions.size)
    }

    @Test
    fun `stopping when nothing runs is a no-op`() {
        val project = ProjectRecord.empty(id = "p", name = "Keller")
        assertEquals(project, project.stopSession(nowMs = 1L))
        assertNull(WorkTime.runningSession(project.sessions))
    }

    @Test
    fun `a clock left running overnight is flagged, not billed quietly`() {
        val start = 1_000L
        val sessions = listOf(session(start))

        // Eight hours in: a long shift, not a mistake.
        assertNull(WorkTime.overdueSession(sessions, start + 8 * 3_600_000L))
        // Twelve hours exactly is still a shift somebody may have worked.
        assertNull(WorkTime.overdueSession(sessions, start + WorkTime.OVERDUE_SESSION_MS))
        // One minute past it: the question is asked.
        val overdue = WorkTime.overdueSession(sessions, start + WorkTime.OVERDUE_SESSION_MS + WorkTime.MINUTE_MS)
        assertEquals(sessions.single(), overdue)
    }

    @Test
    fun `a stopped session is never overdue, however long it was`() {
        // A corrected, long shift is a fact; only a clock that is still running can be forgotten.
        val sessions = listOf(session(0L, end = 20 * 3_600_000L))
        assertNull(WorkTime.overdueSession(sessions, 40 * 3_600_000L))
        assertNull(WorkTime.overdueSession(emptyList(), 40 * 3_600_000L))
    }

    @Test
    fun `the running session is the open one, not the newest by timestamp`() {
        val closed = session(0L, end = 100L)
        val open = session(200L)
        val sessions = listOf(closed, open)
        assertEquals(open, WorkTime.runningSession(sessions))
    }

    @Test
    fun `the time table's total is the sum of its rows`() {
        // Three interruptions of 59 s each - the shape of a job that keeps walking in and out of a
        // cellar. Each row prints "0 min", so the total has to print "0 min" too; it used to print
        // "2 min" and the labour line billed those two minutes (finding 81).
        val sessions = (0 until 3).map { session(it * 10_000L, end = it * 10_000L + 59_000L) }

        assertEquals(0L, WorkTime.billableMinutes(sessions, nowMs = 10_000_000L))
        assertEquals(0L, WorkTime.billableMillis(sessions, nowMs = 10_000_000L))
    }

    @Test
    fun `a long session and a sub-minute one keep the long one's minutes`() {
        // The same rule the other way round: a 40-second interruption must not round the job up
        // from its printed 2 h 7 min to the 2 h 8 min a raw total would give.
        val twoHoursAndChange = session(0L, end = 2 * 3_600_000L + 7 * WorkTime.MINUTE_MS + 42_000L)
        val brief = session(10_000_000L, end = 10_000_000L + 40_000L)
        val sessions = listOf(twoHoursAndChange, brief)

        assertEquals(127L, WorkTime.billableMinutes(sessions, nowMs = 20_000_000L))
        assertEquals(127 * WorkTime.MINUTE_MS, WorkTime.billableMillis(sessions, nowMs = 20_000_000L))
    }
}
