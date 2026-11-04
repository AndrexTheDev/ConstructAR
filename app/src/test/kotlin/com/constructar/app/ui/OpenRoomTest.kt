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
 * When the app decides it is no longer standing in a room.
 *
 * The room id is what makes a cold start walk back into the room the process died in, which is the
 * feature; the same id walking back into a room the operator *left* is the bug (finding 134). The
 * two cases look identical in the state - a room is there - and differ only in whether it has been
 * shown yet, so the decision lives in one function that both can be tested against.
 */
class OpenRoomTest {

    @Test
    fun `arriving at the list closes a room that was left by the back gesture`() {
        // Shown already: the operator is coming back to the list, so the room is not open.
        assertTrue(OpenRoom.closesOnHome(hasOpenProject = true, resumePending = false))
    }

    @Test
    fun `a quick resume that has not been shown yet is not cancelled`() {
        // The navigation to the room is one frame away; closing here would swallow the resume.
        assertFalse(OpenRoom.closesOnHome(hasOpenProject = true, resumePending = true))
    }

    @Test
    fun `a cold start that resumes nothing has nothing to close`() {
        assertFalse(OpenRoom.closesOnHome(hasOpenProject = false, resumePending = false))
    }
}
