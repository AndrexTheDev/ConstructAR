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
import kotlin.math.min

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
 * floating HUD shows and the figure a tape measure can check.
 *
 * [bendDeductionMeters] is the amount by which the *bent* conduit is shorter than that figure:
 * a bend of sweep δ and centreline radius r replaces two straight trims of `r·tan(δ/2)` with an
 * arc of `r·δ`, so the developed length loses `2·r·tan(δ/2) − r·δ` at every corner. The trade
 * calls this the bend deduction (or the "gain" of a bend); it is always positive, and it used to
 * be here as a negative `bendAllowanceMeters` described as "the arc length a fitter has to add",
 * which is the opposite sign of what a bend does and was printed nowhere (finding 114).
 *
 * It is reported, never folded in: the on-site metre stays the tape-measure figure and
 * procurement uses [withWasteMeters]. Only sweeps a bender can make (up to 90°, clamped, and
 * never more than half the run) count - a sharper corner is formed with a fitting, and a
 * measurement that doubles back on itself is not a bend at all.
 */
data class RouteMetrics(
    val totalMeters: Float,
    val segments: List<SegmentLength>,
    val bends: List<BendAngle>,
    val bendDeductionMeters: Float,
    val withWasteMeters: Float,
    /**
     * Corners the chain actually turns at.
     *
     * Not `bends.size`: [bendAngles] classifies every interior node, and one that is within 5° of
     * straight is a `STRAIGHT` entry because the smoothing path and the diagnostics want the whole
     * row of corners. Counting those as bends put "Bends: 4" on a straight pull of six taps along
     * one wall, on the document the customer signs, while the same report's procurement table
     * ordered not a single elbow (finding 116).
     */
    val bendCount: Int,
    val elbow90Count: Int,
    val elbow45Count: Int,
    val otherBendCount: Int,
    val clipCount: Int,
    val couplingCount: Int,
    val minimumConfidence: Float,
    /**
     * Nodes that must be reviewed before the customer pays for them.
     *
     * Two ways to be soft: the hit came from a source that is a guess by design (a feature point
     * or a manual placement), or the surface confidence the tracker reported is below
     * [HitConfidence.RUN_TRUSTED] - the same bar the HUD questions a run at.
     *
     * Counted over the **nodes**, once each. Counting segments was wrong twice over: it used the
     * lower bar of 0.5, so a raycast point (0.7) reached the order list unflagged, and a weak point
     * between two segments was counted as two, because a segment carries its start node's source
     * and the smaller of its two confidences (finding 47).
     */
    val lowConfidenceNodes: Int,
) {
    val segmentCount: Int get() = segments.size
}

/**
 * The measuring engine. Pure functions, no Android, no allocation of note — it is called
 * every frame while the operator is routing (the HUD must update at frame rate).
 */
object PolylineMetrics {

    /**
     * Default procurement waste: cables are re-pulled, ducts are cut, both need spare.
     *
     * Answered per **family**, because that is all it depends on - and the material screen has to
     * be able to say what "the trade default" means before the operator picks it, without inventing
     * a spec to ask about (finding 156). The spec overload is the same function, so the number the
     * screen shows and the number the engine applies cannot drift apart.
     */
    fun defaultWasteFactor(kind: com.constructar.core.model.ConduitKind): Float = when (kind) {
        com.constructar.core.model.ConduitKind.CABLE -> 0.07f
        com.constructar.core.model.ConduitKind.PIPE -> 0.08f
        com.constructar.core.model.ConduitKind.DUCT -> 0.10f
    }

    fun defaultWasteFactor(spec: ConduitSpec): Float = defaultWasteFactor(spec.kind)

