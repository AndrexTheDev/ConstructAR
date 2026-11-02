/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.bom

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
 * The bill of materials is the number the customer pays, so it is tested like money.
 *
 * What is asserted here is *arithmetic between routes*, not the internals of one route: how two
 * identical runs merge into one line, how a waste override propagates, how a low-confidence run
 * is either flagged or excluded, and that the per-line numbers add up to the list totals. The
 * per-route geometry itself is covered by `PolylineMetricsTest`.
 */
class MaterialListTest {

    private fun node(
        index: Int,
        x: Float,
        z: Float,
        confidence: Float = 0.95f,
        source: HitSource = HitSource.PLANE_POLYGON,
    ) = RouteNodeRecord(
        id = "n$index",
        position = Vec3(x, 1.2f, z),
        normal = Vec3(0f, 0f, 1f),
        surface = SurfaceKind.WALL,
        hitSource = source,
        confidence = confidence,
    )

    private fun route(
        id: String,
        spec: ConduitSpec,
        lengthMeters: Float,
        confidence: Float = 0.95f,
        source: HitSource = HitSource.PLANE_POLYGON,
    ) = ConduitRoute(
        id = id,
        label = id,
        spec = spec,
        nodes = listOf(
            node(1, 0f, 0f, confidence, source),
            node(2, lengthMeters, 0f, confidence, source),
        ),
        createdAtEpochMs = 1_000L,
    )

    private fun project(vararg routes: ConduitRoute) = ProjectRecord(
        id = "p1",
        name = "Room",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        routes = routes.toList(),
    )

    private val cable = ConduitSpec.cable(10f)
    private val pipe = ConduitSpec.pipe(20f)

    @Test
    fun `two runs of the same conduit are one order line`() {
        val list = MaterialListBuilder.build(
            project(route("a", cable, 3f), route("b", cable, 2f)),
            nowMs = 7L,
        )

        assertEquals(1, list.lines.size)
        val line = list.lines.single()
        assertEquals(2, line.runCount)
        assertEquals(5f, line.totalMeters, 1e-3f)
        assertEquals(7L, list.generatedAtEpochMs)
        assertEquals("p1", list.projectId)
    }

    @Test
    fun `different conduits are different lines, sorted by kind then label`() {
        val list = MaterialListBuilder.build(
            project(route("p", pipe, 4f), route("c", cable, 4f)),
            nowMs = 0L,
        )

        assertEquals(2, list.lines.size)
        // CABLE sorts before PIPE: the list reads like a merchant's shelf, not like the room.
        assertEquals(listOf(cable.kind, pipe.kind), list.lines.map { it.kind })
        assertEquals(8f, list.totalMeters, 1e-3f)
        assertEquals(4f, list.metersFor(cable.kind), 1e-3f)
        assertEquals(4f, list.metersFor(pipe.kind), 1e-3f)
    }

    @Test
    fun `waste is applied per line and can be overridden`() {
        val list = MaterialListBuilder.build(project(route("a", cable, 10f)), nowMs = 0L)
        val line = list.lines.single()

        assertEquals(line.totalMeters * (1f + line.wasteFactor), line.withWasteMeters, 1e-3f)
        assertTrue(line.withWasteMeters > line.totalMeters, "a cable run without waste is a made-up number")

        val override = MaterialListBuilder.build(
            project(route("a", cable, 10f)),
            MaterialListOptions(wasteFactorOverride = 0.2f),
            nowMs = 0L,
        ).lines.single()
        assertEquals(0.2f, override.wasteFactor, 1e-6f)
        assertEquals(12f, override.withWasteMeters, 1e-3f)
    }

    @Test
    fun `a run measured on a soft hit is flagged, and can be excluded`() {
        val flagged = MaterialListBuilder.build(
            project(route("a", cable, 3f, confidence = 0.2f)),
            nowMs = 0L,
        ).lines.single()
        assertEquals(1, flagged.lowConfidenceRuns, "a 0.2-confidence run must be visible in the BOM")

        val excluded = MaterialListBuilder.build(
            project(route("a", cable, 3f, confidence = 0.2f), route("b", cable, 2f)),
            MaterialListOptions(includeLowConfidenceRuns = false),
            nowMs = 0L,
        ).lines.single()
        assertEquals(1, excluded.runCount)
        assertEquals(2f, excluded.totalMeters, 1e-3f)
        assertEquals(0, excluded.lowConfidenceRuns)
    }

    @Test
    fun `a confident plane hit on a well tracked surface is not flagged`() {
        val clean = MaterialListBuilder.build(project(route("a", cable, 3f)), nowMs = 0L).lines.single()
        assertEquals(0, clean.lowConfidenceRuns)
    }

