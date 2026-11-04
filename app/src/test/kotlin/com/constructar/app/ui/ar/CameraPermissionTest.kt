/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which action the camera gate offers.
 *
 * The failure this locks down is a button that cannot work: after two denials Android answers the
 * request with "denied" without ever showing a dialog, so *grant camera access* did nothing at
 * all and kept doing nothing. The operator had no way to reach the settings screen from inside the
 * app, on the only screen that can measure.
 */
class CameraPermissionTest {

    @Test
    fun `a granted permission measures`() {
        assertEquals(
            CameraPermissionState.GRANTED,
            cameraPermissionState(granted = true, asked = true, rationaleKnown = true, showRationale = false),
        )
    }

    @Test
    fun `the first ask is a request, not a block`() {
        // Before the first request the system reports "no rationale" too - which is why the gate
        // tracks whether it has asked.
        assertEquals(
            CameraPermissionState.ASK,
            cameraPermissionState(granted = false, asked = false, rationaleKnown = true, showRationale = false),
        )
    }

    @Test
    fun `a denial that still shows the rationale can be asked again`() {
        assertEquals(
            CameraPermissionState.ASK,
            cameraPermissionState(granted = false, asked = true, rationaleKnown = true, showRationale = true),
        )
    }

    @Test
    fun `a denial the system will no longer show is a settings trip`() {
        assertEquals(
            CameraPermissionState.BLOCKED,
            cameraPermissionState(granted = false, asked = true, rationaleKnown = true, showRationale = false),
        )
    }

    @Test
    fun `without an activity the gate never claims to be blocked`() {
        assertEquals(
            CameraPermissionState.ASK,
            cameraPermissionState(granted = false, asked = true, rationaleKnown = false, showRationale = false),
        )
    }
}
