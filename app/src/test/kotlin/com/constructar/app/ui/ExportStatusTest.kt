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
 * The case worth a test is the quiet one: a folder the app may create directories in but not
 * write into. Nothing throws, every `openOutputStream` returns `null`, and the screen used to
 * announce "saved 0 files" in the success colour.
 */
class ExportStatusTest {

    private val unreadable = "No se puede escribir en la carpeta elegida."

    @Test
    fun `writing at least one file is a success and names the files`() {
        val status = ExportStatus.of(written = 4, folder = "ConstructAR_Bad_20260314-1015", folderUnreadable = unreadable)

        assertEquals(ExportStatus.Saved(count = 4, folder = "ConstructAR_Bad_20260314-1015"), status)
    }

    @Test
    fun `writing nothing is a failure, not a zero-file success`() {
        val status = ExportStatus.of(written = 0, folder = "ConstructAR_Bad_20260314-1015", folderUnreadable = unreadable)

        assertEquals(ExportStatus.Failed(detail = unreadable), status)
    }
}
