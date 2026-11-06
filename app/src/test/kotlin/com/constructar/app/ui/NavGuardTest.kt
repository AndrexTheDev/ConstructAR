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
 * The black screen after "back to the list" was a second pop of a destination that was already on
 * its way out (finding 259). These are the four answers the guard can give.
 */
class NavGuardTest {

    @Test
    fun `the screen on display may leave`() {
        assertTrue(NavGuard.shouldLeave(currentRoute = "project", ownRoute = "project"))
    }

    @Test
    fun `a screen that is already being transitioned away may not`() {
        // The pop has happened, the controller shows the list, the project destination is still
        // composed for the animation. Popping here removed the list itself.
        assertFalse(NavGuard.shouldLeave(currentRoute = "home", ownRoute = "project"))
    }

    @Test
    fun `nothing on display is nobody's business`() {
        // After a process restore the controller can briefly have no current destination.
        assertFalse(NavGuard.shouldLeave(currentRoute = null, ownRoute = "project"))
    }

    @Test
    fun `the measuring screen is guarded by the same rule`() {
        assertFalse(NavGuard.shouldLeave(currentRoute = "project", ownRoute = "measure"))
        assertTrue(NavGuard.shouldLeave(currentRoute = "measure", ownRoute = "measure"))
    }
}