    @Test
    fun `a plane hit the tracker did not trust is flagged like a guess`() {
        // The AR screen warns while the point is taken; the order list has to agree, or the
        // warning dies with the session and the customer is billed for a soft measurement.
        val soft = MaterialListBuilder.build(
            project(route("a", cable, 3f, confidence = 0.2f, source = HitSource.PLANE_POLYGON)),
            nowMs = 0L,
        ).lines.single()
        assertEquals(1, soft.lowConfidenceRuns)

        val guessed = MaterialListBuilder.build(
            project(route("b", cable, 3f, source = HitSource.FEATURE_POINT)),
            nowMs = 0L,
        ).lines.single()
        assertEquals(1, guessed.lowConfidenceRuns)
    }

    @Test
    fun `an empty room produces an empty list, not a zero line`() {
        val list = MaterialListBuilder.build(project(), nowMs = 0L)

        assertTrue(list.isEmpty)
        assertEquals(0f, list.totalMeters, 0f)
        assertEquals(0f, list.totalWithWasteMeters, 0f)
        assertEquals(0, list.totalClips)
    }

    @Test
    fun `a single node is not a run and cannot be ordered`() {
        val orphan = ConduitRoute(
            id = "one",
            label = "one",
            spec = cable,
            nodes = listOf(node(1, 0f, 0f)),
            createdAtEpochMs = 0L,
        )

        assertTrue(MaterialListBuilder.build(project(orphan), nowMs = 0L).isEmpty)
    }

    /**
     * Clip spacing is the difference between a plausible order sheet and a real one: a 6 m run
     * needs 12 clips at the 0.6 m cable default, 8 at 0.9 m and 14 at 0.5 m. The counts below are
     * the documented formula (`ceil(length / spacing) + 1` for an open run) evaluated at the
     * defaults, so a change to either number has to be deliberate.
     */
    @Test
    fun `clips are counted at the default spacing of each family`() {
        val cableLine = MaterialListBuilder.build(project(route("a", cable, 6.1f)), nowMs = 0L).lines.single()
        assertEquals(12, cableLine.clipCount, "cable at 0.6 m: ceil(6.1 / 0.6) + 1")

        val pipeLine = MaterialListBuilder.build(project(route("a", pipe, 6.1f)), nowMs = 0L).lines.single()
        assertEquals(8, pipeLine.clipCount, "pipe at 0.9 m: ceil(6.1 / 0.9) + 1")
    }

    /**
     * The clip-spacing override is part of the public options and documented as "when null, each
     * conduit family uses its own default" - which promises that a value *is* used. Until finding
     * 113 the builder never forwarded it, so every job was clipped at the family default however
     * the caller asked, and the order list carried the wrong number of the article the buyer
     * orders most of.
     */
    @Test
    fun `a clip spacing override reaches the clip count and nothing else`() {
        val list = MaterialListBuilder.build(
            project(route("a", cable, 6.1f)),
            MaterialListOptions(clipSpacingMetersOverride = 1.0f),
            nowMs = 0L,
        )
        val line = list.lines.single()
        assertEquals(8, line.clipCount, "cable at 1.0 m: ceil(6.1 / 1.0) + 1")
        // The override changes fittings, not metres: the length and the waste line stay put.
        assertEquals(6.1f, line.totalMeters, 1e-3f)
        val defaultLine = MaterialListBuilder.build(project(route("a", cable, 6.1f)), nowMs = 0L).lines.single()
        assertEquals(defaultLine.withWasteMeters, line.withWasteMeters, 1e-3f)
        assertEquals(defaultLine.totalMeters, line.totalMeters, 1e-3f)
    }

    @Test
    fun `a zero clip spacing counts no clips instead of dividing by zero`() {
        val line = MaterialListBuilder.build(
            project(route("a", cable, 6.1f)),
            MaterialListOptions(clipSpacingMetersOverride = 0f),
            nowMs = 0L,
        ).lines.single()
        assertEquals(0, line.clipCount)
        assertEquals(6.1f, line.totalMeters, 1e-3f)
    }

    @Test
    fun `the totals are the sums of the lines`() {
        val list = MaterialListBuilder.build(
            project(route("a", cable, 3f), route("b", cable, 4f), route("c", pipe, 2f)),
            nowMs = 0L,
        )

        assertEquals(list.lines.sumOf { it.clipCount.toDouble() }.toInt(), list.totalClips)
        assertEquals(list.lines.sumOf { it.couplingCount.toDouble() }.toInt(), list.totalCouplings)
        assertEquals(
            list.lines.sumOf { it.elbow90Count.toDouble() }.toInt() +
                list.lines.sumOf { it.elbow45Count.toDouble() }.toInt() +
                list.lines.sumOf { it.otherBendCount.toDouble() }.toInt(),
            list.totalElbows,
        )
        assertEquals(list.lines.fold(0f) { sum, line -> sum + line.totalMeters }, list.totalMeters, 1e-3f)
        assertTrue(list.totalClips > 0, "a 9 m run needs clips")
        assertFalse(list.lines.any { it.totalMeters <= 0f })
    }
}
