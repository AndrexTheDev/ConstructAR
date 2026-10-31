/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.mesh

import com.constructar.core.geo.PathSmoother
import com.constructar.core.geo.PolylineMetrics
import com.constructar.core.geo.RouteMetrics
import com.constructar.core.geo.SmoothedPath
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.RouteNodeRecord

/**
 * The complete geometry of one routed run: the smoothed centreline, the per-station
 * surface normals it was swept on, the renderable/exportable mesh and the measurement.
 */
data class RouteGeometryResult(
    val routeId: String,
    val spec: ConduitSpec,
    val path: SmoothedPath,
    val surfaceNormals: List<Vec3>,
    val mesh: MeshData,
    val metrics: RouteMetrics,
    /** False when the smoothed bend is tighter than the conduit's minimum bending radius. */
    val bendRadiusCompliant: Boolean,
) {
    val lengthMeters: Float get() = metrics.totalMeters
}

/** Tuning shared by the renderer and the exporters. */
data class RouteGeometryOptions(
    /** Straight-span sampling; 1 m keeps vertex distribution even on long runs. */
    val maxStraightSpanMeters: Float = 1.0f,
    /** Spacing between corner-arc samples. 2 cm is smooth at arm's length. */
    val arcSampleMeters: Float = 0.02f,
    val tube: TubeMeshOptions = TubeMeshOptions(),
    val duct: DuctMeshOptions = DuctMeshOptions(),
    /** Overrides the conduit default (null = 8×Ø for cable, 5×Ø for pipe, 1.5×h for ducts). */
    val bendRadiusOverrideMeters: Float? = null,
)

/**
 * Turns a persisted [ConduitRoute] into geometry.
 *
 * The pipeline is intentionally a pure function of the route + the anchor-resolved node
 * positions, so the AR overlay, the HUD, the PDF and the GLB all agree byte-for-byte:
 *
 * ```
 * surface points ──offset along n──► centreline nodes ──fillet──► smoothed path
 *        │                                                            │
 *        └────────────── surface normals ──────────────────────────────┤
 *                                                                     ▼
 *                                            tube sweep / duct sweep ──► MeshData
 * ```
 */
object RouteGeometry {

    /**
     * Lifts the operator's surface taps onto the conduit's centre axis.
     *
     * A tap lands *on* the plaster; the cable's centre is one radius (+ standoff) away
     * from it. Doing this before smoothing is what makes a cable look like it is clipped to
     * the wall rather than half sunk into it.
     */
    fun centrelineNodes(nodes: List<RouteNodeRecord>, spec: ConduitSpec): List<Vec3> {
        val offset = spec.axisOffsetFromSurface
        return nodes.map { node ->
            val n = node.normal.normalizedOrZero()
            if (n.isDegenerate()) node.position else node.position + n * offset
        }
    }

    /**
     * Interpolates the node surface normals along the smoothed path.
     *
     * A path point between two nodes gets an inverse-distance blend of their normals: at a
     * room corner this turns the surface normal smoothly instead of snapping through 90°,
     * which is what keeps a duct sweep from folding inside out at the arris.
     */
    fun interpolateSurfaceNormals(path: SmoothedPath, nodes: List<RouteNodeRecord>): List<Vec3> {
        if (nodes.isEmpty()) return path.points.map { Vec3.Y_AXIS }
        if (nodes.size == 1) return path.points.map { nodes[0].normal.normalized() }

        val nodeNormals = nodes.map { it.normal.normalizedOrZero() }
        val nodePositions = nodes.map { it.position }
        return path.points.mapIndexed { index, point ->
            var bestDistance = Float.MAX_VALUE
            var secondDistance = Float.MAX_VALUE
            var best = 0
            var second = 0
            for (i in nodePositions.indices) {
                val d = point.distanceSquaredTo(nodePositions[i])
                if (d < bestDistance) {
                    secondDistance = bestDistance
                    second = best
                    bestDistance = d
                    best = i
                } else if (d < secondDistance) {
                    secondDistance = d
                    second = i
                }
            }
            val n0 = nodeNormals[best]
            val n1 = nodeNormals[second]
            val w0 = 1f / (bestDistance + 1e-4f)
            val w1 = 1f / (secondDistance + 1e-4f)
            val blended = n0 * w0 + n1 * w1
            val fallback = path.tangents.getOrElse(index) { Vec3.Z_AXIS }.anyOrthogonal()
            if (blended.isDegenerate(1e-4f)) fallback else blended.normalized()
        }
    }

    /** Builds geometry for [route] using the route's stored (anchor-resolved) node positions. */
    fun build(
        route: ConduitRoute,
        options: RouteGeometryOptions = RouteGeometryOptions(),
    ): RouteGeometryResult = build(
        routeId = route.id,
        nodes = route.nodes,
        spec = route.spec,
        closed = route.closedLoop,
        options = options,
    )

    fun build(
        routeId: String,
        nodes: List<RouteNodeRecord>,
        spec: ConduitSpec,
        closed: Boolean = false,
        options: RouteGeometryOptions = RouteGeometryOptions(),
    ): RouteGeometryResult {
        val centreline = centrelineNodes(nodes, spec)
        val bendRadius = options.bendRadiusOverrideMeters ?: PolylineMetrics.defaultBendRadius(spec)
        val path = PathSmoother.smooth(
            nodes = centreline,
            bendRadiusMeters = bendRadius,
            maxStraightSpanMeters = options.maxStraightSpanMeters,
            targetArcSampleMeters = options.arcSampleMeters,
            closed = closed,
        )
        val normals = interpolateSurfaceNormals(path, nodes)
        val mesh = when (spec.kind) {
            com.constructar.core.model.ConduitKind.CABLE,
            com.constructar.core.model.ConduitKind.PIPE,
            -> TubeMeshGenerator.generate(path, spec, options.tube)

            com.constructar.core.model.ConduitKind.DUCT,
            -> DuctMeshGenerator.generate(path, normals, spec, options.duct)
        }
        val metrics = PolylineMetrics.measure(nodes, spec, closed)
        return RouteGeometryResult(
            routeId = routeId,
            spec = spec,
            path = path,
            surfaceNormals = normals,
            mesh = mesh,
            metrics = metrics,
            bendRadiusCompliant = PolylineMetrics.isBendRadiusCompliant(path, spec),
        )
    }

    /**
     * Builds geometry for a live, still-being-drawn run. [livePoint] is the reticle target
     * the operator is about to commit; it is included as a provisional last node so the
     * tube visibly follows the crosshair.
     */
    fun buildPreview(
        nodes: List<RouteNodeRecord>,
        livePoint: RouteNodeRecord?,
        spec: ConduitSpec,
        closed: Boolean = false,
        options: RouteGeometryOptions = RouteGeometryOptions(),
    ): RouteGeometryResult? {
        if (nodes.isEmpty()) return null
        val all = if (livePoint != null) nodes + livePoint else nodes
        if (all.size < 2) return null
        return build("preview", all, spec, closed, options)
    }
}
