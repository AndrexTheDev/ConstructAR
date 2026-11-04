/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** How often a screen that shows a running clock refreshes it. */
internal const val CLOCK_TICK_MS: Long = 30_000L

/**
 * The wall clock, moved on every [CLOCK_TICK_MS] while [running] and frozen when nothing runs.
 *
 * Two screens show a number that a running session changes - the field book's total and the room
 * screen's price preview - and both used to answer it from a value captured once. The price card
 * was worse than the field book: it was `remember`ed on the session *list*, and that list does not
 * change while a clock runs, so the labour figure the operator was looking at while typing a rate
 * was the one from the moment the screen opened. The document (since the same round) counts to the
 * moment of the export and the field book ticks every half minute, so the card was the only surface
 * that disagreed with both.
 *
 * One ticker, in one file, for the same reason `Pricing` is one object: two clocks that answer
 * "what time is it" drift apart. Half a minute is deliberate - a phone's battery is the shift, and
 * a total that is 29 seconds old is not wrong in any way that matters.
 */
@Composable
internal fun rememberNowMs(running: Boolean): Long {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            nowMs = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
    return nowMs
}
