/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.bom.MaterialListOptions
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Which bill of materials the exported files are written from.
 *
 * `ExportScreen` used to fall back to `MaterialListBuilder.build(project)` with no options when the
 * view model had no cached list (a deep link straight into the export screen). That build ignores
 * the firm's waste factor - `SiteSettings.wasteFactorOverride` is only passed where the cached list
 * is built - so a firm that set 20 % would export a protocol and an order list at the cable
 * default of 7 %. The screen the operator had just left showed the 20 %; the wholesaler's file
 * said 7 % (finding 96).
 */
class ExportMaterialListTest {

    private fun project(): ProjectRecord {
        fun node(id: String, x: Float) = RouteNodeRecord(
            id, Vec3(x, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON,
        )
        val route = ConduitRoute(
            id = "r1",
            label = "CABLE 16 #1",
            spec = ConduitSpec.cable(16f, "Cable Ø16"),
            nodes = listOf(node("n1", 0f), node("n2", 10f)),
            createdAtEpochMs = 0L,
        )
        return ProjectRecord.empty("p1", "Bad Nord", 0L).copy(routes = listOf(route))
    }

    @Test
    fun `the fallback list uses the firm's waste factor`() {
        val list = exportMaterialList(project(), cached = null, wasteOverride = 0.20f)

        assertEquals(
            "10 m plus the firm's 20 %, not the cable default of 7 %",
            12.0f,
            list.totalWithWasteMeters,
            0.01f,
        )
        assertEquals(0.20f, list.lines.single().wasteFactor, 0.001f)
    }

    @Test
    fun `the default applies only when the firm set nothing`() {
        val list = exportMaterialList(project(), cached = null, wasteOverride = null)

        assertEquals(10.7f, list.totalWithWasteMeters, 0.01f)
    }

    @Test
    fun `a cached list is never rebuilt`() {
        // The cached list is the one the material screen showed; rebuilding it with other options
        // in the export path is how the screen and the paperwork came apart in the first place.
        val cached = MaterialListBuilder.build(
            project(),
            MaterialListOptions(wasteFactorOverride = 0.05f),
        )

        assertSame(cached, exportMaterialList(project(), cached = cached, wasteOverride = 0.20f))
    }
}
