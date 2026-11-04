/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export

import com.constructar.export.pdf.PageFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a note has to turn the page (finding 174).
 *
 * The notes under the protocol's tables carry operator text — the list of runs that are in the
 * paperwork but not in the model is built by joining their labels — so how many lines they need
 * is not the writer's to decide. Until finding 174 the writer drew them as if each were one line:
 * the procurement note and the exclusion note under it printed on the same baseline, and a note
 * long enough to reach the footer ran straight through it.
 */
class PageFlowTest {

    /** A4 text area: 36 pt margin, 11 pt per note line, footer at 842 − 32 − 12. */
    private val flow = PageFlow(top = 36, lineHeight = 11, bottom = 798)

    @Test
    fun `a line that clears the footer fits`() {
        assertTrue("the first line of a page always fits", flow.fits(flow.top))
        assertTrue("the last line above the footer fits", flow.fits(flow.bottom - flow.lineHeight))
        assertFalse("one line lower is already in the footer", flow.fits(flow.bottom - flow.lineHeight + 1))
    }

    @Test
    fun `a note of operator labels turns pages until it is printed`() {
        // Thirty labels in one sentence: at ~110 characters to the line, four lines is what a
        // dozen runs produce, and the page can be nearly full when they arrive.
        var y = 780
        var pages = 1
        repeat(4) {
            if (!flow.fits(y)) {
                pages++
                y = flow.top
            }
            y += flow.lineHeight
        }
        assertEquals("the note must continue on a second page, not in the footer", 2, pages)
        assertTrue("the continuation starts at the top margin", y <= flow.top + 4 * flow.lineHeight)
    }

    @Test
    fun `a note that fits costs no page`() {
        var y = 300
        var pages = 1
        repeat(4) {
            if (!flow.fits(y)) {
                pages++
                y = flow.top
            }
            y += flow.lineHeight
        }
        assertEquals(1, pages)
        assertEquals("four lines advance the cursor by four lines", 300 + 4 * flow.lineHeight, y)
    }
}
