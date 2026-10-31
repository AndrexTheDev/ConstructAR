/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.route

import com.constructar.arengine.hit.HitResolution
import com.constructar.arengine.hit.SurfaceHit
import com.constructar.core.geo.RouteMetrics
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.constructar.core.mesh.RouteGeometry
import com.constructar.core.mesh.RouteGeometryOptions
import com.constructar.core.mesh.RouteGeometryResult
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.RouteNodeRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * An anchor as the route needs it — deliberately *not* an ARCore `Anchor`, so the routing
 * state machine can be exercised in unit tests and so anchors can be re-created after
 * relocalisation without touching the route.
 */
interface AnchorHandle {
    val id: String

    /** Current world position of the anchor's origin (refined by ARCore every frame). */
    val position: Vec3

    /** Current world orientation. */
    val rotation: Quat

    val tracking: Boolean

    fun detach()
}

/** Creates/destroys anchors. `ArCoreAnchorFactory` is the production implementation. */
interface AnchorFactory {
    fun create(position: Vec3, normal: Vec3): AnchorHandle?
}

/**
 * Non-fatal advice for the operator, as a *code*.
 *
 * Like [com.constructar.arengine.tracking.Guidance], the routing engine never produces
 * user-facing sentences: the app maps these to string resources, so every warning exists in
 * all six shipped languages.
 */
enum class RouteWarning {
    NONE,

    /** The new point is closer than the minimum node spacing. */
    TOO_CLOSE,

    /** The surface hit was rejected because tracking is weak. */
    UNPINNED,

    /** The swept conduit would bend tighter than the profile allows. */
    BEND_RADIUS_TOO_TIGHT,
    ;

    val isPresent: Boolean get() = this != NONE
}

/**
 * The live routing session for one conduit run.
 *
 * Responsibilities, in order of importance:
 *  1. **Bind every node to an anchor** (requirement D) so the run stays glued to the wall
 *     while ARCore refines its pose, and re-read those anchors every frame.
 *  2. **Measure** with the strict Euclidean polyline (`L = Σ|Pᵢ₊₁ − Pᵢ|`), always on the
 *     *surface* points — never on the offset centreline, which would inflate every run by
 *     the conduit radius.
 *  3. **Preview** the conduit geometrically while the operator is still drawing: the tube
 *     follows the reticle, so the run is verified visually before it is committed.
 *
 * All state is exposed as an immutable [State] through a [StateFlow]; the AR overlay collects
 * it and never mutates the session directly.
 */
