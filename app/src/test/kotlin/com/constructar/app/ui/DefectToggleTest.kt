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
    fun `the two directions do not share a sentence`() {
        // The old string said both ("Mark resolved / open again"), which is why a screen reader
        // could not tell the operator what the tap was about to do.
        assertNotEquals(
            DefectToggle.labelRes(DefectToggle.Action.RESOLVE),
            DefectToggle.labelRes(DefectToggle.Action.REOPEN),
        )
    }
}
