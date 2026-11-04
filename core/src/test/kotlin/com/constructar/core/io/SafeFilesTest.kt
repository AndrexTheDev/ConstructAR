/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.io

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The crash contract: either the old file or the new one, never a half-written one - and never
 * a leftover temporary that a backup would later pick up.
 */
class SafeFilesTest {

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("constructar-safe-files").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a new file appears with exactly the bytes that were written`() {
        val target = File(directory, "project.json")
        assertTrue(SafeFiles.writeAtomically(target, "{\"id\":\"p1\"}"))
        assertEquals("{\"id\":\"p1\"}", target.readText())
    }

    @Test
    fun `an existing file is replaced, not appended to`() {
        val target = File(directory, "project.json")
        target.writeText("old and much longer content than the new one")
        assertTrue(SafeFiles.writeAtomically(target, "new"))
        assertEquals("new", target.readText())
    }

    @Test
    fun `no temporary file survives a successful write`() {
        val target = File(directory, "project.json")
        SafeFiles.writeAtomically(target, "x")
        val leftovers = directory.listFiles()?.map { it.name }.orEmpty()
        assertEquals(listOf("project.json"), leftovers)
    }

    @Test
    fun `the target is untouched when the write cannot happen`() {
        // A regular file where the parent directory should be: the temporary cannot be created,
        // which is what a full or unwritable volume looks like from here. (The old version of
        // this test put a directory in `<target>.tmp`, which only worked while the temporary had
        // one fixed name - see finding 115.)
        val blocked = File(directory, "blocked").apply { writeText("not a directory") }
        val target = File(blocked, "project.json")
        assertFalse(SafeFiles.writeAtomically(target, "replacement"))
        assertEquals("not a directory", blocked.readText())
    }

    /**
     * The defect this guards was in the crash-safety code itself: every write used one fixed
     * scratch name, `<target>.tmp`, so two saves of the same room opened the same path, truncated
     * each other and renamed a *mixture* into place - the one outcome the object exists to make
     * impossible. Two writers and one shared scratch name produced 25 mixed targets in 40 rounds;
     * a unique scratch name per call produced none.
     */
    @Test
    fun `writes of the same room from several threads never leave a mixture`() {
        val target = File(directory, "project.json")
        val payloads = List(6) { index ->
            ByteArray(128 * 1024) { index.digitToChar().code.toByte() }
        }
        repeat(3) {
            val barrier = java.util.concurrent.CyclicBarrier(payloads.size)
            val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
            val threads = payloads.map { payload ->
                Thread {
                    barrier.await()
                    results += SafeFiles.writeAtomically(target, payload)
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertTrue("every write must report what it did", results.all { it })
            val content = target.readBytes()
            assertTrue(
                "the target must be exactly one payload, never a mixture of two",
                payloads.any { it.contentEquals(content) },
            )
        }
        assertTrue(
            "no scratch file may survive the round",
            directory.listFiles()?.none { it.name.endsWith(SafeFiles.TEMPORARY_SUFFIX) } == true,
        )
    }

    @Test
    fun `an empty string is a valid, empty file`() {
        val target = File(directory, "empty.json")
        assertTrue(SafeFiles.writeAtomically(target, ""))
        assertTrue(target.exists())
        assertEquals(0L, target.length())
    }

    @Test
    fun `reading a missing or empty file yields null instead of an exception`() {
        assertNull(SafeFiles.readTextOrNull(File(directory, "nope.json")))
        val empty = File(directory, "empty.json").apply { writeText("") }
        assertNull(SafeFiles.readTextOrNull(empty))
        val text = File(directory, "yes.json").apply { writeText("hello") }
        assertEquals("hello", SafeFiles.readTextOrNull(text))
    }

    @Test
    fun `a crashed write leaves one temporary that is cleaned up on the next start`() {
        // Simulate the aftermath of a process kill between write and rename.
        File(directory, "project.json.tmp").writeText("half a project")
        File(directory, "project.json").writeText("complete")
        assertEquals(1, SafeFiles.clearStaleTemporaries(directory))
        assertNotNull(directory.listFiles()?.firstOrNull { it.name == "project.json" })
        assertTrue(directory.listFiles()?.none { it.name.endsWith(".tmp") } == true)
    }

    /**
     * Finding 176: the fallback used to delete the old file and only then rename the new one into
     * place. A filesystem that refuses both moves left no file at all — and the fallback only
     * runs where the ordinary save already failed, so the delete landed on a save that was going
     * wrong anyway. The old version is moved aside instead and comes back if the swap fails.
     */
    @Test
    fun `a filesystem that refuses both moves keeps the old file`() {
        val target = File(directory, "project.json").apply { writeText("the only copy") }
        val temporary = File(directory, "project.json.7.tmp").apply { writeText("replacement") }
        val backup = File(directory, "project.json.9.bak")

        val written = SafeFiles.replaceOverExisting(target, temporary, backup) { _, _ -> false }

        assertFalse("a move that is refused is a failed write", written)
        assertEquals("the old version is the only copy there is", "the only copy", target.readText())
        assertFalse("the temporary of a failed write is worthless", temporary.exists())
        assertFalse("nothing may be left aside", backup.exists())
    }

    @Test
    fun `the old version comes back when the new one does not land`() {
        val target = File(directory, "project.json").apply { writeText("the only copy") }
        val temporary = File(directory, "project.json.7.tmp").apply { writeText("replacement") }
        val backup = File(directory, "project.json.9.bak")
        var hop = 0

        val written = SafeFiles.replaceOverExisting(target, temporary, backup) { from, to ->
            hop++
            // The first move (old file aside) works, the second (new file in) is refused.
            hop != 2 && from.renameTo(to)
        }

        assertFalse("the new version never arrived", written)
        assertEquals("the old version is back at its own name", "the only copy", target.readText())
        assertEquals(
            "only the room itself may remain",
            listOf("project.json"),
            directory.listFiles()?.map { it.name }?.sorted(),
        )
    }

    @Test
    fun `the swap succeeds on a filesystem that needs two moves`() {
        val target = File(directory, "project.json").apply { writeText("old") }
        val temporary = File(directory, "project.json.7.tmp").apply { writeText("replacement") }
        val backup = File(directory, "project.json.9.bak")
        var hop = 0

        val written = SafeFiles.replaceOverExisting(target, temporary, backup) { from, to ->
            hop++
            assertTrue("the fallback is two moves, not a loop", hop <= 3)
            from.renameTo(to)
        }

        assertTrue("two moves are enough", written)
        assertEquals("replacement", target.readText())
        assertEquals(
            "the old version and the temporary are both gone",
            listOf("project.json"),
            directory.listFiles()?.map { it.name }?.sorted(),
        )
    }

    @Test
    fun `a replacement that died between its two moves is put back on the next start`() {
        // The crash window the fallback opens: the old file is aside, the new one never landed.
        File(directory, "project.json.3.bak").writeText("the last good version")

        assertEquals(1, SafeFiles.clearStaleTemporaries(directory))

        assertEquals(
            "the room is back where the repository looks for it",
            "the last good version",
            File(directory, "project.json").readText(),
        )
        assertTrue(
            "the rescued backup is not left behind",
            directory.listFiles()?.none { it.name.endsWith(SafeFiles.BACKUP_SUFFIX) } == true,
        )
    }

    @Test
    fun `a backup beside a living room is only debris`() {
        File(directory, "project.json").writeText("current")
        File(directory, "project.json.4.bak").writeText("stale")
        File(directory, "notes.bak").writeText("not ours - no serial, so not touched")

        assertEquals(1, SafeFiles.clearStaleTemporaries(directory))

        assertEquals(
            "the living room is not overwritten",
            "current",
            File(directory, "project.json").readText(),
        )
        assertTrue("a foreign .bak is left alone", File(directory, "notes.bak").exists())
    }
}
