/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.render

import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.constructar.core.mesh.MeshData
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live preview: the run the operator is measuring, which nothing drew (finding 162).
 *
 * `RouteSession` has always built the preview mesh and `syncLiveRoute` has always existed to
 * upload it, but no production code ever called the latter, and its dedupe compared the node
 * chain - the segment that ends at the reticle changes without a node being added, so the drawn
 * tube trailed the operator by one node. Both halves are pinned here, against a recording sink.
 */
class RouteSceneControllerTest {

    private class RecordingSink : MeshSink {
        val uploads = mutableListOf<Triple<String, MeshData, Int>>()
        val visibility = HashMap<String, Boolean>()
        val present = LinkedHashSet<String>()
        var failUploads = false

        override fun upload(key: String, mesh: MeshData, argb: Int, unlit: Boolean): Boolean {
            if (failUploads) return false
            uploads += Triple(key, mesh, argb)
            present += key
            return true
        }

        override fun setPose(key: String, position: Vec3, rotation: Quat, scale: Float) = Unit

        override fun setVisible(key: String, visible: Boolean) {
            visibility[key] = visible
        }

        override fun contains(key: String): Boolean = key in present

        override fun remove(key: String) {
            present -= key
            visibility.remove(key)
        }
    }

    private val spec = ConduitSpec.cable(16f, "Cable Ø16")

    private fun node(x: Float) = RouteNodeRecord(
        id = "n$x",
        position = Vec3(x, 0f, 0f),
        normal = Vec3(0f, 0f, 1f),
        surface = SurfaceKind.WALL,
        hitSource = HitSource.PLANE_POLYGON,
    )

    /** A mesh with `triangles` triangles - the content is irrelevant, the identity is not. */
    private fun mesh(triangles: Int) = MeshData(
        positions = FloatArray(9) { it.toFloat() },
        normals = FloatArray(9) { 0f },
        uvs = null,
        indices = IntArray(triangles * 3) { 0 },
    )

    private val nodes = listOf(node(0f), node(1f), node(2f))

    @Test
    fun `the run being measured is uploaded under the live key`() {
        val sink = RecordingSink()
        val preview = mesh(4)

        RouteSceneController(sink).syncLiveRoute(nodes, spec, preview)

        assertEquals("one upload for the in-progress run", 1, sink.uploads.size)
        assertEquals("under the key the controller reserves for it", "route:live", sink.uploads[0].first)
        assertTrue("with the spec's colour", sink.uploads[0].third == spec.colorArgb)
        assertEquals("and shown", true, sink.visibility["route:live"])
    }

    @Test
    fun `the preview segment follows the reticle, not the node chain`() {
        // The regression: the geometry changes because the reticle moved, with exactly the same
        // nodes. A dedupe on the node signature swallows this upload and the drawn run stops at
        // the position the reticle had when the last node was placed.
        val sink = RecordingSink()
        val controller = RouteSceneController(sink)

        controller.syncLiveRoute(nodes, spec, mesh(4))
        controller.syncLiveRoute(nodes, spec, mesh(6))

        assertEquals("the reticle moved, so the tube moved", 2, sink.uploads.size)
        assertEquals("the last upload is the newest geometry", 6, sink.uploads[1].second.triangleCount)
    }

    @Test
    fun `the same mesh is never uploaded twice`() {
        val sink = RecordingSink()
        val controller = RouteSceneController(sink)
        val preview = mesh(4)

        controller.syncLiveRoute(nodes, spec, preview)
        controller.syncLiveRoute(nodes, spec, preview)
        controller.syncLiveRoute(nodes, spec, preview)

        assertEquals("a still reticle costs nothing", 1, sink.uploads.size)
    }

    @Test
    fun `a run that cannot be drawn is removed, and comes back`() {
        val sink = RecordingSink()
        val controller = RouteSceneController(sink)

        controller.syncLiveRoute(nodes, spec, mesh(4))
        assertTrue("drawn to start with", sink.contains("route:live"))

        // One node is not a run, and no preview means no geometry: either way the mesh has to go,
        // or the finished run and the next one would share one tube.
        controller.syncLiveRoute(listOf(node(0f)), spec, mesh(4))
        assertFalse("a single node removes the preview", sink.contains("route:live"))

        controller.syncLiveRoute(nodes, spec, null)
        assertFalse("no geometry, no mesh", sink.contains("route:live"))

        controller.syncLiveRoute(nodes, spec, mesh(4))
        assertTrue("and it comes back when there is geometry again", sink.contains("route:live"))
    }

    @Test
    fun `clearAll drops the preview with the committed runs`() {
        val sink = RecordingSink()
        val controller = RouteSceneController(sink)
        val preview = mesh(4)
        controller.syncLiveRoute(nodes, spec, preview)

        controller.clearAll()

        assertFalse("a torn-down scene keeps no live mesh", sink.contains("route:live"))

        // And the state went with it: the same mesh must be uploaded again rather than treated as
        // "already on screen", because it is not.
        controller.syncLiveRoute(nodes, spec, preview)
        assertEquals("the dropped mesh is re-uploaded", 2, sink.uploads.size)
        assertTrue("and drawn again", sink.contains("route:live"))
    }
}
