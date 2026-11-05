/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which way the button on a defect row moves it.
 *
 * One `Check` icon on both states meant the tick said "done" on a defect that *was* done, and
 * tapping it reopened the defect - silently, on the row he is most likely to tap while reading the
 * punch list, and the reopened defect then prints as outstanding on the document the customer signs
 * (finding 136). The icon and the sentence now follow the action, and the action is decided here.
 */
class DefectToggleTest {

    @Test
    fun `an open defect offers to close it`() {
        assertEquals(DefectToggle.Action.RESOLVE, DefectToggle.action(resolved = false))
        assertEquals(R.string.field_defect_resolve, DefectToggle.labelRes(DefectToggle.Action.RESOLVE))
    }

    @Test
    fun `a resolved defect offers to open it again`() {
        assertEquals(DefectToggle.Action.REOPEN, DefectToggle.action(resolved = true))
        assertEquals(R.string.field_defect_reopen, DefectToggle.labelRes(DefectToggle.Action.REOPEN))
    }

    @Test
    fun `the button says the state as well as the action`() {
        // The action is what the tap does - "Mark resolved" on an open defect. The state is what
        // the defect *is*, and it is the half a TalkBack user could not hear at all (finding 211).
        assertEquals(R.string.pdf_status_open, DefectToggle.stateRes(resolved = false))
        assertEquals(R.string.pdf_status_resolved, DefectToggle.stateRes(resolved = true))
    }

    @Test
    fun `the state and the action are never the same sentence`() {
        // If they were, the button would announce one word twice and the operator would learn
        // nothing from the second.
        assertNotEquals(
            DefectToggle.stateRes(resolved = false),
            DefectToggle.labelRes(DefectToggle.action(resolved = false)),
        )
        assertNotEquals(
            DefectToggle.stateRes(resolved = true),
            DefectToggle.labelRes(DefectToggle.action(resolved = true)),
        )
        assertNotEquals(
            DefectToggle.stateRes(resolved = true),
            DefectToggle.stateRes(resolved = false),
        )
    }

    @Test
    fun `the two directions do not share a sentence`() {
        // The old string said both ("Mark resolved / open again"), which is why a screen reader
        // could not tell the operator what the tap was about to do.
        assertNotEquals(
            DefectToggle.labelRes(DefectToggle.Action.RESOLVE),
            DefectToggle.labelRes(DefectToggle.Action.REOPEN),
        )
    }
}
