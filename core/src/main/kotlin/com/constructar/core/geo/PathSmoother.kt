package com.constructar.core.geo

import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The smoothed centreline of a routed conduit.
 *
 * [points] is a dense polyline (straight spans + sampled corner arcs). [arcLengths] holds
 * the cumulative distance at each point, so callers can resample, texture and measure
 * without re-integrating. All values are metres in ARCore world space.
 */
data class SmoothedPath(
    val points: List<Vec3>,
    val tangents: List<Vec3>,
    val arcLengths: FloatArray,
    val totalLengthMeters: Float,
    val cornerCount: Int,
    /** Smallest bend radius actually achieved, metres — compared against the cable's MBR. */
    val minimumBendRadiusMeters: Float,
    val closed: Boolean,
) {
    init {
        require(points.size == tangents.size) { "points/tangents size mismatch" }
        require(points.size == arcLengths.size) { "points/arcLengths size mismatch" }
        require(points.size >= 2) { "A path needs at least two points" }
    }
}

/**
 * Corner smoothing for routed conduits.
 *
 * A cable does not turn on a mathematical corner: it bends on a circular arc whose radius
 * is bounded from below by the conductor's minimum bending radius (typically 4–8 × the
 * outer diameter, 15 × for fibre). This object turns the operator's straight node chain
 * into tangent-continuous geometry:
 *
 * ```
 *   in ────────T1 ╭─────╮ T2──────── out
 *                 ╰──P──╯            P = the tapped corner node
 * ```
 *
 * For a deflection angle δ between the incoming and outgoing direction, the tangent
 * (trim) distance is `t = r · tan(δ/2)` and the arc centre sits at
 * `P + bisector · r / cos(δ/2)`. The radius is clamped so a fillet can never consume more
 * than 45 % of either adjacent segment — corners in a tight corner of the room therefore
 * degrade gracefully to a sharp elbow instead of producing self-intersecting geometry.
 *
 * The same routine is used for round tubes (cables/pipes) and for rectangular ducts, so
 * a duct and a cable routed over the same nodes follow the same intent.
 */
object PathSmoother {

    /** Below this deflection (degrees) a corner is left straight — noise from tapping. */
    const val MIN_DEFLECTION_DEGREES: Float = 2.5f

    /** Never let a fillet eat more than this fraction of the adjacent straight segment. */
    private const val MAX_TRIM_FRACTION = 0.45f

    /** Hard cap on arc subdivisions so a tight helix cannot explode the mesh. */
    private const val MAX_ARC_SAMPLES = 32

    /**
     * @param nodes                 Operator-tapped nodes, metres.
     * @param bendRadiusMeters      Desired bend radius (clamped per corner).
     * @param maxStraightSpanMeters Straight segments longer than this get extra samples so
     *                              very long runs still have evenly distributed vertices
     *                              (useful for the HUD label position and for exporters that
     *                              split runs into 1 m sections).
     * @param targetArcSampleMeters Desired spacing between arc samples (≤ 2 cm for tubes).
     * @param closed                Join the last node back to the first (ring runs).
     */
    fun smooth(
        nodes: List<Vec3>,
        bendRadiusMeters: Float = 0.05f,
        maxStraightSpanMeters: Float = 1.0f,
        targetArcSampleMeters: Float = 0.02f,
        closed: Boolean = false,
    ): SmoothedPath {
        val cleaned = sanitise(nodes, closed)
        require(cleaned.size >= 2) { "A route needs at least two distinct nodes (got ${cleaned.size})" }

        val radius = bendRadiusMeters.coerceAtLeast(0f)
        val result = ArrayList<Vec3>(cleaned.size * 6)
        var cornerCount = 0
        var minRadius = Float.MAX_VALUE

        // Number of corners: interior nodes for open paths, every node for closed loops.
        val count = cleaned.size
        val lastCornerIndex = if (closed) count - 1 else count - 2

        // Walk the original nodes, emitting straights and arcs.
        for (i in cleaned.indices) {
            val current = cleaned[i]
            val isCorner = closed || (i in 1..lastCornerIndex)

            if (!isCorner) {
                appendPoint(result, current)
                continue
            }

            val previous = cleaned[(i - 1 + count) % count]
            val next = cleaned[(i + 1) % count]
            val fillet = computeFillet(previous, current, next, radius)

            if (fillet == null) {
                appendPoint(result, current)
                continue
            }
            cornerCount++
            minRadius = minOf(minRadius, fillet.radius)

            // Enter the arc: straight up to T1.
            appendPoint(result, fillet.tangentIn)

            val arcSamples = arcSampleCount(fillet, targetArcSampleMeters)
            for (s in 1 until arcSamples) {
                val t = s.toFloat() / arcSamples
                appendPoint(result, fillet.sample(t))
            }
            appendPoint(result, fillet.tangentOut)
        }

        if (closed && result.size > 1) {
            val first = result.first()
            val last = result.last()
            if (last.distanceTo(first) < 1e-5f) result.removeAt(result.size - 1)
        }

        // Densify long straight spans (keeps vertex distribution even on 20 m runs).
        val densified = densify(result, maxStraightSpanMeters, closed)

        val arcLengths = cumulativeLengths(densified, closed)
        val tangents = centralTangents(densified, closed)
        val total = if (arcLengths.isEmpty()) 0f else arcLengths.last()

        return SmoothedPath(
            points = densified,
            tangents = tangents,
            arcLengths = arcLengths,
            totalLengthMeters = total,
            cornerCount = cornerCount,
            minimumBendRadiusMeters = if (minRadius == Float.MAX_VALUE) radius else minRadius,
            closed = closed,
        )
    }

