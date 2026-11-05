/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SaveCoalescerTest {

    @Test
    fun `a burst of offers writes only the last value once`() = runTest {
        val written = mutableListOf<Float>()
        val coalescer = SaveCoalescer<Float>(windowMillis = 500L) { written += it }
        backgroundScope.launch { coalescer.run() }
        runCurrent()

        repeat(30) { coalescer.offer(it.toFloat()) }
        runCurrent()
        assertEquals(emptyList(), written, "nothing may be written while the burst is running")

        advanceTimeBy(499)
        runCurrent()
        assertEquals(emptyList(), written, "the window is not over yet")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(29f), written, "one write, of the newest value")
    }

    @Test
    fun `offers separated by more than the window each write`() = runTest {
        val written = mutableListOf<Float>()
        val coalescer = SaveCoalescer<Float>(windowMillis = 500L) { written += it }
        backgroundScope.launch { coalescer.run() }
        runCurrent()

        coalescer.offer(1.4f)
        advanceTimeBy(600)
        runCurrent()
        coalescer.offer(1.5f)
        advanceTimeBy(600)
        runCurrent()

        assertEquals(listOf(1.4f, 1.5f), written)
    }

    @Test
    fun `a slow writer never blocks the caller`() = runTest {
        var writes = 0
        val coalescer = SaveCoalescer<Float>(windowMillis = 500L) {
            writes++
            kotlinx.coroutines.delay(5_000)
        }
        backgroundScope.launch { coalescer.run() }
        runCurrent()

        coalescer.offer(1f)
        advanceTimeBy(600)
        runCurrent()
        // The writer is now suspended inside its own delay; offering during it must return at once.
        repeat(100) { coalescer.offer(it.toFloat()) }
        advanceTimeBy(600)
        runCurrent()

        assertEquals(2, writes, "the pending write is superseded, not queued a hundred times")
    }

    @Test
    fun `default window is half a second`() {
        assertEquals(500L, SaveCoalescer.DEFAULT_WINDOW_MS)
    }
}
