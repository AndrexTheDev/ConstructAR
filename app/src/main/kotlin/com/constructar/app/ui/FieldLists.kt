/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.DefectItem

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

    /** Defect rows drawn at once. */
    const val DEFECTS_SHOWN: Int = 20

    /**
     * The defect rows the screen draws: **every open one first**, then the resolved ones.
     *
     * The cap was applied to the stored order, which is creation order - newest first - and
     * resolving a defect does not move it. A fortnight's punch list therefore ended with the
     * oldest ten still open *behind* twenty newer resolved ones: the counter above the list said
     * "10 open · 20 resolved" and every row drawn was resolved, while the appendix photo budget
     * (which does sort open first) showed the photo of a defect whose row was not there. The one
     * thing on this screen that costs the operator money is a defect he forgot to fix, so it is
     * the one thing the cap must never hide (finding 127).
     *
     * Within each group the stored order is kept, so both halves stay newest first.
     */
    fun visibleDefects(defects: List<DefectItem>, shown: Int = DEFECTS_SHOWN): List<DefectItem> {
        val open = defects.filterNot { it.resolved }
        val resolved = defects.filter { it.resolved }
        return (open + resolved).take(shown.coerceAtLeast(0))
    }

    /**
     * Rows that exist but are not drawn: never negative, so a list shorter than its cap says
     * nothing at all rather than "-17 more".
     */
    fun hiddenCount(total: Int, shown: Int): Int = (total - shown).coerceAtLeast(0)
}