    /** A circular fillet at one corner, parameterised from [tangentIn] to [tangentOut]. */
    data class Fillet(
        val tangentIn: Vec3,
        val tangentOut: Vec3,
        val center: Vec3,
        val radius: Float,
        val sweepRadians: Float,
        val axis: Vec3,
    ) {
        fun sample(t: Float): Vec3 {
            val v = tangentIn - center
            val rotation = Quat.fromAxisAngle(axis, sweepRadians * t)
            return center + rotation.rotate(v)
        }
    }

    /**
     * Builds the fillet for corner [p] between [previous] and [next], or null when the
     * corner is (almost) straight or the requested radius is unusable.
     */
    fun computeFillet(previous: Vec3, p: Vec3, next: Vec3, requestedRadius: Float): Fillet? {
        val eIn = (p - previous)
        val eOut = (next - p)
        val lenIn = eIn.length
        val lenOut = eOut.length
        if (lenIn < 1e-4f || lenOut < 1e-4f) return null

        val dirIn = eIn / lenIn
        val dirOut = eOut / lenOut
        val deflection = dirIn.angleTo(dirOut) // δ ∈ [0, π]
        if (deflection < Math.toRadians(MIN_DEFLECTION_DEGREES.toDouble()).toFloat()) return null
        if (deflection > PI.toFloat() - 1e-4f) return null // U-turn: no valid fillet

        val halfDelta = deflection * 0.5f
        val maxTrim = MAX_TRIM_FRACTION * minOf(lenIn, lenOut)
        val wantedTan = tan(halfDelta)
        if (wantedTan < 1e-5f) return null
        // t = r·tan(δ/2)  ⇒  r ≤ maxTrim / tan(δ/2)
        val radius = minOf(requestedRadius, maxTrim / wantedTan)
        if (radius < 1e-3f) return null

        val trim = radius * wantedTan
        // e1 points from the corner back along the incoming leg, e2 along the outgoing leg.
        val e1 = -dirIn
        val e2 = dirOut
        val bisector = (e1 + e2).normalizedOrZero()
        if (bisector.isDegenerate()) return null

        val center = p + bisector * (radius / cos(halfDelta).coerceAtLeast(1e-4f))
        val tangentIn = p + e1 * trim
        val tangentOut = p + e2 * trim

        val axis = (e1 cross e2).normalizedOrZero()
        if (axis.isDegenerate()) return null
        val sweep = halfDelta * 2f

        // Guard: the arc must actually start and end where we say it does.
        val rIn = (tangentIn - center).length
        val rOut = (tangentOut - center).length
        if (abs(rIn - radius) > radius * 0.05f + 1e-3f || abs(rOut - radius) > radius * 0.05f + 1e-3f) {
            return null
        }

        return Fillet(tangentIn, tangentOut, center, radius, sweep, axis)
    }

    private fun arcSampleCount(fillet: Fillet, targetSpacing: Float): Int {
        if (targetSpacing <= 0f) return 4
        val arcLength = fillet.radius * fillet.sweepRadians
        return ceil(arcLength / targetSpacing).toInt().coerceIn(2, MAX_ARC_SAMPLES)
    }

