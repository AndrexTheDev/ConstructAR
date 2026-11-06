/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import com.constructar.core.model.ScanClosure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line the summary gets when the trajectory was folded back (finding 251).
 *
 * The HUD says it while the dialog is open and after it closes (finding 250), the project file
 * keeps it, and this is where it reaches the customer: a protocol that prints a corrected room
 * without saying the positions were corrected presents an inference as a measurement (finding
 * 217). Two ways of having nothing to say - a scan without a closure and a project with no scan -
 * must both end in `null`, so an ordinary job gains no sentence about a closure nobody confirmed.
 */
class ClosureNoteTest {

    private val labels = PdfLabels()

    @Test
    fun `a room that was folded back says so, with the numbers`() {
        val note = closureNote(
            ScanClosure(closures = 1, movedNodes = 42, nodes = 380, maxShiftMetres = 0.183f),
            labels.closureNote,
            labels.closureNoteUnfinished,
        )

        assertTrue("the note has to name how far the geometry moved: $note", note!!.contains("0.18 m"))
        assertTrue("and how many nodes moved: $note", note.contains("42 of 380"))
        assertEquals("no placeholder may survive the formatting", -1, note.indexOf("%"))
    }

    @Test
    fun `a scan without a closure adds nothing`() {
        assertNull(
            "an ordinary room must not carry a sentence about a correction nobody confirmed",
            closureNote(null, labels.closureNote, labels.closureNoteUnfinished),
        )
    }

    @Test
    fun `a record with no confirmation in it is not a closure either`() {
        // Zero confirmations with a record present would be a contradiction; the note refuses to
        // print it rather than reporting a correction of nothing.
        assertNull(closureNote(ScanClosure(closures = 0), labels.closureNote, labels.closureNoteUnfinished))
    }

    @Test
    fun `a confirmation whose solver stopped prints the sentence, never the zeros`() {
        // Finding 252: "0 of 0 nodes" would be a correction of nothing, presented as one.
        val note = closureNote(
            ScanClosure(closures = 1, converged = false),
            labels.closureNote,
            labels.closureNoteUnfinished,
        )

        assertEquals(labels.closureNoteUnfinished, note)
        assertFalse("no number may stand in for a result: $note", note!!.contains("0 of 0"))
    }

    @Test
    fun `a record the optimiser could not have written is not printed`() {
        // Restored from a backup, or edited in between: the file is not a trusted input
        // (finding 253). A gap in the document is honest; "42 of 10 nodes" is not.
        val impossible = ScanClosure(closures = 1, movedNodes = 42, nodes = 10, maxShiftMetres = 0.1f)

        assertNull(
            "an impossible record must print nothing",
            closureNote(impossible, labels.closureNote, labels.closureNoteUnfinished),
        )
    }
}
