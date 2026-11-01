/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.Assert.assertEquals
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

    @Test
    fun `the caps are the ones the screen draws`() {
        // A week of site days fits without scrolling past the card, and the protocol - not the
        // phone - is the reader for everything older.
        assertEquals(12, FieldLists.SESSIONS_SHOWN)
        assertEquals(20, FieldLists.DIARY_SHOWN)
        assertEquals(20, FieldLists.DEFECTS_SHOWN)
    }
}
