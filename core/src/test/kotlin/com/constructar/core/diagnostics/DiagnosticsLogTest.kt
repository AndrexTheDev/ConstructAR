/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log a beta tester hands over (finding 256).
 *
 * Three properties matter and no more: it keeps what just happened, it never grows without bound,
 * and a line says when it was written - because a report that reads "the walls drifted" is only
 * useful next to the seconds in which they did.
 */
class DiagnosticsLogTest {

    private var now = 1_700_000_000_000L

    private fun log(capacity: Int = DiagnosticsLog.MAX_ENTRIES) = DiagnosticsLog(capacity) { now }

    @Test
    fun `the newest line is the last one`() {
        val log = log()
        log.add("scan", "started")
        log.add("closure", "offered")

        assertEquals(listOf("scan", "closure"), log.lines().map { it.substringAfter('[').substringBefore(']') })
    }

    @Test
    fun `the cap drops the oldest, never the newest`() {
        val log = log(capacity = 3)
        (1..5).forEach { log.add("scan", "frame $it") }

        assertEquals(3, log.size)
        val lines = log.lines()
        assertTrue("the first two are gone", lines.none { it.contains("frame 1") || it.contains("frame 2") })
        assertTrue("the last one is kept", lines.last().contains("frame 5"))
    }

    @Test
    fun `a line carries the moment it was written`() {
        val log = log()
        now = 1_700_000_123_000L
        log.add("export", "pdf written")

        assertTrue("a report without a timestamp is a rumour", log.lines().single().startsWith("2023-11-14 22:15:23"))
    }

    @Test
    fun `a multi-line message stays one line`() {
        val log = log()
        log.add("crash", "IllegalStateException\n\tat Foo.bar(Foo.kt:1)")

        assertEquals("one entry, one line", 1, log.size)
        assertEquals(1, log.toText(emptyList()).trim().lines().count { it.contains("at Foo.bar") })
    }

    @Test
    fun `the text file starts with the header it was given`() {
        val log = log()
        log.add("app", "started")

        val text = log.toText(listOf("ConstructAR beta", "Android 14"))
        assertTrue(text.startsWith("ConstructAR beta\nAndroid 14\n\n"))
        assertTrue(text.contains("[app]"))
    }
}