class RouteSession(
    private val anchorFactory: AnchorFactory,
    private val options: RouteGeometryOptions = RouteGeometryOptions(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class State(
        val spec: ConduitSpec,
        val nodes: List<RouteNodeRecord> = emptyList(),
        val closedLoop: Boolean = false,
        /** Live Euclidean length of the committed chain, metres. Formatted by the UI. */
        val lengthMeters: Float = 0f,
        val metrics: RouteMetrics? = null,
        /** Smoothed preview geometry (null until there are ≥ 2 points). */
        val preview: RouteGeometryResult? = null,
        /** Last hit resolution, including the per-tier diagnostic trail. */
        val lastHit: HitResolution? = null,
        /** Non-fatal advice for the operator ("bend radius too tight"). */
        val warning: RouteWarning = RouteWarning.NONE,
        val nodeCount: Int = 0,
        val canFinish: Boolean = false,
        val anchorCount: Int = 0,
    )

    private val anchors = LinkedHashMap<String, AnchorHandle>()
    private var mutableState = MutableStateFlow(State(spec = ConduitSpec.cable(16f)))
    val state: StateFlow<State> = mutableState.asStateFlow()

    /**
     * Every mutator is `@Synchronized`: [onFrame] runs on ARCore's render thread while taps,
     * undo and spec changes arrive on the main thread. A lost update here would silently drop
     * a measured node, which is the one bug class this app cannot afford.
     */
    // Reticle target the operator is about to commit (preview only, never persisted).
    private var livePoint: RouteNodeRecord? = null
    private var previewDirty = true

    // ── Editing ─────────────────────────────────────────────────────────────────────────

    /** Switches the conduit being routed. Existing nodes are kept (re-swept with the new spec). */
    @Synchronized
    fun setSpec(spec: ConduitSpec) {
        mutableState.value = recompute(mutableState.value.copy(spec = spec))
    }

    @Synchronized
    fun setLivePoint(hit: SurfaceHit?) {
        val previous = livePoint
        livePoint = hit?.toRouteNode(id = LIVE_NODE_ID)
        if (previous?.position != livePoint?.position && livePoint != null) previewDirty = true
    }

    /**
     * Commits a tapped surface point as a node.
     *
     * Returns false — and leaves the run untouched — when the tap was too close to the
     * previous node (double tap) or when the anchor could not be created (tracking lost).
     */
    @Synchronized
    fun addNode(hit: SurfaceHit): Boolean {
        val current = mutableState.value
        val previous = current.nodes.lastOrNull()
        if (previous != null && previous.position.distanceTo(hit.point) < MIN_NODE_SPACING_METERS) {
            mutableState.value = current.copy(warning = RouteWarning.TOO_CLOSE)
            return false
        }
        val anchor = anchorFactory.create(hit.point, hit.normal)
        if (anchor == null) {
            mutableState.value = current.copy(warning = RouteWarning.UNPINNED)
            return false
        }
        anchors[anchor.id] = anchor
        val node = hit.toRouteNode(id = "node-${UUID.randomUUID()}", anchorId = anchor.id)
        previewDirty = true
        mutableState.value = recompute(current.copy(nodes = current.nodes + node, lastHit = null, warning = RouteWarning.NONE))
        return true
    }

    @Synchronized
    fun undo(): RouteNodeRecord? {
        val current = mutableState.value
        val removed = current.nodes.lastOrNull() ?: return null
        removed.anchorId?.let { anchors.remove(it)?.detach() }
        previewDirty = true
        mutableState.value = recompute(current.copy(nodes = current.nodes.dropLast(1), warning = RouteWarning.NONE))
        return removed
    }

    /**
     * Resets the live run **without** detaching its anchors.
     *
     * Called right after [finish]: the committed run must keep following ARCore's refinement,
     * so its anchors stay alive until [release]. Detaching them here would freeze the mesh in
     * world space and let it drift away from the wall.
     */
    @Synchronized
    fun startNewRun() {
        livePoint = null
        previewDirty = true
        mutableState.value = recompute(mutableState.value.copy(nodes = emptyList(), closedLoop = false, warning = RouteWarning.NONE))
    }

    @Synchronized
    fun clear() {
        anchors.values.forEach { it.detach() }
        anchors.clear()
        livePoint = null
        previewDirty = true
        mutableState.value = recompute(mutableState.value.copy(nodes = emptyList(), closedLoop = false))
    }

    /** Closes the run into a loop (rings, closed cable trays). */
    @Synchronized
    fun closeLoop(): Boolean {
        val current = mutableState.value
        if (current.nodes.size < 3) return false
        previewDirty = true
        mutableState.value = recompute(current.copy(closedLoop = !current.closedLoop))
        return true
    }

    /**
     * Refreshes node positions from their anchors and recomputes the measurement.
     *
     * Called once per ARCore frame. Deliberately cheap when nothing moved: positions are
     * compared with a 1 mm threshold, and geometry is only rebuilt when the chain changed or
     * a node moved by more than 5 mm (ARCore's anchor jitter stays well below that after the
     * first second).
     */
    @Synchronized
    fun onFrame() {
        val current = mutableState.value
        if (current.nodes.isEmpty()) return
        var changed = false
        val refreshed = current.nodes.map { node ->
            val anchor = node.anchorId?.let(anchors::get) ?: return@map node
            if (!anchor.tracking) return@map node
            val position = anchor.position
            if (position.distanceTo(node.position) > MOVEMENT_THRESHOLD_METERS) {
                changed = true
                node.withPosition(position)
            } else {
                node
            }
        }
        if (!changed) return
        previewDirty = true
        mutableState.value = recompute(current.copy(nodes = refreshed))
    }

    /** Finalises the run; returns null when fewer than two nodes have been placed. */
    @Synchronized
    fun finish(label: String = "", notes: String = ""): ConduitRoute? {
        val current = mutableState.value
        if (current.nodes.size < 2) return null
        val route = ConduitRoute(
            id = "route-${UUID.randomUUID()}",
            label = label.ifBlank { current.spec.label },
            spec = current.spec,
            nodes = current.nodes,
            createdAtEpochMs = clock(),
            closedLoop = current.closedLoop,
            notes = notes,
        )
        // The committed route keeps its anchors alive: the finished mesh must stay glued to
        // the wall while the operator routes the next run. `release()` is called on teardown.
        livePoint = null
        return route
    }

    /** Detaches every anchor (session teardown / project closed). */
    @Synchronized
    fun release() {
        anchors.values.forEach { it.detach() }
        anchors.clear()
    }

    fun anchorFor(nodeId: String): AnchorHandle? {
        val node = mutableState.value.nodes.firstOrNull { it.id == nodeId } ?: return null
        return node.anchorId?.let(anchors::get)
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────

    private fun recompute(state: State): State {
        val metrics = if (state.nodes.size >= 2) {
            com.constructar.core.geo.PolylineMetrics.measure(state.nodes, state.spec, state.closedLoop)
        } else null

        val lengthMeters = metrics?.totalMeters ?: 0f
        val preview = if (previewDirty || state.preview == null) {
            previewDirty = false
            RouteGeometry.buildPreview(state.nodes, livePoint, state.spec, state.closedLoop, options)
        } else {
            state.preview
        }

        val warning = when {
            state.warning.isPresent -> state.warning
            preview != null && !preview.bendRadiusCompliant -> RouteWarning.BEND_RADIUS_TOO_TIGHT
            else -> RouteWarning.NONE
        }

        return state.copy(
            nodes = state.nodes,
            lengthMeters = lengthMeters,
            metrics = metrics,
            preview = preview,
            warning = warning,
            nodeCount = state.nodes.size,
            canFinish = state.nodes.size >= 2,
            anchorCount = anchors.size,
        )
    }

    private fun SurfaceHit.toRouteNode(id: String, anchorId: String? = null) = RouteNodeRecord(
        id = id,
        position = point,
        normal = normal,
        surface = surfaceKind,
        hitSource = source,
        anchorId = anchorId,
        confidence = confidence,
    )

    companion object {
        /** Two taps closer than this are the same tap (fat fingers + gloves). */
        const val MIN_NODE_SPACING_METERS = 0.02f

        /** Anchor refinement below this is ignored so the mesh does not shimmer. */
        const val MOVEMENT_THRESHOLD_METERS = 0.005f

        private const val LIVE_NODE_ID = "live-reticle"

        /** Rotation that aligns +Y with a surface normal (ARCore's "up" convention). */
        fun quaternionForNormal(normal: Vec3): Quat {
            val n = normal.normalizedOrZero()
            if (n.isDegenerate()) return Quat.IDENTITY
            val from = Vec3.Y_AXIS
            val dot = (from dot n).coerceIn(-1f, 1f)
            if (dot > 0.9999f) return Quat.IDENTITY
            if (dot < -0.9999f) return Quat.fromAxisAngle(Vec3.X_AXIS, kotlin.math.PI.toFloat())
            val axis = (from cross n).normalized()
            return Quat.fromAxisAngle(axis, kotlin.math.acos(dot))
        }
    }
}

/** Convenience for UI: a plain-language confidence summary of the last resolution. */
fun HitResolution.describe(): String = attempts.joinToString(" · ") { attempt ->
    val mark = when {
        !attempt.considered -> "skip"
        attempt.accepted -> "hit"
        else -> "miss"
    }
    "${attempt.source.name}/$mark"
}

/** Creator used when the ARCore session is not available (e.g. a recorded replay). */
object NoOpAnchorFactory : AnchorFactory {
    override fun create(position: Vec3, normal: Vec3): AnchorHandle? = null
}
