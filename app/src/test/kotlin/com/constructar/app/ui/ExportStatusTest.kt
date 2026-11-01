/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the export screen says after an attempt.
 *
 * Three counts, three honest answers, and one order between them. The case that matters most is
 * the quiet one: a folder the app may create directories in but not write into - nothing throws,
 * every `openOutputStream` returns `null`, and the screen used to announce "saved 0 files" in the
 * success colour. The cases that matter just as much are the two where nothing was written for a
 * reason that has nothing to do with the folder: blaming it there is a wrong answer delivered with
 * confidence, and it sends the operator to check a card that is perfectly fine.
 */
class ExportStatusTest {

    private val messages = ExportStatus.Messages(
        folderUnreadable = "No se puede escribir en la carpeta elegida.",
        nothingSelected = "Elige al menos un formato de archivo.",
        nothingToExport = "Aún no hay geometría 3D.",
    )

    private fun report(requested: Int, produced: Int, written: Int, meshSkipped: Boolean = false) =
        ExportStatus.Report(
            requested = requested,
            produced = produced,
            written = written,
            folder = "ConstructAR_Bad_20260314-1015",
            meshSkipped = meshSkipped,
        )

    @Test
    fun `everything asked for was written, and the folder is named`() {
        val status = ExportStatus.of(report(requested = 4, produced = 4, written = 4), messages)

        assertEquals(ExportStatus.Saved(count = 4, folder = "ConstructAR_Bad_20260314-1015"), status)
    }

    @Test
    fun `writing nothing that existed is a folder failure, not a zero-file success`() {
        val status = ExportStatus.of(report(requested = 2, produced = 2, written = 0), messages)

        assertEquals(ExportStatus.Failed(detail = messages.folderUnreadable), status)
    }

    @Test
    fun `asking for nothing does not blame the folder`() {
        val status = ExportStatus.of(report(requested = 0, produced = 0, written = 0), messages)

        assertEquals(ExportStatus.Failed(detail = messages.nothingSelected), status)
    }

    @Test
    fun `a mesh-only export of an unmeasured room says what is missing`() {
        val status = ExportStatus.of(report(requested = 2, produced = 0, written = 0), messages)

        assertEquals(ExportStatus.Failed(detail = messages.nothingToExport), status)
    }

    @Test
    fun `a requested 3D model that no geometry could fill is not a plain success`() {
        // Four switches on, three files in the folder: the missing one is the 3D model, because
        // this project was measured by hand and holds no scan. Saying "saved 3 files" leaves the
        // operator hunting for a GLB the app never could have written.
        val status = ExportStatus.of(
            report(requested = 4, produced = 3, written = 3, meshSkipped = true),
            messages,
        )

        assertEquals(
            ExportStatus.Meshless(saved = 3, expected = 4, folder = "ConstructAR_Bad_20260314-1015"),
            status,
        )
    }

    @Test
    fun `a partial write outranks the missing model`() {
        // One more file lost on top of the missing model: the write failure is the louder fact.
        val status = ExportStatus.of(
            report(requested = 4, produced = 3, written = 2, meshSkipped = true),
            messages,
        )

        assertEquals(
            ExportStatus.Partial(saved = 2, expected = 3, folder = "ConstructAR_Bad_20260314-1015"),
            status,
        )
    }

    @Test
    fun `a half-written folder is reported as partial, not as success`() {
        val status = ExportStatus.of(report(requested = 3, produced = 3, written = 2), messages)

        assertEquals(
            ExportStatus.Partial(saved = 2, expected = 3, folder = "ConstructAR_Bad_20260314-1015"),
            status,
        )
    }
}