    /** Splits straight spans longer than [maxSpan] into equal sub-spans. */
    private fun densify(points: List<Vec3>, maxSpan: Float, closed: Boolean): List<Vec3> {
        if (points.size < 2) return points
        val out = ArrayList<Vec3>(points.size * 2)
        val limit = if (maxSpan <= 0f) Float.MAX_VALUE else maxSpan
        val lastIndex = if (closed) points.size else points.size - 1
        for (i in 0 until lastIndex) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            out.add(a)
            val distance = a.distanceTo(b)
            if (distance > limit) {
                val parts = ceil(distance / limit).toInt()
                for (k in 1 until parts) out.add(a.lerp(b, k.toFloat() / parts))
            }
        }
        if (!closed) out.add(points.last())
        return out
    }

    private fun cumulativeLengths(points: List<Vec3>, closed: Boolean): FloatArray {
        val lengths = FloatArray(points.size)
        var sum = 0f
        for (i in 1 until points.size) {
            sum += points[i].distanceTo(points[i - 1])
            lengths[i] = sum
        }
        if (closed && points.size > 2) sum += points.last().distanceTo(points.first())
        // For closed loops the final entry reports the total (trailing edge).
        if (closed && lengths.isNotEmpty()) lengths[lengths.size - 1] = sum
        return lengths
    }

    /** Central-difference tangents; endpoints use one-sided differences. */
    private fun centralTangents(points: List<Vec3>, closed: Boolean): List<Vec3> {
        val n = points.size
        val normals = ArrayList<Vec3>(n)
        for (i in 0 until n) {
            val tangent = when {
                closed -> {
                    val previous = points[(i - 1 + n) % n]
                    val next = points[(i + 1) % n]
                    // Unwrap the closing edge so the tangent does not collapse at the seam.
                    (next - previous) + (if (i == 0) (points[0] - points[0]) else Vec3.ZERO)
                }
                i == 0 -> points[1] - points[0]
                i == n - 1 -> points[n - 1] - points[n - 2]
                else -> points[i + 1] - points[i - 1]
            }
            normals.add(tangent.normalizedOrZero())
        }
        // Repair any degenerate tangent by inheriting the previous one.
        for (i in normals.indices) {
            if (normals[i].isDegenerate()) {
                normals[i] = if (i > 0) normals[i - 1] else (normals.firstOrNull { !it.isDegenerate() } ?: Vec3.X_AXIS)
            }
        }
        return normals
    }

    private fun appendPoint(list: ArrayList<Vec3>, p: Vec3) {
        val last = list.lastOrNull()
        if (last == null || last.distanceTo(p) > 1e-5f) list.add(p)
    }

    /** Drops duplicated / sub-millimetre nodes that would create zero-length segments. */
    private fun sanitise(nodes: List<Vec3>, closed: Boolean): List<Vec3> {
        if (nodes.isEmpty()) return nodes
        val out = ArrayList<Vec3>(nodes.size)
        for (node in nodes) {
            val last = out.lastOrNull()
            if (last == null || last.distanceTo(node) > 1e-4f) out.add(node)
        }
        if (closed && out.size > 2 && out.first().distanceTo(out.last()) < 1e-4f) out.removeAt(out.size - 1)
        return out
    }

    /**
     * Resamples a path at a fixed spacing — used by the exporter to emit one CSV row per
     * metre and by the HUD to keep its label centred.
     */
    fun resample(path: SmoothedPath, spacingMeters: Float): List<Vec3> {
        if (spacingMeters <= 0f || path.totalLengthMeters <= 0f) return path.points
        val out = ArrayList<Vec3>()
        val steps = (path.totalLengthMeters / spacingMeters).toInt()
        var segment = 0
        out.add(path.points.first())
        for (step in 1 until steps) {
            val target = step * spacingMeters
            while (segment < path.arcLengths.size - 2 && path.arcLengths[segment + 1] < target) segment++
            val s0 = path.arcLengths[segment]
            val s1 = path.arcLengths[segment + 1]
            val span = (s1 - s0).takeIf { it > 1e-6f } ?: 1f
            val t = ((target - s0) / span).coerceIn(0f, 1f)
            out.add(path.points[segment].lerp(path.points[segment + 1], t))
        }
        out.add(path.points.last())
        return out
    }

    /** Point at a normalised position (0 = start, 1 = end) along the arc length. */
    fun pointAt(path: SmoothedPath, fraction: Float): Vec3 {
        if (path.points.isEmpty()) return Vec3.ZERO
        if (path.totalLengthMeters <= 0f) return path.points.first()
        val target = (fraction.coerceIn(0f, 1f)) * path.totalLengthMeters
        for (i in 1 until path.arcLengths.size) {
            if (path.arcLengths[i] >= target) {
                val s0 = path.arcLengths[i - 1]
                val s1 = path.arcLengths[i]
                val span = (s1 - s0).takeIf { it > 1e-6f } ?: 1f
                return path.points[i - 1].lerp(path.points[i], ((target - s0) / span).coerceIn(0f, 1f))
            }
        }
        return path.points.last()
    }

    /** Vertical (world +Y) "up" hint used to seed a stable frame at the start of a run. */
    fun initialUpHint(firstTangent: Vec3): Vec3 {
        val up = Vec3.Y_AXIS
        val projected = up - firstTangent * (up dot firstTangent)
        return if (projected.isDegenerate(0.05f)) firstTangent.anyOrthogonal() else projected.normalized()
    }

    /** `sin` helper kept here so callers avoid importing kotlin.math. */
    internal fun sinDegrees(degrees: Float): Float = sin(Math.toRadians(degrees.toDouble()).toFloat())
}
