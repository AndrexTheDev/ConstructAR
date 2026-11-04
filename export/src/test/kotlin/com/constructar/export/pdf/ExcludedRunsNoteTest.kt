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
 * The line under the procurement table when the list left the uncertain runs out.
 *
 * `MaterialListOptions.includeLowConfidenceRuns = false` drops a run from every total on the page,
 * and every warning the document has counts the runs that **survived** - so a protocol built from
 * such a list said nothing about uncertainty anywhere and read like a job whose every length had
 * been checked, while ordering less than the job needs. The customer signs that page (finding 161).
 */
class ExcludedRunsNoteTest {

    private val labels = PdfLabels()

    @Test
    fun `runs left out of the procurement table are named on the protocol`() {
        val note = exclusionNote(2, 6.5f, labels.procurementExcluded)

        assertTrue("a table short by whole runs must say so: $note", note != null)
        assertTrue("it names how many: $note", note!!.contains("2 runs"))
        assertTrue("and what they measured: $note", note.contains("6.50 m"))
        assertTrue(
            "and tells the reader it is looking at an incomplete total: $note",
            note.contains("totals above do not include"),
        )
    }

    @Test
    fun `a table that left nothing out carries no exclusion note`() {
        assertNull(
            "a complete procurement table must not carry a sentence about completeness",
            exclusionNote(0, 0f, labels.procurementExcluded),
        )
    }

    @Test
    fun `the count and the metres are the numbers the note prints`() {
        // One placeholder swapped for another is how a warning ends up saying "1 runs" about
        // twelve - the arguments are pinned here in the order the template uses them.
        val note = exclusionNote(12, 41f, labels.procurementExcluded)!!

        assertEquals("the runs come first and the metres second", true, note.startsWith("12 runs"))
        assertTrue("the metres are formatted like every other length: $note", note.contains("41.00 m"))
    }
}
