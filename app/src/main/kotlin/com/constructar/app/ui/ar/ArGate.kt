/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * Which blocking message the AR screen shows, if any.
 *
 * The screen used to answer three different "the camera cannot work" situations in three
 * different ways: a missing permission and an unsupported device got a full-screen message with
 * one button, while a **failed ARCore session** got a red line in the status column - underneath
 * the HUD, above a camera surface that stayed on screen, black, with every control still tappable.
 * Tapping `+` or *Save* then did nothing at all, because a session that never started never
 * delivers a frame, and the operator was left prodding a dead screen that looked alive.
 *
 * A session failure is the same class of event as the other two: nothing can be measured until it
 * is resolved. So it is decided here, once, in one place with one precedence order, instead of
 * being left to whichever `if` happens to be reached first.
 */
internal enum class ArGate { PERMISSION, READINESS, SESSION_FAILED, NONE }

/**
 * Precedence is deliberate: a missing permission is the honest first answer even if a session also
 * failed (the failure is a consequence), and readiness is checked before a failure because an
 * unsupported device can never start a session at all.
 */
internal fun arGate(
    hasCameraPermission: Boolean,
    readinessBlocked: Boolean,
    sessionFailed: Boolean,
): ArGate = when {
    !hasCameraPermission -> ArGate.PERMISSION
    readinessBlocked -> ArGate.READINESS
    sessionFailed -> ArGate.SESSION_FAILED
    else -> ArGate.NONE
}

/**
 * Whether the failed-session gate may offer *try again*.
 *
 * Only when nothing has been measured yet. A retry restarts the AR session, and the anchors that
 * hold a run's points belong to the session that created them: with points on screen, a retry
 * would either freeze them at their last pose or, worse, report positions from a session that no
 * longer exists. With work in progress the gate offers *save* instead, and the back gesture still
 * reaches the leave dialog, so discarding stays a deliberate act.
 */
internal fun canRetrySession(nodeCount: Int): Boolean =
    !LeaveGuard.needsConfirmation(nodeCount)
