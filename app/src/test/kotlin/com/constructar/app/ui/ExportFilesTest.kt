/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * One export file out of five, on the worst day the storage can have.
 *
 * The export wrote its files in a loop whose error path only covered "the provider gave me no
 * stream". A write that *started* and threw left the half-written file in the folder and aborted
 * the rest of the bundle, so the operator kept a truncated protocol that opens and looks finished
 * and lost the four files after it (finding 130).
 *
 * The fake "file" here is a `StringBuilder`, so the rule is tested without a `DocumentFile`.
 */
class ExportFilesTest {

    private class FakeFile(val name: String) {
        var deleted = false
    }

    /** A write that dies after the first record, exactly like a full disk. */
    private class HalfWritingStream(private val failsAfter: Int) : ByteArrayOutputStream() {
        var written = 0
        override fun write(b: Int) {
            if (written == failsAfter) throw IOException("ENOSPC")
            written++
            super.write(b)
        }
    }

    private fun writeTo(
        file: FakeFile?,
        stream: Any?,
        onWrite: (Any) -> Unit,
    ): Pair<ExportFiles.Outcome, FakeFile?> =
        ExportFiles.writeOnce(
            create = { file },
            open = { stream },
            write = onWrite,
            delete = { it.deleted = true },
        ) to file

    @Test
    fun `a file that took everything counts as written`() {
        val file = FakeFile("protocol.pdf")
        val (outcome, made) = writeTo(file, ByteArrayOutputStream()) { (it as ByteArrayOutputStream).write(42) }

        assertEquals(ExportFiles.Outcome.Written, outcome)
        assertFalse("a finished file must not be deleted", made!!.deleted)
    }

    @Test
    fun `a provider that refuses the file loses nothing`() {
        val (outcome, made) = writeTo(null, ByteArrayOutputStream()) { }

        assertEquals(ExportFiles.Outcome.NotCreated, outcome)
        assertNull(made)
    }

    @Test
    fun `an empty entry the provider will not open is deleted again`() {
        val file = FakeFile("protocol.pdf")
        val (outcome, made) = writeTo(file, null) { }

        assertEquals(ExportFiles.Outcome.NotOpened, outcome)
        assertTrue("a 0-byte PDF must not stay in the folder", made!!.deleted)
    }

    @Test
    fun `a write that dies halfway deletes the truncated file and keeps the cause`() {
        val file = FakeFile("protocol.pdf")
        val stream = HalfWritingStream(failsAfter = 1)
        val (outcome, made) = writeTo(file, stream) {
            val sink = it as HalfWritingStream
            // One byte at a time: the second one is where the disk is full.
            repeat(3) { byte -> sink.write(byte) }
        }

        assertTrue(outcome is ExportFiles.Outcome.Broken)
        assertTrue((outcome as ExportFiles.Outcome.Broken).cause is IOException)
        assertTrue("the half-written protocol must not look finished", made!!.deleted)
    }

    @Test
    fun `creating the file may throw too, and it is not swallowed`() {
        val outcome = ExportFiles.writeOnce<String, ByteArrayOutputStream>(
            create = { throw SecurityException("grant revoked") },
            open = { ByteArrayOutputStream() },
            write = { it.write(1) },
            delete = { fail("nothing was created, so nothing may be deleted") },
        )

        assertTrue(outcome is ExportFiles.Outcome.Broken)
        assertTrue((outcome as ExportFiles.Outcome.Broken).cause is SecurityException)
    }

    @Test
    fun `a failed delete does not hide the write failure`() {
        val failure = IOException("ENOSPC")
        val outcome = ExportFiles.writeOnce<FakeFile, ByteArrayOutputStream>(
            create = { FakeFile("protocol.pdf") },
            open = { ByteArrayOutputStream() },
            write = { throw failure },
            delete = { throw IllegalStateException("already gone") },
        )

        assertSame(failure, (outcome as ExportFiles.Outcome.Broken).cause)
    }

    @Test
    fun `an OutOfMemoryError is not a failed file`() {
        // The `routeMeshes` rule: an Error is the end of the process, not a provider refusing a
        // write. Swallowing it here would report a folder that is quietly one file short.
        val file = FakeFile("protocol.pdf")
        try {
            ExportFiles.writeOnce<FakeFile, ByteArrayOutputStream>(
                create = { file },
                open = { ByteArrayOutputStream() },
                write = { throw OutOfMemoryError("bitmap") },
                delete = { it.deleted = true },
            )
            fail("an Error must propagate")
        } catch (expected: OutOfMemoryError) {
            assertEquals("bitmap", expected.message)
            assertTrue("the empty file still goes, even when the process is out of memory", file.deleted)
        }
    }
}
