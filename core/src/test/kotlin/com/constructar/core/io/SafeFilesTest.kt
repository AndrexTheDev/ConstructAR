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
}
