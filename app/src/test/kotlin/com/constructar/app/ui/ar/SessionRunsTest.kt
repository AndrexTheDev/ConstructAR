/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which committed runs the AR view may draw.
 *
 * A run is stored as world-space points, and the world belongs to one ARCore session. The app
 * already refused to draw *recovered* runs for exactly that reason, and then drew every other run
 * measured on an earlier visit - the same dead world origin, the same arbitrary spot in the room
 * (finding 129). A misled operator is worse off than an empty view: the tube looks like a run he
 * measured, so he may route next to it.
 *
 * The lengths, the CSV and the price never enter this decision.
 */
class SessionRunsTest {

    private fun run(id: String, recovered: Boolean = false) = ConduitRoute(
        id = id,
        label = id,
        spec = ConduitSpec.cable(16f, "Cable Ø16"),
        nodes = listOf(
            RouteNodeRecord("$id-a", Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON),
            RouteNodeRecord("$id-b", Vec3(2f, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON),
        ),
        createdAtEpochMs = 0L,
        recovered = recovered,
    )

    @Test
    fun `a run finished in this session is drawn`() {
        val now = run("now")

        assertEquals(listOf(now), SessionRuns.placeable(setOf("now"), listOf(now)))
    }

    @Test
    fun `a run measured on an earlier visit is not`() {
        val yesterday = run("yesterday")
        val now = run("now")

        assertEquals(listOf(now), SessionRuns.placeable(setOf("now"), listOf(yesterday, now)))
    }

    @Test
    fun `a recovered run is never drawn, because no session produced it`() {
        val recovered = run("draft", recovered = true)

        // `finish()` is the only source of the set, and it never produced this run: the process
        // that measured it is gone. The filter therefore needs no second look at `recovered`.
        assertTrue(SessionRuns.placeable(emptySet(), listOf(recovered)).isEmpty())
    }

    @Test
    fun `the drawn order is the project's order, not the order of the taps`() {
        val first = run("first")
        val second = run("second")

        assertEquals(
            listOf(second, first),
            SessionRuns.placeable(setOf("second", "first"), listOf(second, first)),
        )
    }

    @Test
    fun `an empty session draws nothing at all`() {
        assertTrue(SessionRuns.placeable(emptySet(), listOf(run("a"), run("b"))).isEmpty())
    }
}
