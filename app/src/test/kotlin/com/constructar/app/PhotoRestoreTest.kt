/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app

import com.constructar.core.model.PhotoTokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a restore does with the photos of the rooms it brought back.
 *
 * The shop page promises that restoring on a new phone "brings the photos along", and the restore
 * announced the rooms it had imported while quietly dropping every photo it could not write. A
 * full phone, a revoked folder, a provider that only allows directory creation - `savePhotoAs`
 * answers `false` to all three, and the answer was thrown away (finding 92).
 *
 * The three outcomes are counted here without a content resolver, a file or a phone, because the
 * copy is handed in as a lambda: the mapping from token to room, the room that was held back, and
 * the write this phone refused.
 */
class PhotoRestoreTest {

    private fun restore(
        photos: List<Pair<String, String>>,
        homes: Map<String, String>,
        save: suspend (String, String) -> Boolean,
    ): PhotoRestore = runBlocking { restorePhotos(photos, homes, save) }

    @Test
    fun `a photo follows its room to the id that room was stored under`() {
        // The room's old id was taken, so it came back as `p1-new`; its photo is named after the
        // old id. Matching by file name instead of by token would leave the picture behind.
        val saved = mutableListOf<String>()
        val result = restore(
            photos = listOf("p1__diary_d1" to "uri-1"),
            homes = mapOf("p1" to "p1-new"),
            save = { token, _ -> saved += token; true },
        )

        assertEquals(1, result.restored)
        assertEquals(0, result.failed)
        assertEquals(
            listOf(PhotoTokens.withProject("p1__diary_d1", "p1-new")),
            saved,
        )
    }

    @Test
    fun `a photo of a room that was held back is neither restored nor failed`() {
        // The room is still in the backup, waiting for the upgrade that will import it; its photo
        // is waiting with it. Counting it as a failure would blame the phone for the plan.
        var attempts = 0
        val result = restore(
            photos = listOf("p2" to "uri-2"),
            homes = mapOf("p1" to "p1"),
            save = { _, _ -> attempts++; true },
        )

        assertEquals(0, result.restored)
        assertEquals(0, result.failed)
        assertEquals("a photo of a held-back room must not be written at all", 0, attempts)
    }

    @Test
    fun `a write this phone refused is counted, not swallowed`() {
        val result = restore(
            photos = listOf("p1" to "uri-a", "p1__diary_d1" to "uri-b", "p1__defect_x" to "uri-c"),
            homes = mapOf("p1" to "p1"),
            save = { _, photo -> photo != "uri-b" },
        )

        assertEquals(2, result.restored)
        assertEquals("the refused photo is the one the card has to name", 1, result.failed)
    }

    @Test
    fun `nothing to copy is no failure`() {
        val result = restore(photos = emptyList(), homes = emptyMap(), save = { _, _ -> false })

        assertEquals(PhotoRestore(restored = 0, failed = 0), result)
    }
}
