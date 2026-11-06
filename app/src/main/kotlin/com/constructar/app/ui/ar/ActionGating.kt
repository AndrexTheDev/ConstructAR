/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * Why "Finish run" and "Close loop" are greyed out, said in points (finding 258).
 *
 * The first device run reported them as broken - "funktionieren nicht" - and from the outside they
 * were: a Material button with `enabled = false` dims its label by 38 % and answers a tap with
 * nothing at all. A worker with gloves does not read that as "you need two points first"; he reads
 * it as a defect, and he is right to, because the screen never said what was missing.
 *
 * The thresholds themselves live where they are enforced - `RouteSession` finishes at two nodes,
 * the screen closes a loop at three - and this object only repeats them in words, so the sentence
 * under the buttons cannot drift away from the buttons.
 */
internal object ActionGating {

    /** The number of points "Close loop" waits for; the screen's own `enabled` uses the same one. */
    const val NODES_FOR_A_LOOP: Int = 3

    /**
     * How many points are still missing before both buttons work, or `null` when they both do.
     *
     * While there is no run at all the answer is the finish threshold, because that is the button
     * the operator is reaching for - the loop is the rarer of the two.
     */
    fun missingPoints(nodeCount: Int, canFinish: Boolean): Int? = when {
        !canFinish -> LeaveGuard.MIN_NODES_FOR_A_RUN
        nodeCount < NODES_FOR_A_LOOP -> NODES_FOR_A_LOOP
        else -> null
    }
}
