/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitKind
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SketchPoint
import com.constructar.core.model.SurfaceKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the wall screen is allowed to say about a room.
 *
 * The three engines behind it are tested in `:core`; what is tested here is the assembly, because
 * the assembly is where the wrong answers come from: a ring that is not closed (three walls of
 * four), a calibration factor applied to the nodes instead of the metres, a mounting height taken
 * from an AR origin that was never the floor. Every case below is one of those.
 */
class WallPlanTest {

    private val square = FloorplanSketch(
        points = listOf(
            SketchPoint(0f, 0f),
            SketchPoint(4f, 0f),
            SketchPoint(4f, 3f),
            SketchPoint(0f, 3f),
        ),
    )

    private fun project(
        sketch: FloorplanSketch? = square,
        scale: Double = 1.0,
        floorHeightM: Float? = null,
        nodes: List<Vec3> = emptyList(),
    ): ProjectRecord = ProjectRecord(
        id = "p1",
        name = "Kitchen",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        sketch = sketch,
        calibrationScale = scale,
        floorHeightM = floorHeightM,
        routes = if (nodes.isEmpty()) {
            emptyList()
        } else {
            listOf(
                ConduitRoute(
                    id = "r1",
                    label = "Ring",
                    spec = ConduitSpec(kind = ConduitKind.CABLE, label = "NYM 3×1.5"),
                    nodes = nodes.mapIndexed { index, position ->
                        RouteNodeRecord(
                            id = "n$index",
                            position = position,
                            normal = Vec3(0f, 0f, 1f),
                            surface = SurfaceKind.WALL,
                            hitSource = com.constructar.core.model.HitSource.PLANE_POLYGON,
                        )
                    },
                    createdAtEpochMs = 0L,
                ),
            )
        },
    )

    @Test
    fun `a closed ring is four walls, the closing one included`() {
        val walls = WallPlanBuilder.wallsOf(square)

        // 4 + 3 + 4 + 3 m: the edge from the last corner back to the first is a wall like any
        // other, and leaving it out would under-order a board and a half on a 4 x 3 m room.
        assertEquals(4, walls.size)
        assertEquals(14f, walls.map { it.lengthM }.sum(), 1e-3f)
    }

    @Test
    fun `an open ring has no walls at all`() {
        val open = square.copy(closure = FloorplanSketch.Closure.Open)

        assertTrue(WallPlanBuilder.wallsOf(open).isEmpty())
        assertFalse(WallPlanBuilder.build(project(sketch = open)).valid)
    }

    @Test
    fun `a ring closed by hand is a ring - the operator walked the room`() {
        val byHand = square.copy(closure = FloorplanSketch.Closure.DrawnByHand)
        val plan = WallPlanBuilder.build(project(sketch = byHand))

        assertEquals(4, plan.walls.size)
        assertEquals(14f, plan.surface.totalLengthM, 1e-3f)
    }

    @Test
    fun `the calibration factor scales the metres, not the node count`() {
        val plan = WallPlanBuilder.build(project(scale = 1.1))

        // 14 m of run at a factor of 1.1 is 15.40 m, and 15.40 m of 2.50 m wall is 38.50 m².
        // The height is *not* scaled: 2.50 m is the assumption the estimate is made under, not a
        // length the session measured, and a factor applied to it would price a room that does
        // not exist.
        assertEquals(15.4f, plan.surface.totalLengthM, 1e-2f)
        assertEquals(38.5f, plan.surface.grossAreaM2, 1e-2f)
        assertEquals(4, plan.walls.size)
    }

    @Test
    fun `without a tracked floor there are no zones`() {
        val plan = WallPlanBuilder.build(
            project(floorHeightM = null, nodes = listOf(Vec3(0f, -1.4f, 0f))),
        )

        // ARCore's origin is wherever the session started; 30 cm above *that* is not 30 cm above
        // the finished floor, so the honest answer is no zones rather than a wrong socket.
        assertNull(plan.installations)
        assertNull(plan.floorHeightM)
        assertEquals(0, plan.pointCount)
    }

    @Test
    fun `with a tracked floor the heights are measured from it`() {
        // The phone started 1.4 m above the floor, so a node 30 cm above the floor sits at -1.1 m
        // in session coordinates. A socket is 30 cm up; a switch, at the same node plus 75 cm, is
        // in the switch zone.
        val plan = WallPlanBuilder.build(
            project(
                floorHeightM = -1.4f,
                nodes = listOf(
                    Vec3(0.5f, -1.1f, 0.1f),
                    Vec3(0.5f, -0.35f, 0.1f),
                ),
            ),
        )

        val installations = plan.installations ?: error("zones expected")
        assertEquals(2, installations.assignments.size)
        assertEquals(1, installations.socketCount)
        assertEquals(1, installations.switchCount)
        assertEquals(0, installations.rejectedCount)
        assertEquals(-1.4f, plan.floorHeightM ?: 0f, 1e-4f)
    }

    @Test
    fun `a point below the floor is assigned to no zone and counted as refused`() {
        val plan = WallPlanBuilder.build(
            project(floorHeightM = 0f, nodes = listOf(Vec3(0f, -0.2f, 0f))),
        )

        val installations = plan.installations ?: error("zones expected")
        assertEquals(1, installations.assignments.size)
        assertEquals(1, installations.rejectedCount)
        assertEquals(0, installations.socketCount)
    }

    @Test
    fun `the take-off reports what the room is made of, and its assumptions`() {
        val plan = WallPlanBuilder.build(project())

        assertEquals(35f, plan.surface.grossAreaM2, 1e-2f)
        assertEquals(35f, plan.surface.netAreaM2, 1e-2f)
        // No opening has a source yet, and the screen has to be able to say so rather than print
        // a silent "0.00 m² deducted" beside a room that has a door.
        assertEquals(0, plan.openingsRecorded)
        // 35 m² of wall at a tenth of waste is 38.5 m² of board, i.e. 13 boards of 3.125 m².
        assertTrue(plan.drywall.valid)
        assertEquals(13, plan.drywall.boards)
        assertTrue(plan.drywall.screws > 0)
    }

    @Test
    fun `a project file written before the floor existed still loads`() {
        // The field is defaulted and the repository's JSON writes defaults and skips nulls, so a
        // room recorded by the last release keeps loading - and a floor that was measured
        // survives the round trip instead of quietly reverting to "no zones".
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val legacy = """{"id":"p1","name":"Kitchen","createdAtEpochMs":0,"updatedAtEpochMs":0}"""

        val loaded = json.decodeFromString(ProjectRecord.serializer(), legacy)
        assertNull(loaded.floorHeightM)

        val stored = loaded.copy(floorHeightM = -1.4f)
        val reread = json.decodeFromString(
            ProjectRecord.serializer(),
            json.encodeToString(ProjectRecord.serializer(), stored),
        )
        assertEquals(-1.4f, reread.floorHeightM ?: 0f, 1e-4f)
    }
}
