/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.DefectItem
import com.constructar.core.model.DiaryEntry
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.PhotoTokens
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which photo the limited budget is spent on.
 *
 * Finding 42: the field book kept twelve thumbnails and the protocol eight, and both were filled
 * in stored order - diary first - so the picture of an open defect, the one the customer is asked
 * to sign as outstanding, was the first thing to fall off the list. Nothing said a photo existed
 * and was not shown; a row without a thumbnail looks exactly like a row without a photo.
 *
 * These tests are about order, not pixels: the budget is spent on open defects, then resolved
 * ones, then the diary, newest first inside each group.
 */
class FieldPhotosTest {

    private fun diary(id: String, photo: Boolean = true) = DiaryEntry(
        id = id,
        createdAtEpochMs = 0L,
        text = "Eintrag $id",
        photo = if (photo) PhotoTokens.forDiary("p1", id) else null,
    )

    private fun defect(id: String, resolved: Boolean, photo: Boolean = true) = DefectItem(
        id = id,
        createdAtEpochMs = 0L,
        title = "Mangel $id",
        resolved = resolved,
        photo = if (photo) PhotoTokens.forDefect("p1", id) else null,
    )

    private fun project(
        diary: List<DiaryEntry> = emptyList(),
        defects: List<DefectItem> = emptyList(),
    ) = ProjectRecord(
        id = "p1",
        name = "Wohnung 3. OG",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        diary = diary,
        defects = defects,
    )

    @Test
    fun `an open defect outranks the newest diary photo`() {
        val ordered = FieldPhotos.ordered(
            project(
                // Stored order: diary first, and it is the biggest list.
                diary = listOf(diary("e3"), diary("e2"), diary("e1")),
                defects = listOf(defect("d1", resolved = false)),
            ),
        )
        assertEquals(PhotoTokens.forDefect("p1", "d1"), ordered.first())
        assertEquals(
            listOf(
                PhotoTokens.forDefect("p1", "d1"),
                PhotoTokens.forDiary("p1", "e3"),
                PhotoTokens.forDiary("p1", "e2"),
                PhotoTokens.forDiary("p1", "e1"),
            ),
            ordered,
        )
    }

    @Test
    fun `an open defect outranks a resolved one, and resolved still outranks the diary`() {
        val ordered = FieldPhotos.ordered(
            project(
                diary = listOf(diary("e1")),
                defects = listOf(
                    defect("d-old", resolved = true),
                    defect("d-open", resolved = false),
                ),
            ),
        )
        assertEquals(
            listOf(
                PhotoTokens.forDefect("p1", "d-open"),
                PhotoTokens.forDefect("p1", "d-old"),
                PhotoTokens.forDiary("p1", "e1"),
            ),
            ordered,
        )
    }

    @Test
    fun `entries without a photo spend none of the budget`() {
        val ordered = FieldPhotos.ordered(
            project(
                diary = listOf(diary("e2"), diary("e1", photo = false)),
                defects = listOf(defect("d1", resolved = false, photo = false), defect("d2", resolved = false)),
            ),
        )
        // A token is a file name; naming one that was never written would use a slot for nothing.
        assertEquals(listOf(PhotoTokens.forDefect("p1", "d2"), PhotoTokens.forDiary("p1", "e2")), ordered)
    }

    @Test
    fun `the screen budget keeps the photos that carry the claim`() {
        val many = project(
            // Newest first, the way the model stores the diary: e20 is the most recent entry.
            diary = (20 downTo 1).map { diary("e$it") },
            defects = listOf(defect("d1", resolved = false), defect("d2", resolved = true)),
        )
        val shown = FieldPhotos.ordered(many).take(FieldPhotos.SCREEN_BUDGET)

        assertEquals(FieldPhotos.SCREEN_BUDGET, shown.size)
        assertTrue(shown.contains(PhotoTokens.forDefect("p1", "d1")), "the open defect's photo must survive")
        assertTrue(shown.contains(PhotoTokens.forDefect("p1", "d2")))
        assertEquals(PhotoTokens.forDiary("p1", "e20"), shown[2])
        // The old behaviour (diary first, twelve thumbnails) showed no defect photo at all.
        assertEquals(10, shown.count { it.contains("__diary_") })
    }

    @Test
    fun `an empty job asks for nothing`() {
        assertEquals(emptyList(), FieldPhotos.ordered(project()))
    }
}
