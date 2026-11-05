/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

import com.constructar.core.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where the reticle should *really* be: the architectural line the tap landed next to.
 *
 * A tap on a wall next to a corner lands a few centimetres inside the wall, because that is where
 * the reticle was. The measurement that follows is then short by those centimetres, and worse, it
 * is short by a *different* amount every time - two corners tapped twice give two different room
 * widths. The geometry to fix it is already in the session: ARCore's planes intersect in lines, and
 * two walls intersect in a vertical line, a wall and the floor in a horizontal one. Those lines are
 * the corners and edges a fitter measures to, and they are exact.
 *
 * So this snaps a hit onto the nearest **feature** - the intersection of two planes, clipped to
 * where both planes actually were - when the hit is within a tolerance of it.
 *
 * Four decisions, each one a place where the obvious version is wrong:
 *
 *  - **The intersection has to be clipped to both polygons.** Two walls that do not touch still
 *    intersect in an infinite line, and snapping to a corner that exists only in the algebra would
 *    put a measurement on a wall that is not there. The clip is a half-space sweep in each
 *    polygon's own plane, so the feature ends where the smaller of the two planes ends.
 *  - **A vertical line is a corner, a horizontal one is an edge - and which is which matters.** The
 *    label comes from the horizontal plane's normal sign, not from the line's direction, so a
 *    ceiling that was scanned from below and a floor that was scanned from above are not confused.
 *  - **Nothing is snapped from further than the tolerance.** 10 cm by default: beyond that the hit
 *    is closer to "somewhere on the wall" than to the corner, and pulling it would be inventing a
 *    measurement. The nearest distance is still reported, so a screen can say how far off it was.
 *  - **Untracked and subsumed planes contribute nothing.** ARCore merges planes; a corner derived
 *    from a plane that has been absorbed by another one is a corner in a room that no longer
 *    exists in the map.
 *
 * Nothing here formats a sentence, and nothing decides a length: it moves a point onto a line that
 * the session already believes in, and says which line.
 */

/** What kind of architectural feature a snap target is. */
enum class SnapFeatureKind {
    /** Two walls: a vertical corner. */
    CORNER,

    /** A wall and a floor. */
    FLOOR_EDGE,

    /** A wall and a ceiling. */
    CEILING_EDGE,

    /** Two horizontal planes - geometrically possible, architecturally meaningless. */
    EDGE,
}

/** A straight architectural feature: a corner or an edge, already clipped to both planes. */
data class SnapFeature(
    val kind: SnapFeatureKind,
    /** Lower endpoint under a lexicographic order, so the same room yields the same feature twice. */
    val start: Vec3,
    val end: Vec3,
    val firstPlaneId: String,
    val secondPlaneId: String,
) {
    val lengthM: Float get() = start.distanceTo(end)
}

/** How far a hit may be from a feature and still be pulled onto it. */
data class SnapOptions(
    val toleranceM: Float = 0.10f,
    /** A feature shorter than this is a sliver of overlap, not a corner worth snapping to. */
    val minFeatureLengthM: Float = 0.20f,
    /** Planes closer than this sine apart are treated as parallel and produce no line. */
    val minSineBetweenPlanes: Float = 0.05f,
)

/**
 * The result of asking where a hit should be.
 *
 * [point] is the snapped point when [feature] is set and the untouched hit otherwise;
 * [distanceM] is how far the hit was from the nearest feature, and null when there was no feature
 * to measure against at all.
 */
data class SnapResult(
    val point: Vec3,
    val feature: SnapFeature?,
    val distanceM: Float?,
) {
    val snapped: Boolean get() = feature != null
}

private const val SNAP_TIE_EPSILON = 1e-4f
private const val HORIZONTAL_COSINE = 0.6f
private const val PARAMETER_LIMIT = 1e6f

/** Derives the room's corners and edges from its planes, and snaps hits onto them. */
object ArchitecturalSnap {

    /** Every feature the given planes produce, in a deterministic order. */
    fun features(planes: List<PlaneCandidate>, options: SnapOptions = SnapOptions()): List<SnapFeature> {
        if (planes.size < 2) return emptyList()
        // A merged plane is a plane that no longer exists in the map, and an untracked one is not
        // trustworthy enough to measure to.
        val usable = planes.filter {
            it.isTracking && it.subsumedBy == null && it.polygon.size >= 3 && it.normal.isReal()
        }
        val result = ArrayList<SnapFeature>()
        for (i in usable.indices) {
            for (j in i + 1 until usable.size) {
                val first = usable[i]
                val second = usable[j]
                val raw = first.normal cross second.normal
                val sine = raw.length
                if (!(sine >= options.minSineBetweenPlanes)) continue
                val direction = raw / sine
                // Closest point on both planes: the standard solution of the 3x3 system
                // (n1 . x = d1, n2 . x = d2, x in the span of n1, n2).
                val base = ((second.normal cross raw) * first.offset +
                    (raw cross first.normal) * second.offset) / (sine * sine)
                val firstClip = clipToPolygon(base, direction, first) ?: continue
                val secondClip = clipToPolygon(base, direction, second) ?: continue
                val low = max(firstClip.low, secondClip.low)
                val high = min(firstClip.high, secondClip.high)
                if (!(high > low)) continue
                if (high - low < options.minFeatureLengthM) continue
                var start = base + direction * low
                var end = base + direction * high
                if (compareLexicographic(end, start) < 0) {
                    val swap = start
                    start = end
                    end = swap
                }
                result += SnapFeature(
                    kind = kindOf(first, second),
                    start = start,
                    end = end,
                    firstPlaneId = first.id,
                    secondPlaneId = second.id,
                )
            }
        }
        return result
    }

