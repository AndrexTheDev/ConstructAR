/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence the photo appendix gets when it cannot print every photo on the job.
 *
 * The appendix holds eight pictures - a page budget, not a taste - while eighteen photos on one
 * job is an ordinary Tuesday. Nothing said which of the two had happened, so the operator handed
 * the customer a page headed "Photo documentation" and both of them believed it was all of it; the
 * photos that were left out looked like photos that had never been taken. The rule here is the
 * quiet one: two of the three cases end in `null`, because a document must never announce a cut
 * that did not happen.
 */
class PhotoAppendixNoteTest {

    private val template = PdfLabels().photosNote

    @Test
    fun `a page that holds every photo claims nothing`() {
        assertNull(photoAppendixNote(shown = 8, total = 8, template = template))
        assertNull(photoAppendixNote(shown = 3, total = 3, template = template))
    }

    @Test
    fun `a caller that named no total claims nothing`() {
        // The writer's default is 0 - "the caller did not say". Reading that as "the job has no
        // photos" would print "8 of 0 photos" at the top of the appendix.
        assertNull(photoAppendixNote(shown = 8, total = 0, template = template))
        assertNull(photoAppendixNote(shown = 8, total = 3, template = template))
    }

    @Test
    fun `a job with more photos than the page says so, with both counts`() {
        val note = photoAppendixNote(shown = 8, total = 20, template = template)!!

        assertTrue("the printed count must be named: $note", note.contains("8"))
        assertTrue("the job's count must be named: $note", note.contains("20"))
        assertTrue("and where the others are: $note", note.contains("in the app"))
    }

    @Test
    fun `a translation without the placeholders still renders`() {
        val plain = "Photos are printed from the site file."

        assertEquals(plain, photoAppendixNote(shown = 8, total = 20, template = plain))
    }
}
