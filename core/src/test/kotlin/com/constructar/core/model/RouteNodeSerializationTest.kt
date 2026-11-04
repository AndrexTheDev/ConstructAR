/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.Vec3
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An anchor id is a runtime handle, not part of the project file (finding 165).
 *
 * Every node of a measured run carries the id of the ARCore anchor it sits on, and the app
 * releases those anchors the moment the run is finished (finding 164). Saving the ids anyway put
 * one dead uuid per node into every project on the phone, and a stored id is an invitation for a
 * later caller to look one up - which is why the draft path already stripped them by hand. The
 * field is `@Transient` now, so both paths get the same guarantee from the model.
 */
class RouteNodeSerializationTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun node(anchorId: String? = null) = RouteNodeRecord(
        id = "node-1",
        position = Vec3(1f, 2f, 3f),
        normal = Vec3(0f, 0f, 1f),
        surface = SurfaceKind.WALL,
        hitSource = HitSource.PLANE_POLYGON,
        anchorId = anchorId,
    )

    private fun route(nodes: List<RouteNodeRecord>) = ConduitRoute(
        id = "route-1",
        label = "CABLE 16 #1",
        spec = ConduitSpec.cable(16f, "Cable Ø16"),
        nodes = nodes,
        createdAtEpochMs = 1_700_000_000_000L,
    )

    @Test
    fun `a saved run carries no anchor ids`() {
        val saved = json.encodeToString(ConduitRoute.serializer(), route(listOf(node("anchor-1"), node("anchor-2"))))

        assertFalse("the runtime handle must not reach the file: $saved", saved.contains("anchorId"))
        assertFalse("nor its value", saved.contains("anchor-1"))
        assertTrue("the position still does", saved.contains("1.0"))
    }

    @Test
    fun `the id is still there in memory`() {
        // Transient is about the file, not the session: the measuring path hands every node its
        // anchor, and `RouteSession.onFrame` refines the node through it.
        val live = node("anchor-1")
        assertEquals("anchor-1", live.anchorId)
        assertEquals("and copying keeps it", "anchor-1", live.withPosition(Vec3(0f, 0f, 0f)).anchorId)
    }

    @Test
    fun `a project saved before this change still loads`() {
        // Files written by every earlier build carry `anchorId` on each node. They must open with
        // the field simply absent rather than fail: `ignoreUnknownKeys` is what the repository
        // relies on, and a project that no longer loads is worse than a dead uuid in it.
        val encoded = json.encodeToString(ConduitRoute.serializer(), route(listOf(node())))
        val legacy = encoded.replace(
            "\"confidence\"",
            "\"anchorId\": \"anchor-of-a-session-long-gone\", \"confidence\"",
        )
        assertTrue("the fixture has to look like an older file", legacy.contains("anchorId"))

        val restored = json.decodeFromString(ConduitRoute.serializer(), legacy)

        assertNull("the dead id is dropped, not trusted", restored.nodes.single().anchorId)
        assertEquals("and the geometry is intact", 3f, restored.nodes.single().position.z, 1e-6f)
        assertEquals("as is the measurement", 1, restored.nodes.size)
    }
}
