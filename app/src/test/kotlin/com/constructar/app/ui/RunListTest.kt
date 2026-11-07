/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitConfidence
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteLabels
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which run a row of the project's list is about (finding 278).
 *
 * The list printed the kind, the cable and the size - and never the run's name, which is the one
 * string the protocol and the CSV print. Two runs of the same cable on the same wall therefore
 * rendered as two identical rows, one delete button away from losing the wrong one.
 *
 * These tests hold the two rules that make a row tell the truth: its identity is the *stored* label
 * (never one derived from the cable, so an imported or hand-corrected name is shown as it is), and
 * its metres are the reported ones, so the list still adds up to the project's total (finding 277).
 * Saving must not erase a measurement's review state either (finding 279): the notices below use
 * the same metrics as procurement, and uncertainty and recovery coexist instead of competing for
 * one badge.
 */
class RunListTest {

    private fun route(
        id: String,
        label: String,
        lengthMeters: Float,
        confidence: Float = 0.95f,
        hitSource: HitSource = HitSource.PLANE_POLYGON,
        recovered: Boolean = false,
    ) = ConduitRoute(
        id = id,
        label = label,
        spec = ConduitSpec.cable(20f),
        nodes = listOf(
            RouteNodeRecord(
                id = "$id-1",
                position = Vec3(0f, 1.2f, 0f),
                normal = Vec3(0f, 0f, 1f),
                surface = SurfaceKind.WALL,
                hitSource = hitSource,
                confidence = confidence,
            ),
            RouteNodeRecord(
                id = "$id-2",
                position = Vec3(lengthMeters, 1.2f, 0f),
                normal = Vec3(0f, 0f, 1f),
                surface = SurfaceKind.WALL,
                hitSource = hitSource,
                confidence = confidence,
            ),
        ),
        createdAtEpochMs = 1_000L,
        recovered = recovered,
    )

    private fun project(vararg routes: ConduitRoute, scale: Double = 1.0) = ProjectRecord(
        id = "p1",
        name = "Bad Nord",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        routes = routes.toList(),
        calibrationScale = scale,
    )

    @Test
    fun `two runs of the same cable are told apart by the name the protocol prints`() {
        // The defect in one test: everything the row used to print is equal.
        val spec = ConduitSpec.cable(20f)
        val first = RouteLabels.next(spec, emptyList())
        val second = RouteLabels.next(spec, listOf(first))
        val project = project(route("a", first, 3f), route("b", second, 2f))

        val rows = RunList.rows(project)

        assertEquals("same kind", rows[0].route.spec.kind, rows[1].route.spec.kind)
        assertEquals("same cable", rows[0].route.spec.label, rows[1].route.spec.label)
        assertEquals("same size", rows[0].route.spec.sizeLabel, rows[1].route.spec.sizeLabel)
        assertNotEquals("and the only thing that tells them apart", rows[0].identity, rows[1].identity)
        assertEquals(first, rows[0].identity)
        assertEquals(second, rows[1].identity)
    }

    @Test
    fun `the identity is the stored name, not one derived from the cable`() {
        // A run can carry a name that was imported, renamed in the project file, or written by an
        // older version. The list shows what is stored - the string the protocol prints - instead
        // of guessing a name from the spec and contradicting the paper.
        val project = project(route("a", "Wand 3 - Steckdosen", 3f))

        assertEquals("Wand 3 - Steckdosen", RunList.rows(project).single().identity)
    }

    @Test
    fun `every run appears once, in the order the project stores it`() {
        val project = project(route("a", "CABLE 20 mm #1", 3f), route("b", "CABLE 20 mm #3", 2f))

        val rows = RunList.rows(project)

        assertEquals(listOf("CABLE 20 mm #1", "CABLE 20 mm #3"), rows.map { it.identity })
        assertEquals(listOf("a", "b"), rows.map { it.route.id })
    }

    @Test
    fun `a row's metres are the reported ones, so the list adds up to the total`() {
        val project = project(route("a", "CABLE 20 mm #1", 3f), route("b", "CABLE 20 mm #2", 2f), scale = 1.05)

        val rows = RunList.rows(project)

        assertEquals(listOf(3.15f, 2.10f), rows.map { it.meters })
        assertEquals(
            "the rows still add up to the number above them",
            project.totalLengthMeters,
            rows.map { it.meters }.sum(),
            1e-4f,
        )
    }

    @Test
    fun `a saved run below the shared confidence bar still asks for review`() {
        val rows = RunList.rows(project(route("a", "CABLE 20 mm #1", 3f, confidence = 0.70f)))

        assertEquals(listOf(RunList.Notice.UNCERTAIN), rows.single().notices)
    }

    @Test
    fun `a run exactly at the shared confidence bar needs no warning`() {
        val rows = RunList.rows(
            project(route("a", "CABLE 20 mm #1", 3f, confidence = HitConfidence.RUN_TRUSTED)),
        )

        assertTrue(rows.single().notices.isEmpty())
    }

    @Test
    fun `a soft hit source stays uncertain even with a perfect imported confidence`() {
        // Procurement deliberately checks the source as well as the number. The project row uses
        // the same metrics, so a hand-edited or migrated feature point cannot look measured here
        // while the same run is counted as uncertain on the order sheet.
        val rows = RunList.rows(
            project(
                route(
                    id = "a",
                    label = "CABLE 20 mm #1",
                    lengthMeters = 3f,
                    confidence = 1f,
                    hitSource = HitSource.FEATURE_POINT,
                ),
            ),
        )

        assertEquals(listOf(RunList.Notice.UNCERTAIN), rows.single().notices)
    }

    @Test
    fun `recovery never hides an uncertain measurement, and uncertainty never hides recovery`() {
        val rows = RunList.rows(
            project(route("a", "CABLE 20 mm #1", 3f, confidence = 0.4f, recovered = true)),
        )

        assertEquals(
            listOf(RunList.Notice.UNCERTAIN, RunList.Notice.RECOVERED),
            rows.single().notices,
        )
    }

    @Test
    fun `a firm uninterrupted run carries no warning at all`() {
        val rows = RunList.rows(project(route("a", "CABLE 20 mm #1", 3f)))

        assertTrue(rows.single().notices.isEmpty())
    }

    @Test
    fun `an empty project has no rows, and nothing to name`() {
        val rows = RunList.rows(project())

        assertTrue("a room with no runs writes no rows", rows.isEmpty())
    }
}
