/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.bom.MaterialListOptions
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a calibration factor does to the numbers a project *reports*.
 *
 * The factor is stored on the project while routes remain raw evidence. A central transformed copy
 * feeds rendering, material quantities and exports; reported distances pass through the same
 * arithmetic exactly once, so every surface agrees without mutating the persisted measurement.
 */
class CalibratedReportTest {

    private fun route(id: String, lengthMeters: Float, confidence: Float = 0.95f) = ConduitRoute(
        id = id,
        label = id,
        spec = ConduitSpec.cable(10f),
        nodes = listOf(
            RouteNodeRecord(
                id = "$id-1",
                position = Vec3(0f, 1.2f, 0f),
                normal = Vec3(0f, 0f, 1f),
                surface = SurfaceKind.WALL,
                hitSource = HitSource.PLANE_POLYGON,
                confidence = confidence,
            ),
            RouteNodeRecord(
                id = "$id-2",
                position = Vec3(lengthMeters, 1.2f, 0f),
                normal = Vec3(0f, 0f, 1f),
                surface = SurfaceKind.WALL,
                hitSource = HitSource.PLANE_POLYGON,
                confidence = confidence,
            ),
        ),
        createdAtEpochMs = 1_000L,
    )

    private fun project(vararg routes: ConduitRoute, scale: Double = 1.0) = ProjectRecord(
        id = "p1",
        name = "Room",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        routes = routes.toList(),
        calibrationScale = scale,
    )

    @Test
    fun `a project without a calibration reports what the phone measured`() {
        val project = project(route("a", 3f), route("b", 2f))

        assertFalse(project.isCalibrated)
        assertEquals(5f, project.totalLengthMeters, 1e-4f)
        assertEquals(3f, project.calibratedMeters(project.routes.first()), 1e-4f)
    }

    @Test
    fun `a calibrated project reports corrected metres, and names itself calibrated`() {
        // The phone measured 5.00 m; the folding rule says every metre is 5 % short.
        val project = project(route("a", 3f), route("b", 2f), scale = 1.05)

        assertTrue(project.isCalibrated)
        assertEquals(5.25f, project.totalLengthMeters, 1e-4f)
        assertEquals(3.15f, project.calibratedMeters(project.routes.first()), 1e-4f)
    }

    @Test
    fun `the rows of a calibrated project add up to its total`() {
        // The invariant every list on the phone and in the protocol depends on (finding 277). The
        // project screen's rows printed the raw measurement while the total above them was
        // corrected, so a 5.00 m job read as 3.00 + 2.00 under "5.25 m" - a list the operator can
        // see is wrong and cannot explain. Summed the way the screens sum it: printed metres.
        val project = project(route("a", 3f), route("b", 2f), scale = 1.05)

        val rows = project.routes.map { project.calibratedMeters(it) }
        // kotlin.test takes the message last - the JUnit order does not compile against this
        // overload, which is the trap `verify_sources` refuses elsewhere in the tree.
        assertEquals(
            project.totalLengthMeters,
            rows.sum(),
            1e-4f,
            "the rows must add up to the number above them",
        )
    }

    @Test
    fun `a measured length is corrected once, in one function`() {
        // `reportedMeters` is the only multiplication site: the total goes through it, a route goes
        // through it, and the AR screen's running total - saved runs and the run in hand alike -
        // goes through it. A second application would print 13.44 m as 14.11 m.
        val project = project(route("a", 4f), scale = 1.05)

        assertEquals(4.2f, project.reportedMeters(4f), 1e-4f)
        assertEquals(4.2f, project.calibratedMeters(project.routes.first()), 1e-4f)
        assertEquals(4.2f, project.totalLengthMeters, 1e-4f)
    }

    @Test
    fun `the bill of materials follows the same factor`() {
        val list = MaterialListBuilder.build(
            project(route("a", 3f), route("b", 2f), scale = 1.05),
            nowMs = 7L,
        )

        val line = list.lines.single()
        assertEquals(5.25f, line.totalMeters, 1e-3f)
        assertEquals(5.25f, list.totalMeters, 1e-3f)
        // Waste is a percentage of the corrected metres, so the order length moves with them.
        assertEquals(5.25f * (1f + line.wasteFactor), line.withWasteMeters, 1e-3f)
    }

    @Test
    fun `uniform calibration preserves bend angles and these fitting counts`() {
        // Material metrics are measured after transforming the points. Uniform scaling preserves
        // bend angles; clip/coupling counts are recalculated from calibrated metres and may change
        // when they cross a spacing/stock threshold (these lengths deliberately do not cross one).
        val uncalibrated = MaterialListBuilder.build(project(route("a", 4f)), nowMs = 7L)
        val calibrated = MaterialListBuilder.build(project(route("a", 4f), scale = 1.05), nowMs = 7L)

        assertEquals(
            uncalibrated.lines.single().clipCount,
            calibrated.lines.single().clipCount,
        )
        assertEquals(
            uncalibrated.lines.single().couplingCount,
            calibrated.lines.single().couplingCount,
        )
        assertEquals(
            uncalibrated.lines.single().elbow90Count,
            calibrated.lines.single().elbow90Count,
        )
    }

    @Test
    fun `material quantities are calculated after calibration rather than scaled afterwards`() {
        // 55 cm needs two clips at 60 cm spacing. Once calibrated to 60.5 cm it crosses the
        // spacing boundary and needs three; multiplying only the final metre total would miss it.
        val raw = MaterialListBuilder.build(project(route("a", 0.55f)), nowMs = 7L)
        val calibrated = MaterialListBuilder.build(project(route("a", 0.55f), scale = 1.10), nowMs = 7L)

        assertEquals(2, raw.lines.single().clipCount)
        assertEquals(0.605f, calibrated.lines.single().totalMeters, 1e-4f)
        assertEquals(3, calibrated.lines.single().clipCount)
    }

    @Test
    fun `an excluded run's metres are corrected too, or the page would not add up`() {
        // The low-confidence run is taken out of the list, and the metres it would have added are
        // kept so the page can say what is missing. Those metres have to be in the same unit as
        // the printed ones.
        val soft = route("soft", 2f, confidence = 0.2f)
        val list = MaterialListBuilder.build(
            project(route("a", 3f), soft, scale = 1.05),
            options = MaterialListOptions(includeLowConfidenceRuns = false),
            nowMs = 7L,
        )

        assertEquals(1, list.excludedLowConfidenceRuns)
        assertEquals(2.1f, list.excludedLowConfidenceMeters, 1e-3f)
        assertEquals(3.15f, list.totalMeters, 1e-3f)
    }
}
