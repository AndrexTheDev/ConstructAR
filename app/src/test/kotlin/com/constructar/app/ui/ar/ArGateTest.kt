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
 * Which message the AR screen shows when the camera cannot deliver, and in which order.
 *
 * The order is the point. A failed session is a *consequence* of a missing permission or an
 * unsupported device, and reporting the consequence first would send the operator to the wrong
 * fix - "AR session failed: not initialized" instead of "allow the camera".
 */
class ArGateTest {

    @Test
    fun `nothing blocking shows no gate`() {
        assertEquals(
            ArGate.NONE,
            arGate(hasCameraPermission = true, readinessBlocked = false, sessionFailed = false),
        )
    }

    @Test
    fun `a missing permission outranks a failed session`() {
        assertEquals(
            ArGate.PERMISSION,
            arGate(hasCameraPermission = false, readinessBlocked = true, sessionFailed = true),
        )
    }

    @Test
    fun `readiness outranks a failed session`() {
        assertEquals(
            ArGate.READINESS,
            arGate(hasCameraPermission = true, readinessBlocked = true, sessionFailed = true),
        )
    }

    @Test
    fun `a failed session is a gate of its own`() {
        assertEquals(
            ArGate.SESSION_FAILED,
            arGate(hasCameraPermission = true, readinessBlocked = false, sessionFailed = true),
        )
    }

    @Test
    fun `retry is offered only while nothing was measured`() {
        assertTrue(canRetrySession(0))
        assertTrue(canRetrySession(1))
        assertFalse(canRetrySession(2))
        assertFalse(canRetrySession(9))
    }
}
