/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * Whether arriving at the room list closes the room the view model still holds.
 *
 * The app remembers the room the operator is standing in, so a process killed by an incoming call
 * comes back into that room instead of a list (`UiPreferences.lastProjectId`). Two exits from the
 * project screen are supposed to end that: the back arrow calls `closeProject()`, which drops the
 * preference - and the **system back gesture**, the one every Android user actually uses, only
 * popped the destination. Nothing typed, so the gesture handler was not even installed; the room
 * stayed "open" behind a list that showed no room, and the next cold start walked back into a room
 * the operator had left - the exact sentence `lastProjectId` promises does not happen (finding 134).
 * Leaving an export that was started from the list had the same effect.
 *
 * The list is therefore the one destination that means "no room is open". The one case that must
 * *not* close is a quick resume that has not been shown yet: the room is in the state, the
 * navigation to it is one frame away, and closing it here would cancel the very feature.
 */
internal object OpenRoom {

    /**
     * @param hasOpenProject a room is still in the view-model state.
     * @param resumePending  that room arrived by quick resume and has not been shown yet.
     */
    fun closesOnHome(hasOpenProject: Boolean, resumePending: Boolean): Boolean =
        hasOpenProject && !resumePending
}
