/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.Vec3
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteDraftEvidenceTest {
    private val nodes = listOf(
        RouteNodeRecord("a", Vec3.ZERO, Vec3.Z_AXIS, SurfaceKind.WALL, HitSource.PLANE_POLYGON),
        RouteNodeRecord("b", Vec3.X_AXIS, Vec3.Z_AXIS, SurfaceKind.WALL, HitSource.PLANE_POLYGON),
    )
    private val evidence = listOf(
        AnchorEvidence(
            nodeId = "b",
            previousNodeId = "a",
            relativeVectorMeters = Vec3.X_AXIS,
            featureCluster = FeatureCluster("b", listOf(Vec3(0.1f, 0f, 0f), Vec3(0f, 0.1f, 0f))),
        ),
    )

    @Test
    fun `relative vectors and feature clusters survive JSON`() {
        val draft = RouteDraft("p", ConduitSpec.cable(16f), nodes, evidence = evidence)
        val text = Json.encodeToString(RouteDraft.serializer(), draft)
        val restored = Json.decodeFromString(RouteDraft.serializer(), text)
        assertEquals(Vec3.X_AXIS, restored.evidence.single().relativeVectorMeters)
        assertEquals(2, restored.evidence.single().featureCluster?.relativePoints?.size)
        assertTrue(text.contains("featureCluster"))
    }

    @Test
    fun `legacy draft recovery remains measurement only`() {
        val route = RouteDraft("p", ConduitSpec.cable(16f), nodes).toRoute("r", "r", 1L)!!
        assertTrue(route.recovered)
        assertFalse(route.relocalised)
    }

    @Test
    fun `accepted relocalisation is distinct from an unplaced recovered route`() {
        val restored = nodes.map { it.copy(position = it.position + Vec3(4f, 0f, 2f), sessionId = "new") }
        val route = RouteDraft("p", ConduitSpec.cable(16f), nodes, evidence = evidence)
            .toRoute("r", "r", 1L, restored)!!
        assertFalse(route.recovered)
        assertTrue(route.relocalised)
        assertEquals("new", route.nodes.first().sessionId)
        assertNull(route.nodes.first().anchorId)
    }
}
