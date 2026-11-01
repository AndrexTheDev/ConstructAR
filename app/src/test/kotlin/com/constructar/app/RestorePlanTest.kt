/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app

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

        val (importedCount, heldBack) = restoreOutcome(1, listOf("garbage", "room")) { text ->
            if (text == "room") {
                imported += text
                true
            } else {
                false
            }
        }

        assertEquals(1, importedCount)
        assertEquals(1, heldBack)
        assertEquals(listOf("room"), imported)
    }

    @Test
    fun `files past the free slots are held back, not dropped`() {
        val (importedCount, heldBack) = restoreOutcome(2, listOf("a", "b", "c", "d")) { true }

        assertEquals(2, importedCount)
        assertEquals(2, heldBack)
    }

    @Test
    fun `no free slot holds everything back and imports nothing`() {
        var attempts = 0

        val (importedCount, heldBack) = restoreOutcome(0, listOf("a", "b")) {
            attempts++
            true
        }

        assertEquals(0, importedCount)
        assertEquals(2, heldBack)
        assertEquals(0, attempts)
    }

    @Test
    fun `an empty selection reports nothing either way`() {
        val (importedCount, heldBack) = restoreOutcome(3, emptyList()) { true }

        assertEquals(0, importedCount)
        assertEquals(0, heldBack)
    }
}
