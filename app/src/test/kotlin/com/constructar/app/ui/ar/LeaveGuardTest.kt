/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AR screen's exit rule: a single tap can be discarded silently, a run cannot.
 *
 * The failure this locks down was silent in both directions - the work was lost and nothing was
 * said - so the threshold is worth one test even though it is a comparison.
 */
class LeaveGuardTest {

    @Test
    fun `nothing measured leaves without asking`() {
        assertFalse(LeaveGuard.needsConfirmation(0))
    }

    @Test
    fun `a single tap is a mis-tap, not a run`() {
        assertFalse(LeaveGuard.needsConfirmation(1))
    }

    @Test
    fun `two points are a measurement and the exit has to ask`() {
        assertTrue(LeaveGuard.needsConfirmation(2))
        assertTrue(LeaveGuard.needsConfirmation(9))
    }

    @Test
    fun `the threshold is the same one a routable run uses`() {
        assertEquals(2, LeaveGuard.MIN_NODES_FOR_A_RUN)
    }
}
