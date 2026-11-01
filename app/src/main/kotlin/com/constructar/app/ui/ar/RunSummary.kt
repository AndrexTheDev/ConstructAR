/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * What the whole room adds up to, while it is still being measured.
 *
 * The HUD used to describe only the run under the crosshair. The operator's actual question on
 * site is "how much is this room", and the moment he can answer it - without leaving the
 * viewfinder, without opening the project screen - is the moment the app stops being a
 * point-to-point toy and behaves like a measuring tool.
 *
 * The live run counts as soon as it is a measurement (two points), because that is the number he
 * is watching grow; a single tap is not a run and never appears here.
 */
internal object RunSummary {

    data class Totals(val runs: Int, val meters: Float)

    /**
     * @param committedMeters length of every run already saved in the project.
     * @param liveNodes points in the run being measured right now.
     * @param liveMeters its current length.
     */
    fun of(committedMeters: List<Float>, liveNodes: Int, liveMeters: Float): Totals {
        val liveCounts = liveNodes >= LeaveGuard.MIN_NODES_FOR_A_RUN
        val live = if (liveCounts) liveMeters.coerceAtLeast(0f) else 0f
        return Totals(
            runs = committedMeters.size + if (liveCounts) 1 else 0,
            // Summed in Double, rounded once: twelve runs of 2.4 m must read 28.80, not 28.799999.
            meters = (committedMeters.sumOf { it.toDouble() } + live.toDouble()).toFloat(),
        )
    }
}
