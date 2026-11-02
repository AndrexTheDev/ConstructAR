/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.DefectItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many rows the field book is not drawing.
 *
 * The three lists are capped so a phone does not lay out a fortnight of rows in one column, and
 * the cap is fine. What was not fine is that it was silent: the lists are stored newest first, so
 * `take(20)` hides the *oldest* entries, and a list that simply stops looks exactly like a list
 * with nothing more in it. An operator looking for the note he wrote on Monday could not tell
 * whether the app had lost it or was holding it back.
 *
 * These cases pin the number the screen prints under the list, not the sentence around it.
 */
class FieldListsTest {

    @Test
    fun `a list that fits says nothing`() {
        assertEquals(0, FieldLists.hiddenCount(total = 0, shown = FieldLists.DIARY_SHOWN))
        assertEquals(0, FieldLists.hiddenCount(total = 7, shown = FieldLists.SESSIONS_SHOWN))
        // Exactly at the cap is still nothing hidden: the twentieth entry is on screen.
        assertEquals(0, FieldLists.hiddenCount(total = 20, shown = FieldLists.DEFECTS_SHOWN))
    }

    @Test
    fun `a long list says how many older rows it left out`() {
        assertEquals(1, FieldLists.hiddenCount(total = 21, shown = FieldLists.DIARY_SHOWN))
        assertEquals(27, FieldLists.hiddenCount(total = 47, shown = FieldLists.DIARY_SHOWN))
        assertEquals(5, FieldLists.hiddenCount(total = 17, shown = FieldLists.SESSIONS_SHOWN))
    }

    /**
     * The cap must never hide a defect that is still open.
     *
     * The list is drawn in creation order, newest first, and resolving a defect does not move it,
     * so a fortnight's punch list put the oldest - and still open - items *behind* twenty newer
     * resolved ones: the counter said "10 open · 20 resolved" and every row drawn was resolved,
     * while the photo budget (which sorts open first) showed the picture of a defect whose row was
     * not there (finding 127).
     */
    @Test
    fun `an open defect is drawn before a resolved one, however old it is`() {
        val oldest = defect("a", createdAt = 1_000L, resolved = false)
        val newer = (0 until 25).map { index ->
            defect("r$index", createdAt = 2_000L + index, resolved = true)
        }

        val drawn = FieldLists.visibleDefects(listOf(oldest) + newer)

        assertEquals(FieldLists.DEFECTS_SHOWN, drawn.size)
        assertEquals(oldest.id, drawn.first().id)
        assertTrue(drawn.toString(), drawn.first().resolved.not())
    }

    @Test
    fun `both halves keep the newest first`() {
        val defects = listOf(
            defect("open-new", createdAt = 9L, resolved = false),
            defect("open-old", createdAt = 3L, resolved = false),
            defect("done-new", createdAt = 8L, resolved = true),
            defect("done-old", createdAt = 2L, resolved = true),
        )

        val drawn = FieldLists.visibleDefects(defects)

        assertEquals(listOf("open-new", "open-old", "done-new", "done-old"), drawn.map { it.id })
    }

    @Test
    fun `a list that fits is drawn whole, and the tail count still matches`() {
        val defects = listOf(
            defect("a", createdAt = 3L, resolved = true),
            defect("b", createdAt = 2L, resolved = false),
        )

        val drawn = FieldLists.visibleDefects(defects)

        assertEquals(2, drawn.size)
        assertEquals(0, FieldLists.hiddenCount(drawn.size, FieldLists.DEFECTS_SHOWN))
        // Fifty defects on the job: twenty are drawn, thirty are named under the list.
        assertEquals(30, FieldLists.hiddenCount(50, FieldLists.DEFECTS_SHOWN))
    }

    private fun defect(id: String, createdAt: Long, resolved: Boolean) = DefectItem(
        id = id,
        createdAtEpochMs = createdAt,
        title = id,
        resolved = resolved,
    )

    @Test
    fun `the caps are the ones the screen draws`() {
        // A week of site days fits without scrolling past the card, and the protocol - not the
        // phone - is the reader for everything older.
        assertEquals(12, FieldLists.SESSIONS_SHOWN)
        assertEquals(20, FieldLists.DIARY_SHOWN)
        assertEquals(20, FieldLists.DEFECTS_SHOWN)
    }
}
