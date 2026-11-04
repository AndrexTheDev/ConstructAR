/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import com.constructar.core.bom.MaterialList
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.ScanSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key/value block at the top of the protocol - the first thing the customer reads.
 *
 * The summary used to carry a "Ceiling height" row behind `ceilingHeightMeters > 0f`, and no line
 * of the app ever computed a ceiling height: a row that could not appear, guarding a model field
 * that read like a measurement nobody had taken. The rows are a pure function now, and this file
 * pins what they are allowed to contain - numbers the app computed, printed once, never a zero
 * standing in for data that does not exist (finding 103).
 */
class SummaryRowsTest {

    private val labels = PdfLabels()

    private fun materialList() = MaterialList(
        projectId = "p1",
        projectName = "Bad Nord",
        lines = emptyList(),
        generatedAtEpochMs = 0L,
    )

    private fun room(scan: ScanSummary? = null) =
        ProjectRecord.empty(id = "p1", name = "Bad Nord").copy(scan = scan)

    @Test
    fun `a scanned room prints its triangle count`() {
        val rows = summaryRows(room(ScanSummary(meshTriangleCount = 89_000)), materialList(), labels)

        assertEquals(
            "the triangle count is the one summary row that comes from the scan",
            labels.labelScannedTriangles to "89,000",
            rows.last(),
        )
        assertEquals("five rows from the bill of materials, one from the scan", 6, rows.size)
    }

    @Test
    fun `a room that was never scanned prints no scan row`() {
        val rows = summaryRows(room(), materialList(), labels)

        assertEquals("nothing to say about a scan that does not exist", 5, rows.size)
        assertTrue(
            "a room with no scan must not print a triangle row",
            rows.none { it.first == labels.labelScannedTriangles },
        )
    }

    @Test
    fun `every row has a label and something to read`() {
        val rows = summaryRows(room(ScanSummary(meshTriangleCount = 1_000)), materialList(), labels)

        for ((label, value) in rows) {
            assertTrue("a row without a label prints an unreadable line", label.isNotBlank())
            assertTrue("the row '$label' has no value to read", value.isNotBlank())
        }
    }
}
