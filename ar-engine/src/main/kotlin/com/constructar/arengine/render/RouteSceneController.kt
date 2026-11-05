/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.render

import android.util.Log
import com.constructar.arengine.route.RouteSession
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.constructar.core.mesh.MeshData
import com.constructar.core.mesh.RouteGeometry
import com.constructar.core.mesh.RouteGeometryOptions
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.RouteNodeRecord

/**
 * Bridges the pure routing state to the GPU.
 *
 * One instance owns the meshes for *every* run in the project plus the live preview:
 *
 *  * **Committed runs** are meshed once and re-uploaded at most every [REUPLOAD_INTERVAL_MS]:
 *    their node positions are the ones the run was measured with, so a re-mesh only happens when
 *    the app hands the controller a run it has not drawn yet.
 *  * **The live run** re-uploads on every geometry change (a node added, a corner smoothed),
 *    never per frame: at 30 fps a 4 000-triangle re-upload would burn the bandwidth budget.
 *
 * The controller is engine-agnostic: it takes a [MeshSink] so it can be unit-tested against a
 * recording fake, and so the same code could render to a video exporter later.
 */
interface MeshSink {
    /**
     * Uploads [mesh] as [key].
     *
     * @param unlit render with the unlit shading model (scan overlay, diagnostics) instead of
     *   the lit one (routes, which must pick up ARCore's light estimate).
     */
    fun upload(key: String, mesh: MeshData, argb: Int, unlit: Boolean = false): Boolean
    fun setPose(key: String, position: Vec3, rotation: Quat, scale: Float = 1f)
    fun setVisible(key: String, visible: Boolean)
    fun contains(key: String): Boolean
    fun remove(key: String)
}

/** Renders nothing; used when the native/GL stack is unavailable (e.g. unit tests). */
object NoOpMeshSink : MeshSink {
    override fun upload(key: String, mesh: MeshData, argb: Int, unlit: Boolean) = false
    override fun setPose(key: String, position: Vec3, rotation: Quat, scale: Float) = Unit
    override fun setVisible(key: String, visible: Boolean) = Unit
    override fun contains(key: String) = false
    override fun remove(key: String) = Unit
}

class RouteSceneController(
    private val sink: MeshSink,
    private val options: RouteGeometryOptions = RouteGeometryOptions(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lastUploadMillis = HashMap<String, Long>(8)
    private val uploadedSignatures = HashMap<String, Int>(8)

    /** The preview mesh currently uploaded, by identity - see [syncLiveRoute]. */
    private var liveMesh: MeshData? = null

    /** Uploads/refreshes every committed route mesh. */
    fun syncCommittedRoutes(routes: List<ConduitRoute>, visible: Boolean = true) {
        for (route in routes) {
            val key = keyFor(route.id)
            val signature = signatureOf(route.nodes, route.spec, route.closedLoop)
            val now = clock()
            val needsUpload = !sink.contains(key) &&
                (now - (lastUploadMillis[key] ?: 0L) > REUPLOAD_INTERVAL_MS)
            val changed = uploadedSignatures[key] != signature
            if (needsUpload || changed) {
                val geometry = runCatching { RouteGeometry.build(route, options) }
                    .onFailure { Log.w(TAG, "geometry failed for ${route.id}", it) }
                    .getOrNull() ?: continue
                if (sink.upload(key, geometry.mesh, route.spec.colorArgb)) {
                    uploadedSignatures[key] = signature
                }
                lastUploadMillis[key] = now
            }
            sink.setVisible(key, visible)
        }
    }

    /**
     * Uploads the in-progress run: the swept tube for the nodes already placed, plus the
     * preview segment to the reticle.
     *
     * Deduped on the **mesh**, not on the node chain. The preview segment ends at the reticle, so
     * its geometry changes without a node being added; the old dedupe compared the node signature,
     * so after the first upload the tube stopped where the reticle had been when the second node
     * was placed and only moved again when a third node was committed - the drawn run trailed the
     * operator by one node (finding 162). `RouteSession` builds a fresh [MeshData] exactly when
     * the preview geometry changes, so identity is the change detector, and re-sending the same
     * mesh object costs nothing.
     */
    fun syncLiveRoute(nodes: List<RouteNodeRecord>, spec: ConduitSpec, previewGeometry: MeshData?) {
        val key = LIVE_KEY
        if (nodes.size < 2 || previewGeometry == null) {
            sink.remove(key)
            // No signature bookkeeping for this key: the live preview is tracked by `liveMesh`,
            // and the map's entry for it would be a line nobody reads.
            liveMesh = null
            return
        }
        if (liveMesh !== previewGeometry) {
            if (sink.upload(key, previewGeometry, spec.colorArgb)) {
                liveMesh = previewGeometry
            }
        }
        sink.setVisible(key, true)
    }

    fun clearAll() {
        uploadedSignatures.keys.toList().forEach(sink::remove)
        uploadedSignatures.clear()
        lastUploadMillis.clear()
        // The live preview is not in `uploadedSignatures` any more (it is deduped on the mesh), so
        // tearing the scene down has to remove it by name or it outlives the session: the next
        // run's first upload would replace it, but a scene that is never measured in again keeps
        // a tube of the last one (finding 162).
        if (liveMesh != null) {
            sink.remove(LIVE_KEY)
            liveMesh = null
        }
    }

    private fun keyFor(routeId: String) = "route:$routeId"

    /**
     * Cheap change detector: node count, quantised positions (1 cm), the spec and loop state.
     * A full hash of the mesh would cost more than the upload it saves.
     */
    private fun signatureOf(nodes: List<RouteNodeRecord>, spec: ConduitSpec, closed: Boolean): Int {
        var hash = nodes.size
        hash = 31 * hash + spec.bomKey.hashCode()
        hash = 31 * hash + if (closed) 1 else 0
        for (node in nodes) {
            hash = 31 * hash + (node.position.x * 100f).toInt()
            hash = 31 * hash + (node.position.y * 100f).toInt()
            hash = 31 * hash + (node.position.z * 100f).toInt()
            hash = 31 * hash + node.normal.hashCode()
        }
        return hash
    }

    companion object {
        private const val TAG = "RouteSceneController"

        /** Committed runs are re-uploaded at most this often (anchor refinement is slow). */
        const val REUPLOAD_INTERVAL_MS = 1_000L

        const val LIVE_KEY = "route:live"

    }
}
