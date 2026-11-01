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
        val target = File(directory, "project.json")
        target.writeText("important")
        // A directory in the temporary's place makes the write fail the way a full or
        // unwritable volume would.
        File(directory, "project.json.tmp").mkdirs()
        assertFalse(SafeFiles.writeAtomically(target, "replacement"))
        assertEquals("important", target.readText())
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
