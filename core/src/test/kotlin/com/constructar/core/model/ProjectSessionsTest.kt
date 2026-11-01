/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Correcting the clock.
 *
 * Finding 44: a work session could be started and stopped and nothing else - there was no way to
 * remove one. A clock forgotten on Friday was still on the invoice, and the only remaining
 * correction was to delete the room and measure it again.
 *
 * The tests pin the two properties that make removal safe: it removes exactly the session named
 * (the room, its runs and its diary stay untouched), and it also works on the *open* session,
 * because cancelling a forgotten timer must not mean stopping it first and carrying the night.
 */
class ProjectSessionsTest {

    private fun project(vararg sessions: WorkSession) = ProjectRecord(
        id = "p1",
        name = "Wohnung 3. OG",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        sessions = sessions.toList(),
    )

    private fun session(id: String, start: Long, end: Long? = null) =
        WorkSession(id = id, startEpochMs = start, endEpochMs = end)

    @Test
    fun `removing a session removes exactly that one`() {
        val project = project(
            session("a", 0L, end = 1_000L),
            session("b", 2_000L, end = 3_000L),
            session("c", 4_000L, end = 5_000L),
        )

        val corrected = project.removeSession("b")

        assertEquals(listOf("a", "c"), corrected.sessions.map { it.id })
        assertEquals(3, project.sessions.size)
    }

    @Test
    fun `a forgotten timer can be cancelled without stopping it first`() {
        val open = session("night", 0L)
        val project = project(open)
        assertTrue(WorkTime.isRunning(project.sessions))

        val corrected = project.removeSession("night")

        assertTrue(corrected.sessions.isEmpty())
        assertNull(WorkTime.runningSession(corrected.sessions))
        assertEquals(0L, WorkTime.totalMillis(corrected.sessions, nowMs = 20 * 3_600_000L))
    }

    @Test
    fun `removing something that is not there changes nothing`() {
        val project = project(session("a", 0L, end = 1_000L))
        // The same object, not a copy: a no-op must not dirty the project and bump its timestamp.
        assertSame(project, project.removeSession("does-not-exist"))
        assertSame(project, project().removeSession("a"))
    }

    @Test
    fun `the room keeps everything else`() {
        val project = project(session("a", 0L, end = 1_000L)).copy(
            diary = listOf(DiaryEntry(id = "e1", createdAtEpochMs = 0L, text = "Rohre verlegt")),
            notes = "Schlüssel beim Nachbarn",
        )

        val corrected = project.removeSession("a")

        assertEquals("Wohnung 3. OG", corrected.name)
        assertEquals(1, corrected.diary.size)
        assertEquals("Schlüssel beim Nachbarn", corrected.notes)
        assertEquals("p1", corrected.id)
    }
}
