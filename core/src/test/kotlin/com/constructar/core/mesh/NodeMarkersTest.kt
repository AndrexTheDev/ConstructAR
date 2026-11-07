/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.mesh

import com.constructar.core.math.Aabb
import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markers that show where the measured points are (finding 269).
 *
 * The app had a sphere builder labelled "in-scene node markers" that nothing but its own test ever
 * called: a run could hold twelve points and the glass showed none of them until the second one
 * made a tube. These tests pin the two things that decide whether the marker layer can be trusted -
 * that a node really produces a visible blob *at that node*, and that nothing degenerate (an
 * empty run, a NaN pose) ever reaches the uploader.
 */
class NodeMarkersTest {

    private fun bounds(mesh: MeshData): Aabb = requireNotNull(mesh.bounds()) { "a marker mesh without bounds" }

    @Test
    fun `an empty run draws nothing`() {
        assertTrue(NodeMarkers.markers(emptyList()).isEmpty)
    }

    @Test
    fun `one node becomes a sphere around that node`() {
        val node = Vec3(1.25f, -0.4f, 2.5f)
        val mesh = NodeMarkers.markers(listOf(node))

        assertFalse(mesh.isEmpty)
        val box = bounds(mesh)
        assertEquals(node.x, box.center.x, 1e-4f)
        assertEquals(node.y, box.center.y, 1e-4f)
        assertEquals(node.z, box.center.z, 1e-4f)
        // Two radii across: the widest vertex of an eight-segment ring sits on the axis.
        assertEquals(NodeMarkers.RADIUS_METERS * 2f, box.size.x, 2e-3f)
    }

    @Test
    fun `the marker is as big as it was asked to be`() {
        val mesh = NodeMarkers.markers(listOf(Vec3(0f, 0f, 0f)), radius = 0.1f)
        val box = bounds(mesh)
        assertEquals(0.2f, box.size.x, 5e-3f)
    }

    @Test
    fun `every node is marked, and the mesh spans all of them`() {
        val points = listOf(Vec3(0f, 0f, 0f), Vec3(3f, 0f, 0f), Vec3(3f, 0f, 4f))
        val mesh = NodeMarkers.markers(points)
        val single = NodeMarkers.markers(listOf(points.first()))

        // One sphere per node, merged: three times the vertices of one, not the last one's.
        assertEquals(single.vertexCount * points.size, mesh.vertexCount)
        assertEquals(single.triangleCount * points.size, mesh.triangleCount)
        val box = bounds(mesh)
        assertEquals(-NodeMarkers.RADIUS_METERS, box.min.x, 2e-3f)
        assertEquals(3f + NodeMarkers.RADIUS_METERS, box.max.x, 2e-3f)
        assertEquals(4f + NodeMarkers.RADIUS_METERS, box.max.z, 2e-3f)
    }

    @Test
    fun `a pose the phone could not resolve is skipped instead of poisoning the mesh`() {
        val points = listOf(Vec3(Float.NaN, 0f, 0f), Vec3(1f, 1f, 1f))
        val mesh = NodeMarkers.markers(points)

        assertFalse(mesh.isEmpty)
        assertEquals(NodeMarkers.markers(listOf(points[1])).vertexCount, mesh.vertexCount)
        val box = bounds(mesh)
        assertTrue(box.min.x.isFinite() && box.max.x.isFinite())
    }

    @Test
    fun `a radius that cannot be drawn yields nothing rather than a degenerate blob`() {
        assertTrue(NodeMarkers.markers(listOf(Vec3(0f, 0f, 0f)), radius = 0f).isEmpty)
        assertTrue(NodeMarkers.markers(listOf(Vec3(0f, 0f, 0f)), radius = Float.NaN).isEmpty)
    }

    @Test
    fun `the current point is marked larger than the run it ends`() {
        // Finding 270: six identical dots never said which end of the run was the current one, so
        // the size difference is asserted rather than left to the eye.
        assertTrue(NodeMarkers.CURRENT_RADIUS_METERS > NodeMarkers.RADIUS_METERS)

        val node = Vec3(2f, 1f, 0f)
        val run = bounds(NodeMarkers.markers(listOf(node)))
        val current = bounds(NodeMarkers.markers(listOf(node), NodeMarkers.CURRENT_RADIUS_METERS))
        assertTrue("the highlight is visibly bigger", current.size.x > run.size.x * 1.2f)
        assertEquals("and sits at the same point", node.x, current.center.x, 1e-4f)
    }
}
