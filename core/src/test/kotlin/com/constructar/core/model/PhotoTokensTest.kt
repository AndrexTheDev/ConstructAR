/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A photo only exists as a file name, so the name has to carry everything: which project it
 * belongs to (cascade delete, restore) and which entry it documents (the field book row).
 */
class PhotoTokensTest {

    @Test
    fun `a room photo is the bare project id`() {
        assertEquals("p-42", PhotoTokens.forRoom("p-42"))
        assertTrue(PhotoTokens.isRoom("p-42"))
        assertNull(PhotoTokens.entryOf("p-42"))
        assertEquals(PhotoKind.ROOM, PhotoTokens.kindOf("p-42"))
    }

    @Test
    fun `a diary photo names project and entry`() {
        val token = PhotoTokens.forDiary("p-42", "e-7")
        assertEquals("p-42__diary_e-7", token)
        assertEquals(PhotoKind.DIARY, PhotoTokens.kindOf(token))
        assertEquals("p-42", PhotoTokens.projectOf(token))
        assertEquals("e-7", PhotoTokens.entryOf(token))
        assertFalse(PhotoTokens.isRoom(token))
    }

    @Test
    fun `a defect photo names project and defect`() {
        val token = PhotoTokens.forDefect("p-42", "d-1")
        assertEquals("p-42__defect_d-1", token)
        assertEquals(PhotoKind.DEFECT, PhotoTokens.kindOf(token))
        assertEquals("p-42", PhotoTokens.projectOf(token))
        assertEquals("d-1", PhotoTokens.entryOf(token))
    }

    @Test
    fun `the project prefix is what a cascade delete matches on`() {
        val ids = listOf("p-1", "p-12")
        // "p-1" must not collect the photos of "p-12": the prefix is compared as a whole token.
        assertEquals("p-12", PhotoTokens.projectOf(PhotoTokens.forDiary("p-12", "e-1")))
        assertTrue(ids.contains(PhotoTokens.projectOf(PhotoTokens.forDefect("p-1", "d-9"))))
    }

    @Test
    fun `ids with hyphens survive the round trip`() {
        val token = PhotoTokens.forDiary("3f9c-11ab", "9d0c-77")
        assertEquals("3f9c-11ab", PhotoTokens.projectOf(token))
        assertEquals("9d0c-77", PhotoTokens.entryOf(token))
    }

    @Test
    fun `an unknown token is treated as a room photo, never as an entry`() {
        assertEquals(PhotoKind.ROOM, PhotoTokens.kindOf("weird"))
        assertNull(PhotoTokens.entryOf("weird"))
    }
}