    /**
     * The nearest feature within the tolerance, or the hit unchanged.
     *
     * @return null when [hit] is not a finite point or the options are not usable - a snap computed
     *         from a NaN is worse than no snap at all
     */
    fun snapToFeatures(
        hit: Vec3,
        features: List<SnapFeature>,
        options: SnapOptions = SnapOptions(),
    ): SnapResult? {
        if (!hit.isReal()) return null
        if (!options.toleranceM.isFinite() || options.toleranceM < 0f) return null

        var bestFeature: SnapFeature? = null
        var bestPoint = hit
        var bestDistance = Float.POSITIVE_INFINITY
        var nearestDistance: Float? = null
        for (feature in features) {
            if (!feature.start.isReal() || !feature.end.isReal()) continue
            val closest = closestPointOnSegment(hit, feature.start, feature.end)
            val distance = closest.distanceTo(hit)
            if (nearestDistance == null || distance < nearestDistance) nearestDistance = distance
            val closer = distance < bestDistance - SNAP_TIE_EPSILON
            // A corner beats an edge at the same distance: the corner is the exact point the trade
            // measures to, and the two are never genuinely equidistant in a real room.
            val tieWins = abs(distance - bestDistance) <= SNAP_TIE_EPSILON &&
                rank(feature.kind) < rank(bestFeature?.kind)
            if (closer || tieWins) {
                bestFeature = feature
                bestPoint = closest
                bestDistance = distance
            }
        }

        if (bestFeature == null || bestDistance > options.toleranceM) {
            return SnapResult(point = hit, feature = null, distanceM = nearestDistance)
        }
        return SnapResult(point = bestPoint, feature = bestFeature, distanceM = bestDistance)
    }

    /** Convenience: derive the features from [planes] and snap onto them in one call. */
    fun snap(hit: Vec3, planes: List<PlaneCandidate>, options: SnapOptions = SnapOptions()): SnapResult? =
        snapToFeatures(hit, features(planes, options), options)

    /**
     * The interval of `base + t * direction` that lies inside [plane]'s polygon, or null when the
     * line misses it.
     *
     * Each edge contributes one half-space, oriented inwards by testing it against the polygon's
     * centroid - which keeps the winding of ARCore's polygons from mattering, because they are not
     * all wound the same way.
     */
    private fun clipToPolygon(base: Vec3, direction: Vec3, plane: PlaneCandidate): Interval? {
        val polygon = plane.polygon
        if (polygon.size < 3) return null
        var sum = Vec3(0f, 0f, 0f)
        for (vertex in polygon) sum += vertex
        val centroid = sum / polygon.size.toFloat()

        var low = -PARAMETER_LIMIT
        var high = PARAMETER_LIMIT
        for (index in polygon.indices) {
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            var inward = (b - a) cross plane.normal
            if (inward.isDegenerate(1e-9f)) return null
            if ((inward dot (centroid - a)) < 0f) inward = -inward
            val offset = inward dot (base - a)
            val rate = inward dot direction
            if (abs(rate) < 1e-9f) {
                if (offset < 0f) return null
            } else {
                val crossing = -offset / rate
                if (rate > 0f) {
                    if (crossing > low) low = crossing
                } else {
                    if (crossing < high) high = crossing
                }
                if (low > high) return null
            }
        }
        return Interval(low, high)
    }

    private fun kindOf(first: PlaneCandidate, second: PlaneCandidate): SnapFeatureKind {
        val firstHorizontal = abs(first.normal.y) > HORIZONTAL_COSINE
        val secondHorizontal = abs(second.normal.y) > HORIZONTAL_COSINE
        if (!firstHorizontal && !secondHorizontal) return SnapFeatureKind.CORNER
        if (firstHorizontal == secondHorizontal) return SnapFeatureKind.EDGE
        // The horizontal plane's normal points out of the material: up from a floor, down from a
        // ceiling. The label follows that, not the line's own direction.
        val horizontal = if (firstHorizontal) first else second
        return if (horizontal.normal.y > 0f) SnapFeatureKind.FLOOR_EDGE else SnapFeatureKind.CEILING_EDGE
    }

    private fun rank(kind: SnapFeatureKind?): Int = when (kind) {
        SnapFeatureKind.CORNER -> 0
        SnapFeatureKind.FLOOR_EDGE, SnapFeatureKind.CEILING_EDGE -> 1
        SnapFeatureKind.EDGE -> 2
        null -> 3
    }

    private data class Interval(val low: Float, val high: Float)

    private fun closestPointOnSegment(point: Vec3, start: Vec3, end: Vec3): Vec3 {
        val span = end - start
        val lengthSquared = span.lengthSquared
        if (lengthSquared < 1e-9f) return start
        val t = (((point - start) dot span) / lengthSquared).coerceIn(0f, 1f)
        return start + span * t
    }

    private fun compareLexicographic(a: Vec3, b: Vec3): Int = when {
        a.x != b.x -> a.x.compareTo(b.x)
        a.y != b.y -> a.y.compareTo(b.y)
        else -> a.z.compareTo(b.z)
    }

    private fun Vec3.isReal(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}
