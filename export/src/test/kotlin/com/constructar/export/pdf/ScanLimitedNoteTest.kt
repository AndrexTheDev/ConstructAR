/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line under the summary that says the capture is only part of the room.
 *
 * The TSDF volume has a hard block budget, and when it is spent the mesh freezes at its current
 * extent while the operator keeps walking. The HUD says so (finding 97), but the protocol did
 * not: it printed "Scanned triangles: 89,000" for a room whose other half was never in the
 * volume, and that is the document the customer signs and the office files (finding 100). Three
 * things are tested: the sentence is printed for a limited capture, it is printed for nothing
 * else, and the translation is passed through untouched - no placeholder is left for a reader to
 * puzzle over and no `%` can make the writer throw.
 */
class ScanLimitedNoteTest {

    private val labels = PdfLabels()

    @Test
    fun `a capture that hit the volume limit is named on the protocol`() {
        val note = scanLimitedNote(scanLimited = true, template = labels.scanLimitedNote)

        assertEquals("the template must be printed as given", labels.scanLimitedNote, note)
        assertTrue(
            "the note must say the model is only part of the room: $note",
            note!!.contains("part of the room"),
        )
        assertTrue(
            "the lengths are unaffected and the note must say which half of the document is " +
                "affected: $note",
            note.contains("unaffected"),
        )
    }

    @Test
    fun `a capture that fitted prints nothing`() {
        assertNull(
            "a whole-room capture must not carry a warning about a limit nobody reached",
            scanLimitedNote(scanLimited = false, template = labels.scanLimitedNote),
        )
    }

    @Test
    fun `the note carries no format placeholder`() {
        val note = scanLimitedNote(scanLimited = true, template = labels.scanLimitedNote)!!

        assertFalse(
            "a `%` in a string the writer sends through String.format would throw or print " +
                "garbage: $note",
            note.contains("%"),
        )
        assertFalse("no empty argument form either: $note", note.contains("%s"))
    }
}
