/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.geo

import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitConfidence
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max

/** Classification of an interior corner — drives the elbow count of the bill of materials. */
enum class BendKind {
    STRAIGHT,
    BEND_45,
    BEND_90,
    BEND_OTHER,
}

/** One measured straight stretch between two anchored nodes. */
data class SegmentLength(
    val index: Int,
    val fromMeters: Vec3,
    val toMeters: Vec3,
    val lengthMeters: Float,
    val surface: SurfaceKind,
    val hitSource: HitSource,
    val confidence: Float,
)

/** A corner in the raw node chain (before smoothing). */
data class BendAngle(
    val index: Int,
    val angleDegrees: Float,
    val kind: BendKind,
)

/**
 * Complete measurement of one routed run.
 *
 * [totalMeters] is the strict Euclidean sum the specification asks for
 * (`L = Σ |Pᵢ₊₁ − Pᵢ|`) over the *anchor-refined* node positions — the same value the
 * floating HUD shows. [bendAllowanceMeters] is reported separately (never folded in) so
 * the on-site figure stays verifiable with a tape measure; procurement uses
 * [withWasteMeters].
 */
data class RouteMetrics(
    val totalMeters: Float,
    val segments: List<SegmentLength>,
    val bends: List<BendAngle>,
    val bendAllowanceMeters: Float,
    val withWasteMeters: Float,
    val bendCount: Int,
    val elbow90Count: Int,
    val elbow45Count: Int,
    val otherBendCount: Int,
    val clipCount: Int,
    val couplingCount: Int,
    val minimumConfidence: Float,
) {
    val segmentCount: Int get() = segments.size

    /**
     * Nodes that must be reviewed before the customer pays for them.
     *
     * Two ways to be soft: the hit came from a source that is a guess by design (a feature point
     * or a manual placement), or the surface confidence the tracker reported is below
     * [HitConfidence.TRUSTED]. The second case used to be ignored
     * here while the AR screen warned about it, so a run measured on a badly tracked plane
     * reached the order list unflagged.
     */
    val lowConfidenceNodes: Int
        get() = segments.count {
            it.hitSource == HitSource.FEATURE_POINT ||
                it.hitSource == HitSource.MANUAL ||
                it.confidence < HitConfidence.RUN_TRUSTED
        }
}

/**
 * The measuring engine. Pure functions, no Android, no allocation of note — it is called
 * every frame while the operator is routing (the HUD must update at frame rate).
 */
object PolylineMetrics {

    /** Default procurement waste: cables are re-pulled, ducts are cut, both need spare. */
    fun defaultWasteFactor(spec: ConduitSpec): Float = when (spec.kind) {
        com.constructar.core.model.ConduitKind.CABLE -> 0.07f
        com.constructar.core.model.ConduitKind.PIPE -> 0.08f
        com.constructar.core.model.ConduitKind.DUCT -> 0.10f
    }

    /** Clip spacing in metres by trade practice (cables/pipes 60 cm, channels 50 cm). */
    fun defaultClipSpacing(spec: ConduitSpec): Float = when (spec.kind) {
        com.constructar.core.model.ConduitKind.CABLE -> 0.6f
        com.constructar.core.model.ConduitKind.PIPE -> 0.9f
        com.constructar.core.model.ConduitKind.DUCT -> 0.5f
    }

    /** Euclidean polyline length: `L = Σ |Pᵢ₊₁ − Pᵢ|`, metres. */
    fun length(points: List<Vec3>, closed: Boolean = false): Float {
        if (points.size < 2) return 0f
        var sum = 0f
        for (i in 0 until points.size - 1) sum += points[i].distanceTo(points[i + 1])
        if (closed && points.size > 2) sum += points.last().distanceTo(points.first())
        return sum
    }

    /** Length of a smoothed path (its own integrated arc length, not the chord sum). */
    fun length(path: SmoothedPath): Float = path.totalLengthMeters

