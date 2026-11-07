/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.relocalisation.AnchorGraphRelocaliser
import com.constructar.arengine.relocalisation.AnchorObservation
import com.constructar.core.math.Vec3
import com.constructar.core.model.AnchorEvidence
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.FeatureCluster
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteDraft
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnchorGraphRelocaliserTest {

    private val source = listOf(
        Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 2f, 0f), Vec3(0f, 0f, 3f),
    )
    private val rotation = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private val translation = Vec3(5f, -2f, 0.5f)
    private val featureOffsets = listOf(Vec3(0.10f, 0f, 0f), Vec3(0f, 0.08f, 0f))

    @Test
    fun `kabsch restores every point and normal in the new session`() {
        val result = AnchorGraphRelocaliser.restore(draft(source), observations(source), "new-session")
        val success = assertIs<AnchorGraphRelocaliser.Result.Success>(result)
        assertEquals(0f, success.residualMeters, 1e-4f)
        success.nodes.forEachIndexed { index, node ->
            val expected = expected(source[index])
            assertEquals(expected.x, node.position.x, 1e-4f)
            assertEquals(expected.y, node.position.y, 1e-4f)
            assertEquals(expected.z, node.position.z, 1e-4f)
            assertEquals("new-session", node.sessionId)
            assertEquals(1f, node.normal.z, 1e-4f)
        }
        val route = draft(source).toRoute("r", "r", 10L, success.nodes)!!
        assertFalse(route.recovered)
        assertTrue(route.relocalised)
    }

    @Test
    fun `three non collinear wall anchors are enough even though they are planar`() {
        val wall = source.take(3)
        val result = AnchorGraphRelocaliser.restore(draft(wall), observations(wall), "wall-session")
        assertIs<AnchorGraphRelocaliser.Result.Success>(result)
    }

    @Test
    fun `collinear observations are rejected instead of inventing a rotation`() {
        val line = listOf(Vec3.ZERO, Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f))
        val result = AnchorGraphRelocaliser.restore(draft(line), observations(line), "new")
        assertEquals(
            AnchorGraphRelocaliser.Failure.DEGENERATE_GEOMETRY,
            assertIs<AnchorGraphRelocaliser.Result.Rejected>(result).reason,
        )
    }

    @Test
    fun `an outlier beyond the metric residual is rejected`() {
        val observations = observations(source).toMutableList()
        observations[2] = observations[2].copy(position = observations[2].position + Vec3(0.3f, 0f, 0f))
        val result = AnchorGraphRelocaliser.restore(draft(source), observations, "new")
        assertEquals(
            AnchorGraphRelocaliser.Failure.RESIDUAL_TOO_LARGE,
            assertIs<AnchorGraphRelocaliser.Result.Rejected>(result).reason,
        )
    }

    @Test
    fun `matching positions with contradictory visual clusters are rejected`() {
        val observations = observations(source).map { observation ->
            observation.copy(
                featureCluster = observation.featureCluster.copy(
                    relativePoints = listOf(Vec3(2f, 0f, 0f), Vec3(0f, 2f, 0f)),
                ),
            )
        }
        val result = AnchorGraphRelocaliser.restore(draft(source), observations, "new")
        assertEquals(
            AnchorGraphRelocaliser.Failure.FEATURE_MISMATCH,
            assertIs<AnchorGraphRelocaliser.Result.Rejected>(result).reason,
        )
    }

    @Test
    fun `legacy drafts fall back to measurements only instead of fabricated coordinates`() {
        val legacy = draft(source).copy(evidence = emptyList())
        val result = AnchorGraphRelocaliser.restore(legacy, observations(source), "new")
        assertEquals(
            AnchorGraphRelocaliser.Failure.INSUFFICIENT_EVIDENCE,
            assertIs<AnchorGraphRelocaliser.Result.Rejected>(result).reason,
        )
        assertTrue(legacy.toRoute("r", "r", 1L)!!.recovered)
    }

    private fun expected(point: Vec3) = Vec3(
        rotation[0] * point.x + rotation[1] * point.y + rotation[2] * point.z,
        rotation[3] * point.x + rotation[4] * point.y + rotation[5] * point.z,
        rotation[6] * point.x + rotation[7] * point.y + rotation[8] * point.z,
    ) + translation

    private fun observations(points: List<Vec3>): List<AnchorObservation> = points.mapIndexed { index, point ->
        val id = "n$index"
        AnchorObservation(
            nodeId = id,
            position = expected(point),
            normal = Vec3(0f, 0f, 1f),
            featureCluster = FeatureCluster(
                nodeId = id,
                relativePoints = featureOffsets.map { offset ->
                    Vec3(-offset.y, offset.x, offset.z)
                },
            ),
        )
    }

    private fun draft(points: List<Vec3>): RouteDraft {
        val nodes = points.mapIndexed { index, point ->
            RouteNodeRecord(
                id = "n$index",
                position = point,
                normal = Vec3(0f, 0f, 1f),
                surface = SurfaceKind.WALL,
                hitSource = HitSource.PLANE_POLYGON,
                confidence = 0.95f,
            )
        }
        return RouteDraft(
            projectId = "p",
            spec = ConduitSpec.cable(16f),
            nodes = nodes,
            evidence = nodes.mapIndexed { index, node ->
                AnchorEvidence(
                    nodeId = node.id,
                    previousNodeId = nodes.getOrNull(index - 1)?.id,
                    relativeVectorMeters = nodes.getOrNull(index - 1)?.let { node.position - it.position },
                    featureCluster = FeatureCluster(node.id, featureOffsets),
                )
            },
        )
    }
}
