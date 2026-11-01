/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sentence under the runs table that says which runs are not in the 3D model.
 *
 * A recovered run counts in every length, every fitting and the price, but its coordinates belong
 * to an ARCore session that no longer exists, so the GLB leaves it out. The screen has a badge for
 * that; the protocol - the file the customer signs and the office imports next to the model - had
 * nothing, so a run missing from the model had no explanation anywhere.
 */
class RecoveredNoteTest {

    private fun route(label: String, recovered: Boolean) = ConduitRoute(
        id = label,
        label = label,
        spec = ConduitSpec.cable(16f, "Cable Ø16"),
        nodes = listOf(
            RouteNodeRecord("n1", Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON),
            RouteNodeRecord("n2", Vec3(2.5f, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON),
        ),
        createdAtEpochMs = 0L,
        recovered = recovered,
    )

    private val template = "Recovered after an interruption: %1\$s"

    @Test
    fun `a measured job says nothing`() {
        assertNull(recoveredRunsNote(listOf(route("CABLE 16 #1", false)), template))
        assertNull(recoveredRunsNote(emptyList(), template))
    }

    @Test
    fun `only the recovered runs are named, in the order the table prints them`() {
        val note = recoveredRunsNote(
            listOf(
                route("CABLE 16 #1", false),
                route("CABLE 16 #2", true),
                route("EMPTY 20 #1", false),
                route("EMPTY 20 #2", true),
            ),
            template,
        )

        assertEquals("Recovered after an interruption: CABLE 16 #2, EMPTY 20 #2", note)
    }
}
