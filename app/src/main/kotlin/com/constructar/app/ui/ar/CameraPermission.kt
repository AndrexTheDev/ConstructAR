/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * What the camera gate can actually do, as opposed to what it offers.
 *
 * The gate had one button: *grant camera access*, wired to
 * `launch(Manifest.permission.CAMERA)`. After two denials Android 11+ stops showing the dialog
 * and answers the request with "denied" immediately - no dialog, no message, no change. The
 * button stayed on the screen, tappable, doing nothing at all, forever: the app's only function
 * behind a dead end that looked like a working button. `docs/RELEASE_CHECKLIST.md` even listed
 * "permission denied → rationale → settings round trip" as a device check, which no code
 * implemented.
 *
 * There are three real states, and they need three different actions:
 *
 *  * [GRANTED] - measure.
 *  * [ASK] - the system will still show its dialog (first ask, or the user denied once and the
 *    rationale is still shown), so asking again is meaningful.
 *  * [BLOCKED] - the system will not show it again. Only the app's settings page can change the
 *    answer, so the gate has to say so and offer exactly that.
 */
internal enum class CameraPermissionState { GRANTED, ASK, BLOCKED }

/**
 * @param asked whether this screen has already requested the permission at least once. Before
 *   that, `shouldShowRequestPermissionRationale` is `false` for a *first* request too, which is
 *   why the flag cannot be skipped.
 * @param rationaleKnown whether the activity was available to ask. Without an answer the state
 *   stays [CameraPermissionState.ASK]: claiming "blocked" without evidence would send the
 *   operator to the settings app for no reason.
 */
internal fun cameraPermissionState(
    granted: Boolean,
    asked: Boolean,
    rationaleKnown: Boolean,
    showRationale: Boolean,
): CameraPermissionState = when {
    granted -> CameraPermissionState.GRANTED
    !asked -> CameraPermissionState.ASK
    !rationaleKnown -> CameraPermissionState.ASK
    showRationale -> CameraPermissionState.ASK
    else -> CameraPermissionState.BLOCKED
}