    /** Detailed measurement with per-segment breakdown, bends, clips and couplings. */
    fun measure(
        nodes: List<RouteNodeRecord>,
        spec: ConduitSpec,
        closed: Boolean = false,
        wasteFactor: Float = defaultWasteFactor(spec),
        clipSpacingMeters: Float = defaultClipSpacing(spec),
    ): RouteMetrics {
        val segments = ArrayList<SegmentLength>(max(0, nodes.size))
        var total = 0f
        for (i in 0 until nodes.size - 1) {
            val a = nodes[i]
            val b = nodes[i + 1]
            val d = a.position.distanceTo(b.position)
            total += d
            segments += SegmentLength(
                index = i,
                fromMeters = a.position,
                toMeters = b.position,
                lengthMeters = d,
                surface = a.surface,
                hitSource = a.hitSource,
                confidence = minOf(a.confidence, b.confidence),
            )
        }
        if (closed && nodes.size > 2) {
            val a = nodes.last()
            val b = nodes.first()
            val d = a.position.distanceTo(b.position)
            total += d
            segments += SegmentLength(
                index = segments.size,
                fromMeters = a.position,
                toMeters = b.position,
                lengthMeters = d,
                surface = a.surface,
                hitSource = a.hitSource,
                confidence = minOf(a.confidence, b.confidence),
            )
        }

        val bends = bendAngles(nodes.map { it.position }, closed)
        val elbow90 = bends.count { it.kind == BendKind.BEND_90 }
        val elbow45 = bends.count { it.kind == BendKind.BEND_45 }
        val other = bends.count { it.kind == BendKind.BEND_OTHER }

        // Bend allowance (arc length a fitter has to add inside the bend): each bend of
        // angle δ replaces two straight trims t with an arc of radius r and sweep δ.
        val radius = defaultBendRadius(spec)
        val allowance = bends.sumOf { bend ->
            val delta = Math.toRadians(bend.angleDegrees.toDouble()).toFloat()
            if (delta <= 0f) 0.0 else {
                val trim = 2f * radius * kotlin.math.tan(delta / 2f)
                ((radius * delta) - trim).toDouble()
            }
        }.toFloat()

        // An open run is clipped at both ends, so it needs one more clip than it has gaps. A
        // closed ring does not: the clip at the start *is* the clip at the end, and the run of
        // 6 m at 0.6 m spacing needs exactly ten clips, not eleven. The extra clip used to be
        // counted anyway, which put one fitting per ring on the order list.
        val closedRing = closed && nodes.size > 2
        val clips = if (clipSpacingMeters <= 0f) {
            0
        } else {
            ceil(total / clipSpacingMeters).toInt() + if (closedRing) 0 else 1
        }
        // One coupling per straight length limit exceeded: 3 m for ducts, 50 m cable pulls.
        val couplingLimit = if (spec.kind == com.constructar.core.model.ConduitKind.DUCT) 3f else 50f
        val couplings = if (total <= couplingLimit) 0 else ceil(total / couplingLimit).toInt() - 1

        return RouteMetrics(
            totalMeters = total,
            segments = segments,
            bends = bends,
            bendAllowanceMeters = allowance,
            withWasteMeters = total * (1f + wasteFactor),
            bendCount = bends.size,
            elbow90Count = elbow90,
            elbow45Count = elbow45,
            otherBendCount = other,
            clipCount = clips,
            couplingCount = couplings,
            minimumConfidence = nodes.minOfOrNull { it.confidence } ?: 1f,
        )
    }

    /** Convenience: measure a persisted route. */
    fun measure(
        route: ConduitRoute,
        wasteFactor: Float = defaultWasteFactor(route.spec),
        clipSpacingMeters: Float = defaultClipSpacing(route.spec),
    ): RouteMetrics = measure(route.nodes, route.spec, route.closedLoop, wasteFactor, clipSpacingMeters)

    /**
     * Interior corner angles of the raw chain. A "90° elbow" is anything within ±10° of a
     * right angle, matching how fitters order fittings.
     */
    fun bendAngles(points: List<Vec3>, closed: Boolean): List<BendAngle> {
        if (points.size < 3) return emptyList()
        val out = ArrayList<BendAngle>(points.size)
        val indices = if (closed) points.indices else 1..points.size - 2
        for (i in indices) {
            val previous = points[(i - 1 + points.size) % points.size]
            val next = points[(i + 1) % points.size]
            val inDir = (points[i] - previous).normalizedOrZero()
            val outDir = (next - points[i]).normalizedOrZero()
            val deflection = inDir.angleTo(outDir)
            val degrees = Math.toDegrees(deflection.toDouble()).toFloat()
            val kind = when {
                degrees < 5f -> BendKind.STRAIGHT
                kotlin.math.abs(degrees - 90f) <= 10f -> BendKind.BEND_90
                kotlin.math.abs(degrees - 45f) <= 10f -> BendKind.BEND_45
                else -> BendKind.BEND_OTHER
            }
            out += BendAngle(i, degrees, kind)
        }
        return out
    }

    /**
     * Default bend radius by conduit family, metres: 8 × outer diameter for cables and
     * 5 × for pipes (DIN EN 61386 practice), 1.5 × duct height for channels.
     */
    fun defaultBendRadius(spec: ConduitSpec): Float = when (spec.kind) {
        com.constructar.core.model.ConduitKind.CABLE -> max(0.02f, spec.outerDiameterMeters * 8f)
        com.constructar.core.model.ConduitKind.PIPE -> max(0.03f, spec.outerDiameterMeters * 5f)
        com.constructar.core.model.ConduitKind.DUCT -> max(0.05f, spec.heightMeters * 1.5f)
    }

    /**
     * Warns when the smoothed path bends tighter than the conduit can take. A worker
     * dragging a 16 mm cable around a 2 cm corner would otherwise get a mesh that looks
     * fine and an installation that fails inspection.
     */
    fun isBendRadiusCompliant(path: SmoothedPath, spec: ConduitSpec): Boolean =
        path.minimumBendRadiusMeters + 1e-3f >= defaultBendRadius(spec)

    /** 2π helper for callers computing arc sweeps without importing kotlin.math. */
    val TAU: Float get() = 2f * PI.toFloat()
}
