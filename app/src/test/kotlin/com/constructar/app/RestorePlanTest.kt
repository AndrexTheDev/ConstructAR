/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app

import com.constructar.app.ui.BackupOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The restore quota, which is the difference between "your other rooms are in the zip" and a
 * room that silently never made it.
 *
 * The scenario these tests exist for is not exotic: a backup folder holds the JSON files *and*
 * the photos, the picker hands over everything at once, and the first file it offers is not
 * necessarily a project. A free installation has one slot, so a stray file at the front must not
 * consume it.
 */
class RestorePlanTest {

    @Test
    fun `a file that is not a project does not use up a free slot`() {
        val imported = mutableListOf<String>()

        val counts = restoreOutcome(1, listOf("garbage", "room"), parse = { it == "room" }) { text ->
            imported += text
            true
        }

        assertEquals(1, counts.imported)
        // The garbage file is not "held back for Pro": no plan can import it.
        assertEquals(0, counts.heldBack)
        assertEquals(1, counts.unreadable)
        assertEquals(listOf("room"), imported)
    }

    @Test
    fun `a corrupt file is reported as unreadable, not as waiting for an upgrade`() {
        // Two rooms and one truncated file, one free slot: the slot goes to the first room and
        // the second is held back - and the truncated file must not be counted with it, because
        // the card promises that buying Pro imports what was held back.
        val counts = restoreOutcome(
            slots = 1,
            texts = listOf("room", "corrupt", "room2"),
            parse = { it != "corrupt" },
        ) { text ->
            text.startsWith("room")
        }

        assertEquals(1, counts.imported)
        assertEquals(1, counts.heldBack)
        assertEquals(1, counts.unreadable)
    }

    @Test
    fun `a stray file after the slot is used is still not a room waiting for an upgrade`() {
        // The free slot goes to the first room, so the loop never attempts the third file. If
        // classification hung on the attempt, the truncated file would be reported as held back
        // and the card would promise that Pro imports it - the same lie in a quieter place.
        val counts = restoreOutcome(
            slots = 1,
            texts = listOf("room", "garbage", "room2"),
            parse = { it != "garbage" },
        ) { it.startsWith("room") }

        assertEquals(1, counts.imported)
        assertEquals(1, counts.heldBack)
        assertEquals(1, counts.unreadable)
    }

    @Test
    fun `nothing readable and nothing held back is a failure, not a restore of zero`() {
        val counts = restoreOutcome(5, listOf("garbage", "also garbage"), parse = { false }) { false }

        assertEquals(
            BackupOutcome.Failed(detail = "no rooms"),
            BackupOutcome.restored(
                counts = BackupOutcome.RestoreCounts(
                    imported = counts.imported,
                    heldBack = counts.heldBack,
                    unreadable = counts.unreadable,
                ),
                nothingImported = "no rooms",
            ),
        )
    }

    @Test
    fun `files past the free slots are held back, not dropped`() {
        val counts = restoreOutcome(2, listOf("a", "b", "c", "d"), parse = { true }) { true }

        assertEquals(2, counts.imported)
        assertEquals(2, counts.heldBack)
        assertEquals(0, counts.unreadable)
    }

    @Test
    fun `no free slot holds everything back and imports nothing`() {
        var attempts = 0

        val counts = restoreOutcome(0, listOf("a", "b"), parse = { true }) {
            attempts++
            true
        }

        assertEquals(0, counts.imported)
        assertEquals(2, counts.heldBack)
        // Never attempted, so never called unreadable either.
        assertEquals(0, counts.unreadable)
        assertEquals(0, attempts)
    }

    @Test
    fun `an empty selection reports nothing either way`() {
        val counts = restoreOutcome(3, emptyList(), parse = { true }) { true }

        assertEquals(0, counts.imported)
        assertEquals(0, counts.heldBack)
        assertEquals(0, counts.unreadable)
    }
}
