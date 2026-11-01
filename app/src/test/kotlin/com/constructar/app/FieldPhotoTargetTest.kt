/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app

import com.constructar.core.model.DiaryEntry
import com.constructar.core.model.PhotoTokens
import com.constructar.core.model.ProjectRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a copied field photo lands when the screen moved on while it was copied.
 *
 * The scenario is ordinary on a slow phone: the operator taps "photo", the system picker returns
 * a five-megabyte picture, the copy starts, and the operator is already back in the room list and
 * has opened the next room by the time it finishes. The token in the file name names its own room,
 * so the answer is never "whatever is open now" - and when the room or the diary entry is gone, the
 * copy is an orphan that has to be removed rather than left in private storage.
 */
class FieldPhotoTargetTest {

    private fun room(id: String, diary: List<DiaryEntry> = emptyList()) = ProjectRecord(
        id = id,
        name = "Raum $id",
        createdAtEpochMs = 1_767_225_600_000L,
        updatedAtEpochMs = 1_767_225_600_000L,
        diary = diary,
    )

    private fun entry(id: String) = DiaryEntry(id = id, createdAtEpochMs = 1_767_225_600_000L, text = "Wand gefliest")

    @Test
    fun `the open room is the one that gets the picture`() {
        val token = PhotoTokens.forDiary("a", "e1")
        val open = room("a", listOf(entry("e1")))

        val target = fieldPhotoTarget(token, "e1", open = open, stored = null)

        assertTrue(target is FieldPhotoTarget.OnScreen)
        val applied = (target as FieldPhotoTarget.OnScreen).project
        assertEquals(token, applied.diary.single().photo)
        assertEquals("a", applied.id)
    }

    @Test
    fun `a room that is no longer on screen is written there, not to the open one`() {
        val token = PhotoTokens.forDiary("a", "e1")
        val open = room("b", listOf(entry("e1")))
        val stored = room("a", listOf(entry("e1")))

        val target = fieldPhotoTarget(token, "e1", open = open, stored = stored)

        assertTrue(target is FieldPhotoTarget.OffScreen)
        val applied = (target as FieldPhotoTarget.OffScreen).project
        assertEquals("a", applied.id)
        assertEquals(token, applied.diary.single().photo)
    }

    @Test
    fun `an entry that was deleted while the copy ran leaves an orphan, not a photo`() {
        // The room is there, the entry is not: `withDiaryPhoto` maps over the list and returns the
        // project unchanged, which used to be reported as a success.
        val token = PhotoTokens.forDiary("a", "gone")
        val open = room("a", listOf(entry("e1")))

        assertEquals(FieldPhotoTarget.Gone, fieldPhotoTarget(token, "gone", open = open, stored = null))
    }

    @Test
    fun `a room that was deleted while the copy ran leaves an orphan too`() {
        val token = PhotoTokens.forDiary("a", "e1")
        val open = room("b", listOf(entry("e1")))

        assertEquals(FieldPhotoTarget.Gone, fieldPhotoTarget(token, "e1", open = open, stored = null))
    }

    @Test
    fun `a defect photo takes the same path as a diary photo`() {
        val token = PhotoTokens.forDefect("a", "d1")
        val defect = com.constructar.core.model.DefectItem(
            id = "d1",
            createdAtEpochMs = 1_767_225_600_000L,
            title = "Riss",
        )
        val open = room("a").copy(defects = listOf(defect))

        val target = fieldPhotoTarget(token, "d1", open = open, stored = null)

        assertTrue(target is FieldPhotoTarget.OnScreen)
        assertEquals(token, (target as FieldPhotoTarget.OnScreen).project.defects.single().photo)
    }

    @Test
    fun `a room photo is not attached through the entry path`() {
        val token = PhotoTokens.forRoom("a")
        val open = room("a")

        assertEquals(FieldPhotoTarget.Gone, fieldPhotoTarget(token, "a", open = open, stored = null))
    }
}
