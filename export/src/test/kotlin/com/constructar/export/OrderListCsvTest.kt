/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export

import com.constructar.core.bom.MaterialLine
import com.constructar.core.bom.MaterialList
import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitKind
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import com.constructar.export.csv.MaterialCsvWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order list the wholesaler gets.
 *
 * It is a second file, not a second mode of the bill of materials: the operator reads the full
 * CSV (summary, every column, a per-run breakdown) while the buyer needs one line per article.
 * These cases pin the parts that make it usable - one row per conduit type, the same number
 * convention as the other CSV in the folder, and the same guard against a project name that a
 * spreadsheet would execute.
 */
class OrderListCsvTest {

    private fun project(name: String = "Bad Nord", material: String = "LSZH 3 × 2.5 mm²"): ProjectRecord {
        val spec = ConduitSpec.cable(16f, "Cable Ø16").copy(material = material)
        fun node(id: String, x: Float) = RouteNodeRecord(
            id, Vec3(x, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON,
        )
        val route = ConduitRoute(
            id = "r1",
            label = "Kitchen – worktop",
            spec = spec,
            nodes = listOf(node("n1", 0f), node("n2", 3.5f)),
            createdAtEpochMs = 0L,
        )
        return ProjectRecord.empty("p1", name, 0L).copy(routes = listOf(route))
    }

    @Test
    fun `one line per article, with the fittings, and no per-run detail`() {
        val list = MaterialListBuilder.build(project(), nowMs = 0L)
        val csv = MaterialCsvWriter.writeOrderList(list, MaterialCsvWriter.Options(includeBom = false), "2026-03-14 10:15")

        val lines = csv.trim().split("\r\n")
        assertTrue(lines.first(), lines.first().startsWith("Project;"))
        assertTrue(
            csv,
            csv.contains(
                "Article;Order length (m);Clips;Couplings;90° elbows;45° elbows;Other bends",
            ),
        )
        // One conduit type in the project: header, project, timestamp, one row, blank, total.
        assertEquals(1, lines.count { it.startsWith("LSZH 3 × 2.5 mm²;") })
        assertEquals(1, lines.count { it.startsWith("Total;") })
        // The full CSV's run-detail block has no business in a purchase order.
        assertFalse(csv.contains("Run;"))
        assertFalse(csv.contains("Kitchen"))
    }

    @Test
    fun `the order length is the one the protocol prints`() {
        val list = MaterialListBuilder.build(project(), nowMs = 0L)
        val csv = MaterialCsvWriter.writeOrderList(list, MaterialCsvWriter.Options(includeBom = false))

        val row = csv.split("\r\n").first { it.startsWith("LSZH") }
        val cells = row.split(";")
        // 3.50 m plus the cable family's waste factor, formatted with a dot like every other
        // number in the export - and the same figure the protocol and the screen show.
        assertTrue(cells[1], cells[1].matches(Regex("""\d+\.\d{2}""")))
        assertEquals(
            String.format(java.util.Locale.ROOT, "%.2f", list.totalWithWasteMeters),
            csv.split("\r\n").first { it.startsWith("Total;") }.split(";")[1],
        )
    }

    @Test
    fun `a spec without a material falls back to its size label, and the name is still guarded`() {
        val list = MaterialListBuilder.build(project(name = "=cmd|' /C calc'!A0", material = ""), nowMs = 0L)
        val csv = MaterialCsvWriter.writeOrderList(list, MaterialCsvWriter.Options(includeBom = false))

        // No procureable description: the row is still identifiable by its size.
        assertTrue(csv.contains("Ø 16 mm"))
        // And the project name became text for the spreadsheet, not a formula.
        assertTrue(csv, csv.contains("Project;'=cmd|' /C calc'!A0"))
    }
    private fun line(elbow90: Int, elbow45: Int, other: Int) = MaterialLine(
        bomKey = "cable|16",
        kind = ConduitKind.CABLE,
        spec = ConduitSpec.cable(16f, "Cable Ø16").copy(material = "LSZH 3 × 2.5 mm²"),
        runCount = 3,
        totalMeters = 10f,
        withWasteMeters = 10.7f,
        wasteFactor = 0.07f,
        clipCount = 17,
        couplingCount = 2,
        elbow90Count = elbow90,
        elbow45Count = elbow45,
        otherBendCount = other,
        bendAllowanceMeters = 0.5f,
        lowConfidenceRuns = 0,
    )

    private fun listOfLines(vararg lines: MaterialLine) =
        MaterialList(
            projectId = "p1",
            projectName = "Bad Nord",
            lines = lines.toList(),
            generatedAtEpochMs = 0L,
        )

    @Test
    fun `the bend articles stay apart, so the list can be ordered from`() {
        // 2 x 90°, 3 x 45°, 1 bend of unknown angle. A single "Bends: 6" tells the wholesaler
        // nothing he can put in a van (finding 90).
        val csv = MaterialCsvWriter.writeOrderList(
            listOfLines(line(elbow90 = 2, elbow45 = 3, other = 1)),
            MaterialCsvWriter.Options(includeBom = false),
        )

        val rows = csv.trim().split("\r\n")
        val header = rows.first { it.startsWith("Article;") }.split(";")
        val article = rows.first { it.startsWith("LSZH") }.split(";")
        val total = rows.first { it.startsWith("Total;") }.split(";")

        assertEquals(
            "every row must have one cell per header: header=$header",
            header.size,
            article.size,
        )
        assertEquals("header=$header", header.size, total.size)
        assertEquals(listOf("2", "3", "1"), article.takeLast(3))
        assertEquals(listOf("2", "3", "1"), total.takeLast(3))
    }

    @Test
    fun `the totals add each bend article across every conduit`() {
        val csv = MaterialCsvWriter.writeOrderList(
            listOfLines(
                line(elbow90 = 2, elbow45 = 3, other = 1),
                line(elbow90 = 1, elbow45 = 0, other = 4),
            ),
            MaterialCsvWriter.Options(includeBom = false),
        )

        val total = csv.trim().split("\r\n").first { it.startsWith("Total;") }.split(";")
        // 3 x 90°, 3 x 45°, 5 of unknown angle - summed per article, never merged.
        assertEquals(listOf("3", "3", "5"), total.takeLast(3))
    }

    @Test
    fun `an uncertain hit under the order list is named, not hidden`() {
        // The screen says it, the bill of materials has a column for it and the protocol flags
        // the run - but the order list, the document that turns a measurement into money at the
        // wholesaler's counter, said nothing (finding 105).
        val csv = MaterialCsvWriter.writeOrderList(
            listOfLines(line(elbow90 = 2, elbow45 = 0, other = 0).copy(lowConfidenceRuns = 3)),
            MaterialCsvWriter.Options(includeBom = false),
        )

        val rows = csv.trim().split("\r\n")
        val note = rows.last()
        assertTrue("the note must be the last row, under the total: $note", note.startsWith("Note;"))
        assertTrue("it names how many runs are affected: $note", note.contains("uncertain hit: 3"))
        assertTrue(
            "and it stays below the total, so a reader of the seven columns is unaffected",
            rows.indexOfFirst { it.startsWith("Total;") } < rows.lastIndex,
        )
    }

    @Test
    fun `an order list built only from measured hits carries no note`() {
        val csv = MaterialCsvWriter.writeOrderList(
            listOfLines(line(elbow90 = 1, elbow45 = 1, other = 1)),
            MaterialCsvWriter.Options(includeBom = false),
        )

        // The absence of the note is a claim too: an operator who does not see it has to be able
        // to trust that every length on the sheet was measured.
        assertFalse(csv, csv.contains("Note;"))
    }

    @Test
    fun `the note counts every conduit on the order`() {
        val csv = MaterialCsvWriter.writeOrderList(
            listOfLines(
                line(elbow90 = 0, elbow45 = 0, other = 0).copy(lowConfidenceRuns = 2),
                line(elbow90 = 0, elbow45 = 0, other = 0).copy(lowConfidenceRuns = 1),
            ),
            MaterialCsvWriter.Options(includeBom = false),
        )

        val note = csv.trim().split("\r\n").last()
        assertTrue("both articles are counted, not just the first: $note", note.contains("uncertain hit: 3"))
    }
}
