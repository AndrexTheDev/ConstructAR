/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app

import com.constructar.app.ui.SiteFileTexts
import com.constructar.app.ui.buildCostSection
import com.constructar.app.ui.buildSiteFileSections
import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import com.constructar.core.model.WorkSession
import com.constructar.core.price.MoneyFormatter
import com.constructar.core.price.Pricing
import com.constructar.core.units.LengthFormatter
import com.constructar.export.csv.MaterialCsvWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One job, every surface, one set of numbers.
 *
 * The app shows the same measurement on four surfaces: the phone, the protocol, the bill of
 * materials and the wholesaler's order list. Each of them has its own formatter for its own good
 * reason (the CSV must use dots, the page must not depend on the phone's language, the phone must
 * follow the operator), which is exactly how two of them drift apart - findings 39, 46 and 47 were
 * all one fact with two owners.
 *
 * The earlier tests check each piece in isolation. This one runs a whole small job through all of
 * them and asserts that the numbers agree, so a change to one surface that quietly changes its
 * convention fails here rather than in front of a customer.
 */
class EndToEndJobTest {

    private val texts = SiteFileTexts(
        customerTitle = "Customer",
        customerLabel = "Customer",
        addressLabel = "Address",
        phoneLabel = "Phone",
        timesTitle = "Working time",
        columnDate = "Date",
        columnStart = "From",
        columnEnd = "To",
        columnDuration = "Duration",
        totalLabel = "Total",
        runningLabel = "running",
        diaryTitle = "Site diary",
        defectsTitle = "Defects",
        columnDefect = "Defect",
        columnSeverity = "Severity",
        columnStatus = "Status",
        openStatus = "open",
        resolvedStatus = "resolved",
        severityLow = "low",
        severityMedium = "medium",
        severityHigh = "high",
        hoursMinutes = "%1\$d h %2\$d min",
        minutesOnly = "%1\$d min",
        costsTitle = "Costs",
        labourLabel = "Labour",
        materialLabel = "Material",
        netTotalLabel = "Net",
        netNote = "No VAT.",
        photosTitle = "Photos",
        photoDiaryPrefix = "Diary",
        photoDefectPrefix = "Defect",
    )

    private fun run(id: String, spec: ConduitSpec, meters: Float): ConduitRoute {
        fun node(nodeId: String, x: Float) = RouteNodeRecord(
            nodeId, Vec3(x, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON,
        )
        return ConduitRoute(
            id = id,
            label = "Run $id",
            spec = spec,
            nodes = listOf(node("$id-a", 0f), node("$id-b", meters)),
            createdAtEpochMs = 0L,
        )
    }

    /** 2 h 30 min on site, two conduit types, a 250,00 € material lump sum. */
    private fun job(): ProjectRecord {
        val sessions = listOf(WorkSession(id = "s1", startEpochMs = 0L, endEpochMs = 9_000_000L))
        return ProjectRecord.empty("p1", "Bad Nord", 0L).copy(
            routes = listOf(
                run("r1", ConduitSpec.cable(16f, "Cable Ø16"), 12.5f),
                run("r2", ConduitSpec.pipe(20f, "Pipe Ø20"), 4.25f),
            ),
            sessions = sessions,
            materialLumpSumCents = 25_000L,
        )
    }

    @Test
    fun `the priced hours and the time table tell the same story`() {
        val project = job()
        val list = MaterialListBuilder.build(project, nowMs = 9_000_000L)
        assertEquals(2, list.lines.size)

        val cost = buildCostSection(project, Pricing.DEFAULT_HOURLY_RATE_CENTS, texts, nowMs = 9_000_000L)
        val time = buildSiteFileSections(project, texts, nowMs = 9_000_000L)
            .single { it.title == texts.timesTitle }

        // 2 h 30 min is 120,00 € at 48,00 €/h, and the page says the same duration in both places.
        assertEquals("Total: 2 h 30 min", time.note)
        assertTrue(cost!!.paragraphs.first(), cost.paragraphs.first().startsWith("Labour: 2 h 30 min x "))
        assertEquals(12_000L, Pricing.labourCents(9_000_000L, Pricing.DEFAULT_HOURLY_RATE_CENTS))
        // The page states the calculation (hours x rate) and the two amounts that follow from it,
        // in the money formatter every surface uses: 2 h 30 min at 48,00 € an hour is 120,00 €,
        // plus the 250,00 € lump sum makes 370,00 €.
        assertEquals(
            "Labour: 2 h 30 min x " + MoneyFormatter.format(Pricing.DEFAULT_HOURLY_RATE_CENTS.toLong()),
            cost.paragraphs[0],
        )
        assertEquals("Material: " + MoneyFormatter.format(25_000L), cost.paragraphs[1])
        assertEquals("Net: " + MoneyFormatter.format(37_000L), cost.paragraphs[2])
    }

    @Test
    fun `the phone, the spreadsheet and the order list print the same order length`() {
        val project = job()
        val list = MaterialListBuilder.build(project, nowMs = 0L)
        val csv = MaterialCsvWriter.write(list, MaterialCsvWriter.Options(includeBom = false), "2026-03-14 10:15")
        val order = MaterialCsvWriter.writeOrderList(list, MaterialCsvWriter.Options(includeBom = false), "2026-03-14 10:15")

        val onScreen = LengthFormatter.format(list.totalWithWasteMeters)
        val onPage = csv.split("\r\n").first { it.startsWith("Total metres (incl. waste)") }.split(";")[1]
        val onOrder = order.split("\r\n").first { it.startsWith("Total;") }.split(";")[1]

        // One number, three surfaces: the screen adds the unit, the two files carry the digits.
        assertEquals(onScreen, "$onPage m")
        assertEquals(onPage, onOrder)
        assertEquals(LengthFormatter.number(list.totalWithWasteMeters), onPage)
    }

    @Test
    fun `the run detail names exactly the runs the project has`() {
        val project = job()
        val list = MaterialListBuilder.build(project, nowMs = 0L)
        val details = project.routes.map { Triple(it.label, it.spec.sizeLabel, it.polylineLengthMeters) }
        val csv = MaterialCsvWriter.write(
            materialList = list,
            options = MaterialCsvWriter.Options(includeBom = false, includeRunDetail = true),
            generatedAtLabel = "2026-03-14 10:15",
            runDetails = details,
        )

        val block = csv.split("\r\n").dropWhile { !it.startsWith("Run;") }.drop(1).filter { it.isNotBlank() }
        assertEquals(project.routes.size, block.size)
        assertTrue(block.all { it.startsWith("Run r") })
    }

    @Test
    fun `the order list carries the same fittings as the bill`() {
        val project = job()
        val list = MaterialListBuilder.build(project, nowMs = 0L)
        val order = MaterialCsvWriter.writeOrderList(list, MaterialCsvWriter.Options(includeBom = false))

        val total = order.split("\r\n").first { it.startsWith("Total;") }.split(";")
        assertEquals(list.totalClips.toString(), total[2])
        assertEquals(list.totalCouplings.toString(), total[3])
        assertEquals(list.totalElbows.toString(), total[4])
    }
}
