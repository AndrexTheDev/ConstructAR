/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.core.model.ConduitRoute

/**
 * Which committed runs may be drawn inside the AR view.
 *
 * A run is stored as world-space points, and that space belongs to **one ARCore session**. A new
 * session - a fresh screen, or the retry the failure gate offers - starts from a new world origin,
 * so a run measured yesterday has coordinates that mean nothing in today's frame: the app drew it
 * anyway, somewhere in the room, in exactly the way the app's own comment about recovered runs says
 * must not happen ("drawing it would put a length of conduit in a random spot of this room"). The
 * filter existed and keyed on `recovered`, which is only one of the ways a run can belong to a
 * session that no longer exists (finding 129).
 *
 * The protocol, the CSV and the price are untouched by this: they take lengths, and a length is
 * right in every frame. It is only the *picture* that has to be honest about what it can place.
 */
internal object SessionRuns {

    /**
     * [routes] whose geometry can be drawn: only the ones finished in this session.
     *
     * @param finishedHere ids the AR session itself produced. A recovered draft never is one -
     *   its coordinates were captured by a session that died with the process - and neither is a
     *   run measured on an earlier visit.
     */
    fun placeable(finishedHere: Set<String>, routes: List<ConduitRoute>): List<ConduitRoute> =
        routes.filter { it.id in finishedHere }
}
