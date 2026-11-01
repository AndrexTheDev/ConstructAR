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
 * Kept in `:core` with no Android types so it can be unit-tested on the host JVM.
 */
object SafeFiles {

    /** Replaces [target] with [text]. Returns false (and keeps the old file) on failure. */
    fun writeAtomically(target: File, text: String): Boolean =
        writeAtomically(target, text.toByteArray(Charsets.UTF_8))

    fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val directory = target.parentFile ?: return false
        if (!directory.exists() && !directory.mkdirs()) return false
        val temporary = File(directory, "${target.name}.tmp")
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
                // A few Android filesystems refuse to rename over an existing file.
                target.delete()
                val moved = temporary.renameTo(target)
                if (!moved) temporary.delete()
                moved
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
     * Removes the leftover `.tmp` files of a crashed write.
     *
     * Called once on start: a temporary file that was never renamed is dead weight, and it is
     * also how a reader can tell that the previous write did not complete.
     */
    fun clearStaleTemporaries(directory: File): Int {
        val stale = directory.listFiles { file -> file.isFile && file.name.endsWith(TEMPORARY_SUFFIX) }
            ?: return 0
        return stale.count { it.delete() }
    }

    const val TEMPORARY_SUFFIX = ".tmp"
}
