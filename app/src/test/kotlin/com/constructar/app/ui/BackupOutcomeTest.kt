/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the backup card says after copying, and why it is not always "saved".
 *
 * This is the second copy of the same code as the export screen's write loop, and it had the same
 * flaw for the same reason: a counter of successes cannot tell three outcomes apart. On a fresh
 * install the card announced `0 Projekte gesichert` in the success colour over an empty folder it
 * had just created; on a folder that had lost its write grant it said the same thing.
 */
class BackupOutcomeTest {

    private val nothing = "Noch nichts zu sichern."
    private val failed = "Sicherung fehlgeschlagen."

    private fun report(available: Int, written: Int, photos: Int = 0, photosWritten: Int = 0) =
        BackupOutcome.Report(
            available = available,
            written = written,
            photos = photos,
            photosWritten = photosWritten,
        )

    @Test
    fun `nothing to copy is said plainly, not reported as a zero-file success`() {
        val outcome = BackupOutcome.of(report(available = 0, written = 0), nothing, failed)

        assertEquals(BackupOutcome.Failed(detail = nothing), outcome)
    }

    @Test
    fun `everything failing to write points at the folder`() {
        val outcome = BackupOutcome.of(report(available = 3, written = 0), nothing, failed)

        assertEquals(BackupOutcome.Failed(detail = failed), outcome)
    }

    @Test
    fun `a complete copy counts projects and photos`() {
        val outcome = BackupOutcome.of(
            report(available = 2, written = 2, photos = 5, photosWritten = 5),
            nothing,
            failed,
        )

        assertEquals(BackupOutcome.Saved(projects = 2, photos = 5, photosSkipped = 0), outcome)
    }

    @Test
    fun `photos that could not be copied are reported, not swallowed`() {
        val outcome = BackupOutcome.of(
            report(available = 2, written = 2, photos = 5, photosWritten = 3),
            nothing,
            failed,
        )

        assertEquals(BackupOutcome.Saved(projects = 2, photos = 3, photosSkipped = 2), outcome)
    }

    @Test
    fun `a partly written backup says so instead of counting only its successes`() {
        // Five rooms, two written: the card used to say "2 projects and 0 photos copied" in the
        // success colour, so the three rooms that are not in the folder were invisible - while the
        // photos it skipped in the same run got a warning line (finding 85).
        val outcome = BackupOutcome.of(report(available = 5, written = 2), nothing, failed)

        assertEquals(
            BackupOutcome.Saved(projects = 2, photos = 0, photosSkipped = 0, projectsSkipped = 3),
            outcome,
        )
    }

    @Test
    fun `a complete backup skips nothing, in either category`() {
        val outcome = BackupOutcome.of(
            report(available = 3, written = 3, photos = 4, photosWritten = 4),
            nothing,
            failed,
        )

        assertEquals(
            BackupOutcome.Saved(projects = 3, photos = 4, photosSkipped = 0, projectsSkipped = 0),
            outcome,
        )
    }
    @Test
    fun `a restore this phone refused blames the storage, not the backup`() {
        // Nothing imported, nothing held back, one room read and refused: "nothing imported" would
        // send the operator looking at his backup instead of at his storage (finding 86).
        val refused = BackupOutcome.RestoreCounts(
            imported = 0,
            heldBack = 0,
            unreadable = 0,
            notWritten = 2,
        )

        assertEquals(
            BackupOutcome.Failed(detail = "storage"),
            BackupOutcome.restored(refused, nothingImported = "no rooms", storageFailed = "storage"),
        )
    }

    @Test
    fun `a restore that saved one room and was refused two still names both counts`() {
        val partial = BackupOutcome.RestoreCounts(
            imported = 1,
            heldBack = 0,
            unreadable = 0,
            notWritten = 2,
        )

        assertEquals(
            BackupOutcome.Restored(count = 1, notWritten = 2),
            BackupOutcome.restored(partial, nothingImported = "no rooms", storageFailed = "storage"),
        )
    }
}
