/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field book's exit rule: a diary line typed in the rain is work.
 *
 * The drafts on that screen survive a rotation (`rememberSaveable`) and nothing else. The back
 * arrow and the system back gesture left the screen outright, so the paragraph about the missing
 * Schlitze - or the defect title, or the location it belongs to - went with them without a
 * question, on the one screen whose entire purpose is that the day gets written down (finding 143).
 *
 * Both directions are pinned: a guard that asks when there is nothing to lose is as wrong as one
 * that stays quiet when there is.
 */
class FieldDraftsTest {

    @Test
    fun `an empty field book leaves without asking`() {
        assertFalse(FieldDrafts.unsaved(diaryText = "", defectTitle = "", defectLocation = ""))
        // Whitespace is what a glove leaves behind, not a draft.
        assertFalse(FieldDrafts.unsaved(diaryText = "   ", defectTitle = " ", defectLocation = "\n"))
    }

    @Test
    fun `a typed diary line asks on its own`() {
        assertTrue(
            FieldDrafts.unsaved(
                diaryText = "Schlitze im Wohnzimmer fehlen noch.",
                defectTitle = "",
                defectLocation = "",
            ),
        )
    }

    @Test
    fun `a defect title or its location asks on its own`() {
        assertTrue(FieldDrafts.unsaved(diaryText = "", defectTitle = "Riss über der Tür", defectLocation = ""))
        assertTrue(FieldDrafts.unsaved(diaryText = "", defectTitle = "", defectLocation = "Bad, links"))
    }
}
