/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.licence.Entitlements
import com.constructar.core.licence.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free plan's one room, counted over what is on the phone rather than over what parses.
 *
 * The room list shows only the projects that could be read; a damaged file is kept and reported
 * separately, which is the honest thing to do with data the app does not understand. The limit
 * then used that shorter list, so a free install whose only room file was damaged was offered
 * "New room" and ended up with two rooms - one of them invisible (finding 126).
 *
 * Finding 126 fixed the *button*, which draws from that same shorter list, and left the two
 * places that actually create a room: `createProject`'s gate and the room count a restore is
 * granted. Both counted readable projects only, so a free installation whose one room file was
 * damaged could still import last week's backup from the backup card and hold two room files
 * (finding 149). Every decision about the plan's rooms is asked here now.
 */
class ProjectSlotsTest {

    private val free = Entitlements(plan = Plan.FREE)
    private val pro = Entitlements(plan = Plan.LIFETIME)

    @Test
    fun `a readable room and an unreadable file both take a slot`() {
        assertEquals(0, ProjectSlots.used(readable = 0, unreadable = 0))
        assertEquals(1, ProjectSlots.used(readable = 1, unreadable = 0))
        assertEquals(1, ProjectSlots.used(readable = 0, unreadable = 1))
        assertEquals(3, ProjectSlots.used(readable = 2, unreadable = 1))
    }

    @Test
    fun `the free plan stops at one room however that room is stored`() {
        assertTrue(free.allowsAnotherProject(ProjectSlots.used(readable = 0, unreadable = 0)))
        assertFalse(free.allowsAnotherProject(ProjectSlots.used(readable = 1, unreadable = 0)))
        assertFalse(free.allowsAnotherProject(ProjectSlots.used(readable = 0, unreadable = 1)))
    }

    @Test
    fun `a licence does not count them at all`() {
        assertTrue(pro.allowsAnotherProject(ProjectSlots.used(readable = 40, unreadable = 3)))
    }

    @Test
    fun `a restore is granted no room when a damaged file already holds the slot`() {
        // The gate behind the button - the one a share intent or the backup card reaches.
        assertFalse(ProjectSlots.allowsAnother(free, readable = 0, unreadable = 1))
        assertEquals(0, ProjectSlots.remaining(free, readable = 0, unreadable = 1))
        // Where finding 126's scenario actually bit: the restore's allowance was the difference
        // to the *readable* list, which was empty.
        assertEquals(0, ProjectSlots.remaining(free, readable = 1, unreadable = 0))
        assertEquals(0, ProjectSlots.remaining(free, readable = 0, unreadable = 2))
    }

    @Test
    fun `an empty free install is granted exactly one room`() {
        assertTrue(ProjectSlots.allowsAnother(free, readable = 0, unreadable = 0))
        assertEquals(1, ProjectSlots.remaining(free, readable = 0, unreadable = 0))
    }

    @Test
    fun `a licence borrows as many rooms as it likes`() {
        assertEquals(Int.MAX_VALUE - 43, ProjectSlots.remaining(pro, readable = 40, unreadable = 3))
        assertTrue(ProjectSlots.allowsAnother(pro, readable = 40, unreadable = 3))
    }
}
