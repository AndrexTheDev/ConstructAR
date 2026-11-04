/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * Writes one export file, and makes sure a file that did not get written does not stay behind.
 *
 * The export loop used to be three lines:
 *
 * ```
 * val file = target.createFile(mime, name)
 * val output = file?.uri?.let { resolver.openOutputStream(it) }
 * if (output != null) { output.use { artifact.write(it) }; written++ } else file?.delete()
 * ```
 *
 * The `else` deleted the empty entry when no stream came back - and then a write that *started*
 * and threw (the storage full, the provider pulling the grant, the PDF renderer failing on a
 * bitmap) left the half-written file exactly where the finished one would have been
 * (finding 130). A truncated protocol is worse than a missing one: a viewer opens it, the summary
 * and the runs are there, the signature page is not, and the operator mails it to a customer. The
 * exception also aborted the whole attempt, so the files that *had* been written were not reported
 * either.
 *
 * Deleting is therefore part of writing, not an error path bolted next to it. Android types stay
 * outside: [create]/[open] are lambdas, so the rule is testable without a `DocumentFile`.
 */
internal object ExportFiles {

    /** What became of one file. */
    sealed interface Outcome {
        /** The stream took everything and was closed without complaint. */
        data object Written : Outcome

        /** The provider refused to create the file at all; nothing exists and nothing was lost. */
        data object NotCreated : Outcome

        /**
         * The file exists but the provider refused to open it. The empty entry is deleted again:
         * a 0-byte `ConstructAR_Kueche_….pdf` in the folder is a file the operator will try to
         * mail.
         */
        data object NotOpened : Outcome

        /**
         * Creating, opening or writing threw. The file - empty or half-written - is deleted, and
         * [cause] is kept so the screen can still name the failure in the operator's language.
         */
        data class Broken(val cause: Throwable?) : Outcome
    }

    /**
     * @param create creates the (still empty) file, or returns `null` when the provider refuses.
     * @param open opens the stream to write into, or returns `null` when the provider refuses.
     * @param write writes the whole artifact into the stream; it may throw halfway.
     * @param delete removes the file [create] made.
     */
    fun <F : Any, S : Any> writeOnce(
        create: () -> F?,
        open: (F) -> S?,
        write: (S) -> Unit,
        delete: (F) -> Unit,
    ): Outcome {
        var made: F? = null
        return try {
            val file = create() ?: return Outcome.NotCreated
            made = file
            val sink = open(file)
            if (sink == null) {
                remove(file, delete)
                return Outcome.NotOpened
            }
            write(sink)
            Outcome.Written
        } catch (failure: Throwable) {
            made?.let { remove(it, delete) }
            // An `OutOfMemoryError` while building a bitmap is not a failed file, it is the end of
            // the process: it is passed on, but the half-written file still goes first. Swallowing
            // it would hand back a folder that looks complete - the same rule as
            // `ExportService.routeMeshes`.
            if (failure is Error) throw failure
            Outcome.Broken(failure)
        }
    }

    /**
     * Best effort: a provider that already lost the file must not turn a write failure into a
     * delete failure that hides it.
     */
    private fun <F : Any> remove(file: F, delete: (F) -> Unit) {
        try {
            delete(file)
        } catch (_: Exception) {
            // Nothing left to say: the write is the failure the caller reports.
        }
    }
}
