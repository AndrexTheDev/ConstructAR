/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.io

import java.io.File
import java.io.FileOutputStream

/**
 * Files that survive a crash in the middle of a write.
 *
 * A project file is the only copy of a morning's measuring, and phones die on site: battery,
 * a dropped phone, the OS killing the process. Writing straight into the target leaves a
 * truncated JSON file and, with it, a room that can never be opened again.
 *
 * The rules here are the ones that actually matter on Android's ext4/f2fs:
 *
 *  1. write to a sibling temporary file,
 *  2. `fsync` it, so the bytes are on flash before the rename is attempted,
 *  3. rename over the target — an atomic operation on the same filesystem, so a reader sees
 *     either the old file or the new one, never half of either,
 *  4. on any failure, remove the temporary file and leave the target untouched.
 *
 * 5. write under a lock and into a temporary whose name is **unique per call**, because a fixed
 *    `<target>.tmp` is a shared scratch file: two saves of the same room (two taps, a save that
 *    overlaps the draft write) opened the same path, truncated each other's bytes and renamed a
 *    mixture into place - the one failure this object exists to prevent, caused by the object
 *    itself. Simulated with two writers and one scratch name: 25 of 40 rounds left a target that
 *    was neither payload; with a unique name, 0 of 40 (finding 115).
 *
 * The one filesystem that refuses step 3 (a few Android volumes do) is handled by **moving the
 * old file aside**, never by deleting it: the old version comes back if the second move fails,
 * and a crash in that window is repaired on the next start. Deleting it first — which is what
 * this object did until finding 176 — turned a failed save into a lost room, on exactly the path
 * that exists because the ordinary save did not work.
 *
 * Kept in `:core` with no Android types so it can be unit-tested on the host JVM.
 */
object SafeFiles {

    /** Replaces [target] with [text]. Returns false (and keeps the old file) on failure. */
    fun writeAtomically(target: File, text: String): Boolean =
        writeAtomically(target, text.toByteArray(Charsets.UTF_8))

    fun writeAtomically(target: File, bytes: ByteArray): Boolean =
        // One lock for the whole object: writes are small (a project is a few hundred kilobytes),
        // rare, and already on an I/O thread, so serialising them costs nothing and removes the
        // only way two callers can meet in the same directory at all.
        synchronized(writeLock) { writeLocked(target, bytes) }

    private fun writeLocked(target: File, bytes: ByteArray): Boolean {
        val directory = target.parentFile ?: return false
        if (!directory.exists() && !directory.mkdirs()) return false
        val temporary = File(directory, "${target.name}.${serial.incrementAndGet()}$TEMPORARY_SUFFIX")
        return try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                // The rename is only atomic *and durable* if the data is already on flash.
                output.fd.sync()
            }
            if (temporary.renameTo(target)) {
                true
            } else {
                replaceOverExisting(
                    target = target,
                    temporary = temporary,
                    backup = File(directory, "${target.name}.${serial.incrementAndGet()}$BACKUP_SUFFIX"),
                )
            }
        } catch (error: Throwable) {
            temporary.delete()
            false
        }
    }

    /** Text of [file], or `null` when it does not exist or cannot be read. Never throws. */
    fun readTextOrNull(file: File): String? = runCatching {
        if (!file.exists() || file.length() == 0L) null else file.readText()
    }.getOrNull()

    /**
     * Puts [temporary] in place of an existing [target] on a filesystem that refuses to rename
     * over it.
     *
     * The old file is **moved aside**, not deleted: if the second move fails the first one is
     * undone, so a failed save still returns `false` with the previous version in place. The old
     * code deleted the target and then tried to rename, so a filesystem that refused both moves
     * left the room with no file at all (finding 176). Between the two moves the target does not
     * exist, which is why [clearStaleTemporaries] puts a leftover backup back.
     *
     * [move] is a parameter so the failing case can be tested: no host filesystem refuses to
     * rename over a file, and the case that matters is the one that cannot be reached otherwise.
     */
    internal fun replaceOverExisting(
        target: File,
        temporary: File,
        backup: File,
        move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ): Boolean {
        // If the old file cannot even be moved aside, nothing has happened yet: keep both.
        if (!move(target, backup)) {
            temporary.delete()
            return false
        }
        if (move(temporary, target)) {
            backup.delete()
            return true
        }
        // The new file did not land. Put the old one back; the temporary is worthless either way.
        move(backup, target)
        temporary.delete()
        return false
    }

    /**
     * Removes the leftovers of a crashed write, and puts back a room whose replacement died
     * between the two moves.
     *
     * Called once on start. A `.tmp` file that was never renamed is dead weight, and it is also
     * how a reader can tell that the previous write did not complete; a `.bak` file whose target
     * is missing *is* the room — the write had moved the old version aside and never got the new
     * one in (finding 176) — so it is renamed back rather than deleted. A `.bak` next to a
     * surviving target is the end of a completed write that failed to clean up, and goes.
     *
     * @return how many files were removed or restored.
     */
    fun clearStaleTemporaries(directory: File): Int {
        val files = directory.listFiles { file -> file.isFile } ?: return 0
        var handled = 0
        for (file in files) {
            if (file.name.endsWith(TEMPORARY_SUFFIX)) {
                if (file.delete()) handled++
                continue
            }
            if (!file.name.endsWith(BACKUP_SUFFIX)) continue
            // `<name>.<serial>.bak` is ours; anything else that ends in .bak is left alone.
            val stem = file.name.removeSuffix(BACKUP_SUFFIX)
            if (!stem.substringAfterLast('.').all { it.isDigit() }) continue
            val original = File(directory, stem.substringBeforeLast('.'))
            if (!original.exists() && file.renameTo(original)) {
                handled++
            } else if (file.delete()) {
                handled++
            }
        }
        return handled
    }

    const val TEMPORARY_SUFFIX = ".tmp"

    /** Where an interrupted replacement parked the old version - see [replaceOverExisting]. */
    const val BACKUP_SUFFIX = ".bak"

    /** Serialises every write in this process - see [writeAtomically]. */
    private val writeLock = Any()

    /** Makes each temporary unique: `project.json.17.tmp`, not one shared `project.json.tmp`. */
    private val serial = java.util.concurrent.atomic.AtomicLong(0L)
}
