/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.arengine.session.ArReadiness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which readiness answers the gate can answer with "Open Play Store" (finding 146).
 *
 * The distinction is the whole finding: a fixable answer used to be shown with the same single
 * "Back" button as an unfixable one, so "install ARCore from the Play Store" arrived without a
 * way to the Play Store. Only answers the operator can act on may offer the button - offering it
 * for a device that has no ARCore support would send him to a store page that cannot help him.
 */
class ArReadinessActionTest {

    @Test
    fun `the three fixable answers offer the play store`() {
        assertTrue(ArReadinessAction.offersInstall(ArReadiness.NeedsInstall("apk too old")))
        assertTrue(ArReadinessAction.offersInstall(ArReadiness.UserDeclined))
        assertTrue(ArReadinessAction.offersInstall(ArReadiness.InstallRequested))
    }

    @Test
    fun `an unsupported device is not sent to the store`() {
        assertFalse(ArReadinessAction.offersInstall(ArReadiness.Unsupported("no ARCore hardware")))
    }

    @Test
    fun `unknown failures keep the plain back button`() {
        // Nothing is known about the cause, so the store page is not a next step - the operator
        // restarts the app instead (ar_unavailable_failure says so).
        assertFalse(ArReadinessAction.offersInstall(ArReadiness.Failure("session init failed")))
    }

    @Test
    fun `ready and camera answers have nothing to install`() {
        assertFalse(ArReadinessAction.offersInstall(ArReadiness.Ready))
        assertFalse(ArReadinessAction.offersInstall(ArReadiness.CameraPermissionMissing))
        // No answer yet: the gate is not on screen for readiness at all.
        assertFalse(ArReadinessAction.offersInstall(null))
    }
}
