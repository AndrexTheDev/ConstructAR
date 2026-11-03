/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import com.constructar.core.bom.MaterialLine
import com.constructar.core.model.ConduitKind
import com.constructar.core.model.ConduitSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence the procurement table gets when bends make the cut piece shorter than the run.
 *
 * The screen has shown "Bend deduction 0.90 m" per line since finding 114 - the number a fitter
 * cuts to - and the printed protocol never mentioned it, so the page the customer signs carried an
 * order length that is not the length of the piece that goes in, with nothing saying why (finding
 * 141). Lines without a bend must add nothing at all: a note under every line would be noise on a
 * document that is already long.
 */
class BendDeductionNoteTest {

    private val template = PdfLabels().procurementDeduction

    private fun line(material: String, deduction: Float) = MaterialLine(
        bomKey = "pipe|$material",
        kind = ConduitKind.PIPE,
        spec = ConduitSpec.pipe(20f, "Pipe Ø20").copy(material = material),
        runCount = 1,
        totalMeters = 12f,
        withWasteMeters = 12.96f,
        wasteFactor = 0.08f,
        clipCount = 15,
        couplingCount = 3,
        elbow90Count = 2,
        elbow45Count = 1,
        otherBendCount = 0,
        bendDeductionMeters = deduction,
        lowConfidenceRuns = 0,
    )

    @Test
    fun `a line with a bend says what the fitter cuts off`() {
        val notes = bendDeductionNotes(listOf(line("Composite pipe Ø20 mm", 0.5f)), template)

        assertEquals(1, notes.size)
        val note = notes.first()
        assertTrue("names the article: $note", note.contains("Composite pipe Ø20 mm"))
        assertTrue("names the metres: $note", note.contains("0.50 m"))
        assertTrue("says the order length is not reduced: $note", note.contains("not reduced"))
    }

    @Test
    fun `lines without a bend claim nothing`() {
        assertEquals(emptyList<String>(), bendDeductionNotes(emptyList(), template))
        assertEquals(emptyList<String>(), bendDeductionNotes(listOf(line("Pipe", 0f)), template))
        // Rounding noise, not a number a fitter can cut to.
        assertEquals(
            emptyList<String>(),
            bendDeductionNotes(listOf(line("Pipe", MIN_DEDUCTION_METERS)), template),
        )
    }

    @Test
    fun `only the lines with a bend get a sentence`() {
        val notes = bendDeductionNotes(
            listOf(line("Pipe A", 0.3f), line("Pipe B", 0f), line("Pipe C", 1.2f)),
            template,
        )
        assertEquals(2, notes.size)
        assertTrue(notes[0].contains("Pipe A"))
        assertTrue(notes[1].contains("Pipe C"))
        assertEquals("1.20 m", notes[1].substringAfter("deduction ").substringBefore(" on"))
    }

    @Test
    fun `a translation without placeholders still renders a line`() {
        val plain = "Bend deduction"
        assertEquals(listOf(plain), bendDeductionNotes(listOf(line("Pipe", 0.5f)), plain))
    }
}
