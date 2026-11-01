/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * When leaving the AR screen has to ask first.
 *
 * A run lives only in memory until it is finished: the nodes are held by `RouteSession`, the
 * positions by ARCore anchors, and `release()` drops both. That is the right design for a session
 * that needs the camera - but it means the screen's exit is also the run's exit, and the exit used
 * to be unconditional. The visible close button popped the screen, and the **system back gesture
 * had no handler at all**, so the quietest way out was the one that lost the most work: eight taps
 * along a wall, gone, with nothing to undo.
 *
 * The threshold is the same one `ConduitRoute.isRoutable` uses: two points make a measurement, a
 * single tap is a mis-tap. Kept out of the composable so the number has one home and can be
 * asserted.
 */
internal object LeaveGuard {

    const val MIN_NODES_FOR_A_RUN: Int = 2

    fun needsConfirmation(nodeCount: Int): Boolean = nodeCount >= MIN_NODES_FOR_A_RUN
}
