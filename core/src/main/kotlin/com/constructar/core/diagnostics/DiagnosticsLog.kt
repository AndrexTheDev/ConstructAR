/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app did, in the order it did it — bounded, thread-safe, and meant to be handed over.
 *
 * A field report has always been a sentence ("after about ten minutes the walls drift"), and the
 * conversation that follows costs a day per round trip: which build, which phone, which screen,
 * what the graph said. Nothing in the app could answer any of that afterwards — the log went to
 * `logcat`, which is gone as soon as the cable is unplugged (finding 256).
 *
 * So this is a ring of timestamped lines with a hard cap: a tester with a phone in a cellar must not
 * be able to fill their storage with a log, and the newest entries are the ones that matter. The
 * formatting is `Locale.ROOT` because the file is read by a developer, not by the phone's owner.
 *
 * Thread confinement: none. The scan worker, the render thread and the main thread all write here,
 * which is why every method synchronises on one lock.
 */
class DiagnosticsLog(
    private val capacity: Int = MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val entries = ArrayDeque<String>()

    /** How many lines are being kept — the callers use it to decide whether to print anything. */
    val size: Int get() = synchronized(lock) { entries.size }

    /** One line: when, which part of the app, and what happened. */
    fun add(tag: String, message: String) {
        val line = formatStamp(clock()) + " " + tag.padEnd(TAG_WIDTH) + " " + message.replace('\n', ' ')
        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    /** The lines that are still held, oldest first. */
    fun lines(): List<String> = synchronized(lock) { entries.toList() }

    /** The whole log as one text file, with [header] first (build, phone, when it started). */
    fun toText(header: List<String>): String = buildString {
        header.forEach { appendLine(it) }
        appendLine()
        lines().forEach { appendLine(it) }
    }

    companion object {
        /** A thousand lines is a whole session of interest and a few hundred kilobytes of storage. */
        const val MAX_ENTRIES: Int = 1000

        private const val TAG_WIDTH = 10

        private fun formatStamp(epochMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(epochMillis))
    }
}
