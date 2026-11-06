/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * Whether a screen that has just lost its room may still leave by itself (finding 259).
 *
 * The first device run reported it as "back to the list and the screen goes black, the app has to
 * be restarted". The mechanism was two pops for one gesture:
 *
 * 1. the arrow calls `closeProject()` - the room is gone - and then pops back to the list;
 * 2. the project destination is *still composed* for the length of the pop transition, and it now
 *    sees `openProject == null`, so the branch that says "this screen needs a room" runs its
 *    `LaunchedEffect { popBackStack() }` and pops **again** - this time the list itself.
 *
 * An empty back stack renders no destination at all: a black window that ignores back, because
 * there is nothing to go back to. Only a new process gets out of it.
 *
 * The rule is one comparison, and it is a comparison because it has to be testable: a destination
 * may only pop itself away while it is the destination the controller actually shows.
 */
internal object NavGuard {

    /**
     * True when [ownRoute] is the destination on screen, so leaving it is this screen's business.
     *
     * A destination that is merely being transitioned away reports the *next* route here, or none
     * at all after a process restore, and both answers mean "not yours to pop" - which is exactly
     * the second pop that emptied the stack.
     */
    fun shouldLeave(currentRoute: String?, ownRoute: String): Boolean =
        currentRoute != null && currentRoute == ownRoute
}
