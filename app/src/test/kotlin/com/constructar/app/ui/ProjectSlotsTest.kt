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
}
