/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Turns a stream of "please save this" into one write per quiet window.
 *
 * The AR screen learns the floor from ARCore continuously - the same plane, re-reported every
 * frame, jittering by millimetres. Every one of those was a project file written through the
 * atomic temp-file/fsync/rename path, and although the write itself already ran on
 * `Dispatchers.IO`, the *serialisation and the enqueueing* happened on the caller's thread, in the
 * middle of a running AR session. A save that happens thirty times a second is not a save; it is a
 * disk benchmark with a measurement attached (finding 219).
 *
 * The operator's intent is the *last* value in a burst, so the coalescer keeps only that: every new
 * request cancels the pending one and restarts the timer. `collectLatest` does exactly that and is
 * a stable API - the `debounce` operator is still preview, and an experimental annotation in a
 * module compiled with `allWarningsAsErrors` is a build failure waiting for a dependency bump.
 *
 * `tryEmit` never suspends and never blocks the caller: a request offered while the writer is busy
 * replaces the queued one (`DROP_OLDEST`), which is the correct behaviour for a value that is
 * superseded anyway.
 */
class SaveCoalescer<T>(
    private val windowMillis: Long = DEFAULT_WINDOW_MS,
    private val write: suspend (T) -> Unit,
) {
    private val requests = MutableSharedFlow<T>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Offers a write. Returns immediately; the write happens when the burst goes quiet. */
    fun offer(value: T) {
        requests.tryEmit(value)
    }

    /** Drains the stream. Runs until the scope it is launched in is cancelled. */
    suspend fun run() {
        requests.collectLatest { value ->
            delay(windowMillis)
            write(value)
        }
    }

    companion object {
        /** Half a second: longer than a burst of ARCore frames, shorter than a human blink. */
        const val DEFAULT_WINDOW_MS: Long = 500L
    }
}
