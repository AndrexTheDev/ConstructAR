/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * How much of the site file a phone screen draws, and how much it does not.
 *
 * The field book caps every list - twelve sessions, twenty diary entries, twenty defects - and
 * the cap itself is right: a job that runs for a fortnight has more rows than a phone should
 * decode and lay out in one column. What was wrong is that the cap was **silent**. The lists are
 * stored newest first, so `take(20)` hides the *oldest* rows, and a screen that simply stops
 * looks exactly like a screen with nothing more in it: the operator scrolls to the bottom of his
 * diary, does not find the note he wrote on Monday, and has no way of telling whether the app
 * lost it or is holding it back.
 *
 * The protocol prints every row, so the honest sentence is short: this many older entries exist
 * and are not drawn here. [hiddenCount] is that number, and the caps live here so the screen and
 * the sentence cannot drift apart.
 */
object FieldLists {

    /** Session rows drawn at once. Enough for a week of site days, one screenful of scrolling. */
    const val SESSIONS_SHOWN: Int = 12

    /** Diary rows drawn at once. The protocol's appendix is the reader for the long tail. */
    const val DIARY_SHOWN: Int = 20

    /** Defect rows drawn at once. An open defect is never sorted last, so the budget is safe. */
    const val DEFECTS_SHOWN: Int = 20

    /**
     * Rows that exist but are not drawn: never negative, so a list shorter than its cap says
     * nothing at all rather than "-17 more".
     */
    fun hiddenCount(total: Int, shown: Int): Int = (total - shown).coerceAtLeast(0)
}
