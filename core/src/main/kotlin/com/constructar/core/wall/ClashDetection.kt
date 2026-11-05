/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import com.constructar.core.math.Aabb
import com.constructar.core.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Clash detection: does the run the operator measured actually fit where the drawings say it does?
 *
 * A cable route is measured against surfaces - it hugs a wall, crosses a ceiling, drops behind a
 * duct - and every one of those is a place where the run can end up inside something. The app's
 * whole promise is that the number was taken on site rather than guessed off a drawing, so the
 * check has to happen on the same geometry: the polyline as measured, against boxes that stand for
 * walls, beams and services.
 *
 * Three decisions are worth stating, because each one is a place where a plausible implementation
 * is quietly wrong:
 *
 *  - **A segment that passes *through* a box touches none of its edges.** The obvious shortcut -
 *    "measure the segment against the box's twelve edges" - returns a positive distance for a run
 *    that goes straight through the middle of a 2 m beam, because the closest approach is to the
 *    interior, not to an edge. The intersection test is therefore a proper slab clip, and only the
 *    non-intersecting case is measured against edges and corners.
 *  - **Distance is exact, not sampled.** Stepping along the route and testing points misses a
 *    crossing between two samples, and the step size that would make it safe is smaller than the
 *    clearances being asked about. Segment-to-box is solved in closed form: zero when it
 *    intersects, otherwise the minimum of the two endpoint distances and the twelve edge-to-segment
 *    distances.
 *  - **A rejected input is reported, not measured.** A non-finite point, a route of one point, or a
 *    box that is inside out returns `valid = false` with no hits - a clearance of "0.0 m, no clash"
 *    computed from nonsense is worse than no answer.
 *
 * Nothing here formats a sentence: the report carries ids, distances and fractions, and `:app` owns
 * the words (finding 100's rule).
 */

/** A box a run has to keep clear of: a wall, a beam, a duct, a radiator. */
data class Obstacle(
    val id: String,
    val bounds: Aabb,
    /** Extra clearance required around this obstacle, on top of the check's default. */
    val clearanceM: Float = 0f,
)

/** How a run failed the check. */
enum class ClashKind {
    /** The run goes through the obstacle. */
    THROUGH_OBSTACLE,

    /** The run passes closer than the clearance it is held to. */
    BELOW_CLEARANCE,
}

/** Where a run and an obstacle disagree, and by how much. */
data class ClashHit(
    val obstacleId: String,
    /** Index of the route segment that clashes (segment `i` runs from point `i` to point `i+1`). */
    val segmentIndex: Int,
    val kind: ClashKind,
    /** Closest approach in metres; exactly 0 for a run that goes through. */
    val distanceM: Float,
    /** Point on the route where the closest approach happens. */
    val atPoint: Vec3,
    /** Where along the segment that point is, 0 at its start and 1 at its end. */
    val segmentFraction: Float,
)

/** The result of checking one route against a set of obstacles. */
data class ClearanceReport(
    val hits: List<ClashHit>,
    val checkedSegments: Int,
    val checkedObstacles: Int,
    /** Smallest distance found, or null when nothing was checked. */
    val worstClearanceM: Float?,
    val valid: Boolean,
) {
    /** True when the run has at least one clash. */
    val hasClash: Boolean get() = hits.isNotEmpty()
}

/** Distance between a segment and a box, and where on the segment it happens. */
data class SegmentBoxDistance(val distanceM: Float, val fraction: Float)

/** Distance between two segments, and the parameters that realise it. */
data class SegmentSegmentDistance(
    val distanceM: Float,
    val firstFraction: Float,
    val secondFraction: Float,
)

private const val GEOMETRY_EPSILON = 1e-9f

/**
 * The geometry of the check, exposed because it is testable on its own and reusable by anything
 * else that has to reason about runs and boxes.
 */
object ClashGeometry {

    /** Shortest distance from [point] to the box - zero inside it. */
    fun pointToAabbDistance(point: Vec3, bounds: Aabb): Float {
        val dx = axisDistance(point.x, bounds.min.x, bounds.max.x)
        val dy = axisDistance(point.y, bounds.min.y, bounds.max.y)
        val dz = axisDistance(point.z, bounds.min.z, bounds.max.z)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Slab clip of the segment against the box.
     *
     * @return the entry fraction on the segment, or null when the segment misses the box
     */
    fun segmentEntryFraction(start: Vec3, end: Vec3, bounds: Aabb): Float? {
        var enter = 0f
        var exit = 1f
        for (axis in 0 until 3) {
            val from = start[axis]
            val delta = end[axis] - from
            val low = bounds.min[axis]
            val high = bounds.max[axis]
            if (abs(delta) < GEOMETRY_EPSILON) {
                // Parallel to this pair of faces: the segment has to already lie between them.
                if (from < low || from > high) return null
            } else {
                var t0 = (low - from) / delta
                var t1 = (high - from) / delta
                if (t0 > t1) {
                    val swap = t0
                    t0 = t1
                    t1 = swap
                }
                enter = max(enter, t0)
                exit = min(exit, t1)
                if (enter > exit) return null
            }
        }
        return enter
    }

    /**
     * Distance from the segment to the box, exact.
     *
     * Zero when the segment intersects the box (a run through a beam), otherwise the minimum of the
     * two endpoint distances and the twelve edge-to-segment distances - which is the true answer
     * for a segment and a convex box, unlike an edge-only or endpoint-only test.
     */
    fun segmentToAabb(start: Vec3, end: Vec3, bounds: Aabb): SegmentBoxDistance {
        val entry = segmentEntryFraction(start, end, bounds)
        if (entry != null) return SegmentBoxDistance(0f, entry)

        var bestDistance = pointToAabbDistance(start, bounds)
        var bestFraction = 0f
        val endDistance = pointToAabbDistance(end, bounds)
        if (endDistance < bestDistance) {
            bestDistance = endDistance
            bestFraction = 1f
        }
        for ((corner0, corner1) in boundsEdges(bounds)) {
            val measure = segmentToSegment(start, end, corner0, corner1)
            if (measure.distanceM < bestDistance) {
                bestDistance = measure.distanceM
                bestFraction = measure.firstFraction
            }
        }
        return SegmentBoxDistance(bestDistance, bestFraction)
    }

    /**
     * Closest approach between two segments (Ericson, *Real-Time Collision Detection* §5.1.9).
     *
     * Handles the degenerate cases the formula divides by: a segment collapsed to a point, and two
     * parallel segments. Both are real here - a route can have two identical nodes, and a wall's
     * edge can be parallel to the run that hugs it.
     */
    fun segmentToSegment(p1: Vec3, q1: Vec3, p2: Vec3, q2: Vec3): SegmentSegmentDistance {
        val d1 = q1 - p1
        val d2 = q2 - p2
        val r = p1 - p2
        val a = d1 dot d1
        val e = d2 dot d2
        val f = d2 dot r

        var s: Float
        var t: Float
        if (a <= GEOMETRY_EPSILON && e <= GEOMETRY_EPSILON) {
            s = 0f
            t = 0f
        } else if (a <= GEOMETRY_EPSILON) {
            s = 0f
            t = clamp01(f / e)
        } else {
            val c = d1 dot r
            if (e <= GEOMETRY_EPSILON) {
                t = 0f
                s = clamp01(-c / a)
            } else {
                val b = d1 dot d2
                val denominator = a * e - b * b
                s = if (denominator != 0f) clamp01((b * f - c * e) / denominator) else 0f
                t = (b * s + f) / e
                if (t < 0f) {
                    t = 0f
                    s = clamp01(-c / a)
                } else if (t > 1f) {
                    t = 1f
                    s = clamp01((b - c) / a)
                }
            }
        }
        val closest1 = p1 + d1 * s
        val closest2 = p2 + d2 * t
        return SegmentSegmentDistance(closest1.distanceTo(closest2), s, t)
    }

    internal fun boundsEdges(bounds: Aabb): List<Pair<Vec3, Vec3>> {
        val lo = bounds.min
        val hi = bounds.max
        fun corner(x: Boolean, y: Boolean, z: Boolean) = Vec3(
            if (x) hi.x else lo.x,
            if (y) hi.y else lo.y,
            if (z) hi.z else lo.z,
        )
        val c000 = corner(false, false, false)
        val c001 = corner(false, false, true)
        val c010 = corner(false, true, false)
        val c011 = corner(false, true, true)
        val c100 = corner(true, false, false)
        val c101 = corner(true, false, true)
        val c110 = corner(true, true, false)
        val c111 = corner(true, true, true)
        return listOf(
            c000 to c001, c000 to c010, c001 to c011, c010 to c011,
            c100 to c101, c100 to c110, c101 to c111, c110 to c111,
            c000 to c100, c001 to c101, c010 to c110, c011 to c111,
        )
    }

    private fun axisDistance(value: Float, low: Float, high: Float): Float = when {
        value < low -> low - value
        value > high -> value - high
        else -> 0f
    }

    private fun clamp01(value: Float): Float = when {
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }
}

/**
 * Checks every segment of [route] against every obstacle.
 *
 * A hit is a **penetration** (distance 0) or a distance below the clearance that applies to that
 * obstacle - its own [Obstacle.clearanceM] when that is larger than [defaultClearanceM], so a
 * service that needs 20 cm is held to 20 cm and everything else to the house default.
 *
 * @param route the run as measured, in order
 * @param obstacles boxes to keep clear of
 */
fun checkClearance(
    route: List<Vec3>,
    obstacles: List<Obstacle>,
    defaultClearanceM: Float = 0.05f,
): ClearanceReport {
    val validInput = route.size >= 2 &&
        route.all { it.isFinite() } &&
        obstacles.all { it.clearanceM >= 0f && it.bounds.isFinite() && !it.bounds.isEmpty } &&
        defaultClearanceM >= 0f
    if (!validInput) {
        return ClearanceReport(
            hits = emptyList(),
            checkedSegments = 0,
            checkedObstacles = 0,
            worstClearanceM = null,
            valid = false,
        )
    }

    val hits = ArrayList<ClashHit>()
    var worst = Float.POSITIVE_INFINITY
    for (index in 0 until route.size - 1) {
        val start = route[index]
        val end = route[index + 1]
        for (obstacle in obstacles) {
            val measure = ClashGeometry.segmentToAabb(start, end, obstacle.bounds)
            if (measure.distanceM < worst) worst = measure.distanceM
            val required = max(defaultClearanceM, obstacle.clearanceM)
            val kind = when {
                measure.distanceM <= GEOMETRY_EPSILON -> ClashKind.THROUGH_OBSTACLE
                measure.distanceM < required -> ClashKind.BELOW_CLEARANCE
                else -> null
            } ?: continue
            hits += ClashHit(
                obstacleId = obstacle.id,
                segmentIndex = index,
                kind = kind,
                distanceM = measure.distanceM,
                atPoint = start + (end - start) * measure.fraction,
                segmentFraction = measure.fraction,
            )
        }
    }

    return ClearanceReport(
        hits = hits.sortedWith(compareBy({ it.segmentIndex }, { it.distanceM }, { it.obstacleId })),
        checkedSegments = route.size - 1,
        checkedObstacles = obstacles.size,
        // Null rather than infinity when nothing was measured: a number that is not a distance
        // must not travel into a report that a screen or a PDF will print.
        worstClearanceM = if (worst.isFinite()) worst else null,
        valid = true,
    )
}

/** A box has to be finite and the right way round before anything is measured against it. */
internal fun Aabb.isFinite(): Boolean =
    min.isFinite() && max.isFinite() && min.x <= max.x && min.y <= max.y && min.z <= max.z

/** True when every coordinate is a real number. */
internal fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