    /**
     * Clip spacing in metres by trade practice: cables 60 cm, pipes 90 cm, channels 50 cm.
     *
     * Answered per **family**, like [defaultWasteFactor] and for the same reason (finding 156):
     * the material screen has to say what "the trade default" stands for *before* the operator
     * picks it, and it has no spec to ask about. The spec overload routes through this one, so the
     * number the screen states and the number the engine counts with cannot drift apart.
     *
     * This KDoc said "cables/pipes 60 cm, channels 50 cm" while the code has counted pipe clips at
     * 90 cm since finding 138 - a sentence a maintainer reads *before* changing the number, and one
     * that every other document contradicts: findings 113, 151 and the material screen all quote
     * 90 cm for pipe (finding 180).
     */
    fun defaultClipSpacing(kind: com.constructar.core.model.ConduitKind): Float = when (kind) {
        com.constructar.core.model.ConduitKind.CABLE -> 0.6f
        com.constructar.core.model.ConduitKind.PIPE -> 0.9f
        com.constructar.core.model.ConduitKind.DUCT -> 0.5f
    }

    /** The spacing the engine counts a spec's clips with — see the per-family overload. */
    fun defaultClipSpacing(spec: ConduitSpec): Float = defaultClipSpacing(spec.kind)

    /**
     * The length one piece of this family is bought in, or `null` for a reel that has no joint.
     *
     * Cable comes on a drum, so a pull needs no coupling however long it is. Pipes, empty conduit
     * and duct channels are sold in 3 m lengths, so every 3 m of run needs a socket. The order list
     * used to answer this with one hard-coded limit for everything: 3 m for ducts and **50 m** for
     * pipes and cable, so a 40 m pipe pull ordered no socket at all (the fitter cannot join two
     * pieces he was never sent) and a 60 m cable ordered a coupling that no one makes for a drum
     * (finding 138).
     */
    fun defaultStockLength(spec: ConduitSpec): Float? = when (spec.kind) {
        com.constructar.core.model.ConduitKind.CABLE -> null
        com.constructar.core.model.ConduitKind.PIPE -> 3f
        com.constructar.core.model.ConduitKind.DUCT -> 3f
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
        stockLengthMeters: Float? = defaultStockLength(spec),
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

        // Bend deduction: each bend of sweep δ replaces two straight trims of r·tan(δ/2) with an
        // arc of radius r, so the bent conduit is shorter than the polyline by
        // 2·r·tan(δ/2) − r·δ. Clamped to a right angle (a sharper corner is made with a fitting,
        // not by bending) and to half the run (a deduction can never eat the piece).
        val radius = defaultBendRadius(spec)
        val deduction = min(
            bends.sumOf { bend ->
                // A corner inside the STRAIGHT tolerance is not bent at all.
                if (bend.kind == BendKind.STRAIGHT) return@sumOf 0.0
                val delta = Math.toRadians(bend.angleDegrees.toDouble()).toFloat()
                val sweep = min(delta, Math.PI.toFloat() / 2f)
                if (sweep <= 0f) 0.0 else {
                    val trim = 2f * radius * kotlin.math.tan(sweep / 2f)
                    (trim - (radius * sweep)).coerceAtLeast(0f).toDouble()
                }
            }.toFloat(),
            total * 0.5f,
        )

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
        // One coupling per joint the run needs, and the joint count follows the stock length: a
        // 12 m pipe is four 3 m pieces and three sockets, a 70 m cable on a drum is still one
        // piece. See [defaultStockLength] for the 50 m limit this replaces (finding 138).
        val couplings = if (stockLengthMeters == null || stockLengthMeters <= 0f || total <= stockLengthMeters) {
            0
        } else {
            ceil(total / stockLengthMeters).toInt() - 1
        }

        return RouteMetrics(
            totalMeters = total,
            segments = segments,
            bends = bends,
            bendDeductionMeters = deduction,
            withWasteMeters = total * (1f + wasteFactor),
            bendCount = bends.count { it.kind != BendKind.STRAIGHT },
            elbow90Count = elbow90,
            elbow45Count = elbow45,
            otherBendCount = other,
            clipCount = clips,
            couplingCount = couplings,
            minimumConfidence = nodes.minOfOrNull { it.confidence } ?: 1f,
            lowConfidenceNodes = nodes.count { node ->
                node.hitSource == HitSource.FEATURE_POINT ||
                    node.hitSource == HitSource.MANUAL ||
                    node.confidence < HitConfidence.RUN_TRUSTED
            },
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
