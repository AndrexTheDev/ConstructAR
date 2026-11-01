/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export

import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.math.Vec3
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
        assertTrue(csv.contains("Article;Order length (m);Clips;Couplings;Bends"))
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
}
