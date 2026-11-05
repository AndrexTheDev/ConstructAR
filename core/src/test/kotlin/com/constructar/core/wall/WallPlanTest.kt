/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.model.ConduitKind
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SketchPoint
import com.constructar.core.model.SurfaceKind
import com.constructar.core.math.Vec3
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the wall screen may say about a room, assembled from `:core` alone.
 *
 * The four engines behind it are tested where they live; what is tested here is the assembly,
 * because that is where the wrong answers come from: a ring that is not closed (three walls of
 * four), a calibration factor applied to the nodes instead of the metres, openings that never
 * reach the subtraction, a mounting height taken from a session other than the floor's.
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

    private fun node(
        position: Vec3,
        sessionId: String = "s1",
        surface: SurfaceKind = SurfaceKind.WALL,
    ) = RouteNodeRecord(
        id = "n-${position.x}-${position.y}-${position.z}",
        position = position,
        normal = Vec3(0f, 0f, 1f),
        surface = surface,
        hitSource = HitSource.PLANE_POLYGON,
        sessionId = sessionId,
    )

    private fun project(
        sketch: FloorplanSketch? = square,
        scale: Double = 1.0,
        floorHeightM: Float? = null,
        sessionFloorHeights: Map<String, Float> = emptyMap(),
        openings: List<PlacedOpening> = emptyList(),
        nodes: List<RouteNodeRecord> = emptyList(),
    ): ProjectRecord = ProjectRecord(
        id = "p1",
        name = "Kitchen",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        sketch = sketch,
        calibrationScale = scale,
        floorHeightM = floorHeightM,
        sessionFloorHeights = sessionFloorHeights,
        openings = openings,
        routes = if (nodes.isEmpty()) {
            emptyList()
        } else {
            listOf(
                ConduitRoute(
                    id = "r1",
                    label = "Ring",
                    spec = ConduitSpec(kind = ConduitKind.CABLE, label = "NYM 3×1.5"),
                    nodes = nodes,
                    createdAtEpochMs = 0L,
                ),
            )
        },
    )

    @Test
    fun `a closed ring is four walls, the closing one included`() {
        val walls = ringWalls(square)

        // 4 + 3 + 4 + 3 m: the edge from the last corner back to the first is a wall like any
        // other, and leaving it out would under-order a board and a half on a 4 x 3 m room.
        assertEquals(4, walls.size)
        assertEquals(14f, walls.map { it.lengthM }.sum(), 1e-3f)
    }

    @Test
    fun `an open ring has no walls at all`() {
        val open = square.copy(closure = FloorplanSketch.Closure.Open)

        assertTrue(ringWalls(open).isEmpty())
        assertFalse(wallPlan(project(sketch = open)).valid)
    }

    @Test
    fun `a ring closed by hand is a ring - the operator walked the room`() {
        val byHand = square.copy(closure = FloorplanSketch.Closure.DrawnByHand)
        val plan = wallPlan(project(sketch = byHand))

        assertEquals(4, plan.walls.size)
        assertEquals(14f, plan.surface.totalLengthM, 1e-3f)
    }

    @Test
    fun `the calibration factor scales the metres, not the assumed height`() {
        val plan = wallPlan(project(scale = 1.1))

        // 14 m of run at a factor of 1.1 is 15.40 m, and 15.40 m of 2.50 m wall is 38.50 m².
        // The height is *not* scaled: 2.50 m is the assumption the estimate is made under, not a
        // length the session measured.
        assertEquals(15.4f, plan.surface.totalLengthM, 1e-2f)
        assertEquals(38.5f, plan.surface.grossAreaM2, 1e-2f)
        assertEquals(4, plan.walls.size)
    }

    @Test
    fun `an opening is deducted from the wall it sits on, and only from that wall`() {
        // A 0.885 x 2.01 m door is 1.78 m² off a 4 x 2.5 m wall.
        val plan = wallPlan(project(openings = listOf(PlacedOpening.door(wallIndex = 0, offsetM = 1f))))

        assertEquals(1, plan.openingsRecorded)
        assertEquals(35f - 1.77885f, plan.surface.netAreaM2, 1e-2f)
        // Wall 0 is the 4 m run: 10 m² gross, 1.77885 m² of door, 8.22115 m² of board. Wall 1 is
        // the 3 m run and loses nothing.
        assertEquals(10f - 1.77885f, plan.surface.lines[0].netAreaM2, 1e-2f)
        assertEquals(1.77885f, plan.surface.lines[0].openingAreaM2, 1e-2f)
        assertEquals(7.5f, plan.surface.lines[1].netAreaM2, 1e-2f)
        assertEquals(1, plan.surface.lines[0].openingsSubtracted)
        assertEquals(0, plan.surface.lines[1].openingsSubtracted)
    }

    @Test
    fun `an opening wider than its wall is clipped and counted, not silently absorbed`() {
        // 3 m of window on a 3 m wall is the whole wall: net 0, and the clip is reported.
        val plan = wallPlan(
            project(
                openings = listOf(
                    PlacedOpening(
                        wallIndex = 1,
                        kind = OpeningKind.WINDOW,
                        offsetM = 1f,
                        widthM = 3f,
                        sillHeightM = 0.9f,
                        heightM = 1.4f,
                    ),
                ),
            ),
        )

        assertTrue(plan.surface.clippedOpenings >= 1)
        assertEquals(1, plan.surface.rejected.size + plan.surface.clippedOpenings)
        assertTrue(plan.surface.netAreaM2 < 35f)
    }

    @Test
    fun `without any measured floor there are no zones`() {
        val plan = wallPlan(project(nodes = listOf(node(Vec3(0f, -1.4f, 0f)))))

        // ARCore's origin is wherever the session started; 30 cm above *that* is not 30 cm above
        // the finished floor, so the honest answer is no zones rather than a wrong socket.
        assertNull(plan.installations)
        assertEquals(0, plan.pointCount)
        assertEquals(1, plan.noFloorPoints)
        assertFalse(plan.zonesCoverEveryPoint)
    }

    @Test
    fun `with a tracked floor the heights are measured from it`() {
        // The phone started 1.4 m above the floor, so a node 30 cm above the floor sits at -1.1 m
        // in session coordinates. A socket is 30 cm up; a switch, at the same node plus 75 cm, is
        // in the switch zone.
        val plan = wallPlan(
            project(
                sessionFloorHeights = mapOf("s1" to -1.4f),
                floorHeightM = -1.4f,
                nodes = listOf(
                    node(Vec3(0.5f, -1.1f, 0.1f)),
                    node(Vec3(0.5f, -0.35f, 0.1f)),
                ),
            ),
        )

        val installations = plan.installations ?: error("zones expected")
        assertEquals(2, installations.assignments.size)
        assertEquals(1, installations.socketCount)
        assertEquals(1, installations.switchCount)
        assertEquals(0, installations.rejectedCount)
        assertEquals(-1.4f, plan.floorHeightM ?: 0f, 1e-4f)
        assertTrue(plan.zonesCoverEveryPoint)
    }

    @Test
    fun `a point below the floor is assigned to no zone and counted as refused`() {
        val plan = wallPlan(
            project(
                sessionFloorHeights = mapOf("s1" to 0f),
                floorHeightM = 0f,
                nodes = listOf(node(Vec3(0f, -0.2f, 0f))),
            ),
        )

        val installations = plan.installations ?: error("zones expected")
        assertEquals(1, installations.assignments.size)
        assertEquals(1, installations.rejectedCount)
        assertEquals(0, installations.socketCount)
    }

    @Test
    fun `a node from another session is not classified and is not silently dropped`() {
        // s1 has a floor, s2 does not: the node from s2 cannot be given a mounting height, and the
        // count says so instead of pretending the room has one point (finding 217).
        val plan = wallPlan(
            project(
                sessionFloorHeights = mapOf("s1" to -1.4f),
                floorHeightM = -1.4f,
                nodes = listOf(
                    node(Vec3(0.5f, -1.1f, 0.1f), sessionId = "s1"),
                    node(Vec3(3f, 2.2f, 1f), sessionId = "s2"),
                ),
            ),
        )

        val installations = plan.installations ?: error("zones expected")
        assertEquals(1, installations.assignments.size)
        assertEquals(1, installations.socketCount)
        assertEquals(1, plan.foreignSessionPoints)
        assertFalse(plan.zonesCoverEveryPoint)
        assertEquals(1, plan.sessionCount)
    }

    @Test
    fun `the take-off reports what the room is made of, and its assumptions`() {
        val plan = wallPlan(project())

        assertEquals(35f, plan.surface.grossAreaM2, 1e-2f)
        assertEquals(35f, plan.surface.netAreaM2, 1e-2f)
        assertEquals(0, plan.openingsRecorded)
        // 35 m² of wall at a tenth of waste is 38.5 m² of board, i.e. 13 boards of 3.125 m².
        assertTrue(plan.drywall.valid)
        assertEquals(13, plan.drywall.boards)
        assertTrue(plan.drywall.screws > 0)
    }

    @Test
    fun `a project file written before the floor and openings existed still loads`() {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val legacy = """{"id":"p1","name":"Kitchen","createdAtEpochMs":0,"updatedAtEpochMs":0}"""

        val loaded = json.decodeFromString(ProjectRecord.serializer(), legacy)
        assertNull(loaded.floorHeightM)
        assertTrue(loaded.sessionFloorHeights.isEmpty())
        assertTrue(loaded.openings.isEmpty())

        val stored = loaded.copy(
            floorHeightM = -1.4f,
            sessionFloorHeights = mapOf("s1" to -1.4f),
            openings = listOf(PlacedOpening.door(0, 1f)),
        )
        val reread = json.decodeFromString(
            ProjectRecord.serializer(),
            json.encodeToString(ProjectRecord.serializer(), stored),
        )
        assertEquals(-1.4f, reread.floorHeightM ?: 0f, 1e-4f)
        assertEquals(-1.4f, reread.sessionFloorHeights["s1"] ?: 0f, 1e-4f)
        assertEquals(1, reread.openings.size)
    }
}
