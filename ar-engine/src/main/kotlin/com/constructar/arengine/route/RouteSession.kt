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

    /**
     * The new point sits on a surface parallel to the previous one but clearly behind or in
     * front of it: the classic "tapped through the doorway onto the far wall" slip, which is
     * off by a wall thickness rather than by a millimetre. Accepted, but flagged, because only
     * the operator knows whether the jump was intended.
     */
    SURFACE_JUMP,

    /**
     * The run was closed into a ring, but its first and last node are not on top of each other:
     * the closing segment would be invented rather than measured. Harmless for the length (the
     * loop is still a polyline), wrong for the drawing and for anyone reading the metres later.
     */
    LOOP_TOO_WIDE,
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
        /** Distance from the last committed node to the live reticle, metres (0 when unknown). */
        val liveSegmentMeters: Float = 0f,
        /** Confidence of the last committed node, 0..1. */
        val lastNodeConfidence: Float = 0f,
        /** Weakest confidence in the run — the honest quality of this measurement. */
        val worstNodeConfidence: Float = 0f,
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
        val node = hit?.toRouteNode(id = LIVE_NODE_ID)
        livePoint = node
        if (previous?.position != node?.position && node != null) previewDirty = true

        // Published on every reticle update (12 Hz) because it costs one distance, not a
        // geometry rebuild: the operator sees the segment under the crosshair growing while he
        // walks, which is what tells him "stop, that is the 3 m I need".
        val last = mutableState.value.nodes.lastOrNull()
        val distance = if (last != null && node != null) last.position.distanceTo(node.position) else 0f
        val current = mutableState.value
        if (current.liveSegmentMeters != distance) {
            mutableState.value = current.copy(liveSegmentMeters = distance)
        }
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
        val surfaceJump = previous != null && isSurfaceJump(previous, hit)
        val anchor = anchorFactory.create(hit.point, hit.normal)
        if (anchor == null) {
            mutableState.value = current.copy(warning = RouteWarning.UNPINNED)
            return false
        }
        anchors[anchor.id] = anchor
        val node = hit.toRouteNode(id = "node-${UUID.randomUUID()}", anchorId = anchor.id)
        previewDirty = true
        // The segment under the reticle is now measured from *this* node, not the previous one.
        val liveDistance = livePoint?.position?.let { node.position.distanceTo(it) } ?: 0f
        mutableState.value = recompute(
            current.copy(
                nodes = current.nodes + node,
                lastHit = null,
                liveSegmentMeters = liveDistance,
                warning = if (surfaceJump) RouteWarning.SURFACE_JUMP else RouteWarning.NONE,
            ),
        )
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

    /**
     * Closes the run into a loop (rings, closed cable trays) or opens it again.
     *
     * Closing is checked: if the last node is not within [LOOP_TOLERANCE_METERS] of the first,
     * the operator is told, because the ring would be closed by a segment nobody measured.
     */
    @Synchronized
    fun closeLoop(): Boolean {
        val current = mutableState.value
        if (current.nodes.size < 3) return false
        previewDirty = true
        val closing = !current.closedLoop
        val gap = if (closing) loopGap(current.nodes) else 0f
        val warning = if (gap > LOOP_TOLERANCE_METERS) RouteWarning.LOOP_TOO_WIDE else RouteWarning.NONE
        mutableState.value = recompute(current.copy(closedLoop = closing, warning = warning))
        return true
    }

    /** Distance between the first and the last node, or `0` for a run with fewer than two. */
    fun loopGap(nodes: List<RouteNodeRecord> = mutableState.value.nodes): Float {
        val first = nodes.firstOrNull() ?: return 0f
        val last = nodes.lastOrNull() ?: return 0f
        return first.position.distanceTo(last.position)
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
            liveSegmentMeters = if (livePoint == null) 0f else state.liveSegmentMeters,
            lastNodeConfidence = state.nodes.lastOrNull()?.confidence ?: 0f,
            worstNodeConfidence = state.nodes.minOfOrNull { it.confidence } ?: 0f,
            metrics = metrics,
            preview = preview,
            warning = warning,
            nodeCount = state.nodes.size,
            canFinish = state.nodes.size >= 2,
            anchorCount = anchors.size,
        )
    }

    /**
     * True when [next] lands on a surface parallel to [previous] but offset from it.
     *
     * A real corner (normal ~90°) is not a jump - that is how a run turns. What this catches is
     * the parallel-but-displaced case: the ray slipped through a reveal, past a pipe or onto the
     * far side of a partition, and the node is off by the thickness of a wall.
     */
    private fun isSurfaceJump(previous: RouteNodeRecord, next: SurfaceHit): Boolean {
        val n = next.normal.normalizedOrZero()
        if (n.isDegenerate()) return false
        val cosine = (previous.normal dot n).coerceIn(-1f, 1f)
        if (cosine < PARALLEL_COSINE) return false
        val offset = kotlin.math.abs((previous.position - next.point) dot n)
        return offset > SURFACE_JUMP_METERS
    }

    companion object {
        /** Two taps closer than this are the same tap (fat fingers + gloves). */
        const val MIN_NODE_SPACING_METERS = 0.02f

        /** `dot >= 0.966` is within 15° — the surfaces are parallel for routing purposes. */
        private const val PARALLEL_COSINE = 0.966f

        /** A parallel surface further than this from the last node is a different wall. */
        const val SURFACE_JUMP_METERS = 0.20f

        /** A ring whose ends are further apart than this was closed by an unmeasured segment. */
        const val LOOP_TOLERANCE_METERS = 0.15f

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
