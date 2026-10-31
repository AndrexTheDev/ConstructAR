package com.constructar.core.math

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Ray in world space, `p(t) = origin + t · direction` with `direction` unit length. */
data class Ray(val origin: Vec3, val direction: Vec3) {
    fun pointAt(t: Float): Vec3 = origin + direction * t
}

/**
 * Infinite plane `n · x = d` with a unit normal.
 *
 * ConstructAR builds these from ARCore plane poses (`n = plane.up`, `d = n · plane.center`)
 * and from depth neighbourhoods (least-squares fit), then uses them for the strict
 * `isPoseInPolygon` style containment tests required by the hit-test cascade.
 */
data class Plane3(val normal: Vec3, val d: Float) {

    /** Signed distance of [p] from the plane: positive on the normal side. */
    fun signedDistance(p: Vec3): Float = normal dot p - d

    fun projectPoint(p: Vec3): Vec3 = p - normal * signedDistance(p)

    /** Orients the plane so [viewer] is on the positive side — "surface faces the user". */
    fun facing(viewer: Vec3): Plane3 =
        if (signedDistance(viewer) < 0f) Plane3(-normal, -d) else this

    /**
     * Ray/plane intersection distance `t` (≥ 0 when in front of the ray origin), or null
     * when the ray is parallel to the plane.
     */
    fun intersect(ray: Ray): Float? {
        val denom = normal dot ray.direction
        if (abs(denom) < 1e-6f) return null
        val t = (d - (normal dot ray.origin)) / denom
        return if (t.isFinite()) t else null
    }

    companion object {
        fun fromPointNormal(point: Vec3, normal: Vec3): Plane3 {
            val n = normal.normalized()
            return Plane3(n, n dot point)
        }

        /** Plane through three points; null if they are collinear. */
        fun fromPoints(a: Vec3, b: Vec3, c: Vec3): Plane3? {
            val normal = (b - a) cross (c - a)
            if (normal.isDegenerate(1e-7f)) return null
            return fromPointNormal(a, normal)
        }

        /**
         * Total-least-squares plane fit (PCA) of [points] — used to derive a stable surface
         * normal from a small depth patch, where three arbitrary samples are too noisy.
         * Returns the centroid as well so callers can reuse it.
         */
        fun fitLeastSquares(points: List<Vec3>): Plane3Fit? {
            if (points.size < 3) return null
            val centroid = points.centroid()
            var xx = 0.0
            var xy = 0.0
            var xz = 0.0
            var yy = 0.0
            var yz = 0.0
            var zz = 0.0
            for (p in points) {
                val dx = (p.x - centroid.x).toDouble()
                val dy = (p.y - centroid.y).toDouble()
                val dz = (p.z - centroid.z).toDouble()
                xx += dx * dx; xy += dx * dy; xz += dx * dz
                yy += dy * dy; yz += dy * dz; zz += dz * dz
            }
            val cov = doubleArrayOf(
                xx, xy, xz,
                xy, yy, yz,
                xz, yz, zz,
            )
            val eigen = symmetricEigenSmallest(cov) ?: return null
            val normal = Vec3(eigen[0].toFloat(), eigen[1].toFloat(), eigen[2].toFloat())
            if (normal.isDegenerate(1e-6f)) return null
            return Plane3Fit(Plane3.fromPointNormal(centroid, normal), centroid, points.size)
        }

        /**
         * Smallest-eigenvector of a symmetric 3×3 (row-major) matrix via cyclic Jacobi
         * rotations. 12 sweeps are ample for a 3×3 system; returns null if the matrix is
         * degenerate (a flat depth patch with no depth variance).
         */
        private fun symmetricEigenSmallest(m: DoubleArray): DoubleArray? {
            val a = m.copyOf()
            val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            for (sweep in 0 until 12) {
                var off = 0.0
                off += abs(a[1]); off += abs(a[2]); off += abs(a[5])
                if (off < 1e-12) break
                for (p in 0 until 3) {
                    for (q in p + 1 until 3) {
                        val apq = a[p * 3 + q]
                        if (abs(apq) < 1e-15) continue
                        val app = a[p * 3 + p]
                        val aqq = a[q * 3 + q]
                        val theta = (aqq - app) / (2.0 * apq)
                        val t = if (theta >= 0) 1.0 / (theta + sqrt(theta * theta + 1)) else 1.0 / (theta - sqrt(theta * theta + 1))
                        val c = 1.0 / sqrt(t * t + 1)
                        val s = t * c
                        for (k in 0 until 3) {
                            val akp = a[k * 3 + p]
                            val akq = a[k * 3 + q]
                            a[k * 3 + p] = c * akp - s * akq
                            a[k * 3 + q] = s * akp + c * akq
                        }
                        for (k in 0 until 3) {
                            val apk = a[p * 3 + k]
                            val aqk = a[q * 3 + k]
                            a[p * 3 + k] = c * apk - s * aqk
                            a[q * 3 + k] = s * apk + c * aqk
                        }
                        for (k in 0 until 3) {
                            val vkp = v[k * 3 + p]
                            val vkq = v[k * 3 + q]
                            v[k * 3 + p] = c * vkp - s * vkq
                            v[k * 3 + q] = s * vkp + c * vkq
                        }
                    }
                }
            }
            var best = 0
            var largest = a[0]
            for (i in 1 until 3) {
                if (a[i * 3 + i] < a[best * 3 + best]) best = i
                if (a[i * 3 + i] > largest) largest = a[i * 3 + i]
            }
            // All-coincident samples carry no orientation at all. (Non-planar patches are
            // still answered with the best-fit normal; callers check the residual, because
            // rejecting them outright would throw away perfectly usable depth normals.)
            if (largest <= 1e-12) return null
            return doubleArrayOf(v[best], v[3 + best], v[6 + best])
        }
    }
}

/** Result of a least-squares plane fit: plane, inlier centroid, sample count. */
data class Plane3Fit(val plane: Plane3, val centroid: Vec3, val sampleCount: Int)

/** Axis-aligned bounding box in metres. */
data class Aabb(val min: Vec3, val max: Vec3) {
    val center: Vec3 get() = (min + max) * 0.5f
    val size: Vec3 get() = max - min
    val halfExtent: Vec3 get() = size * 0.5f
    val isEmpty: Boolean get() = min.x > max.x || min.y > max.y || min.z > max.z

    fun contains(p: Vec3, epsilon: Float = 1e-4f): Boolean =
        p.x >= min.x - epsilon && p.x <= max.x + epsilon &&
            p.y >= min.y - epsilon && p.y <= max.y + epsilon &&
            p.z >= min.z - epsilon && p.z <= max.z + epsilon

    fun union(other: Aabb): Aabb = Aabb(min.minWith(other.min), max.maxWith(other.max))
    fun padded(meters: Float): Aabb = Aabb(min - Vec3(meters, meters, meters), max + Vec3(meters, meters, meters))

    companion object {
        fun of(points: Iterable<Vec3>): Aabb? {
            var min: Vec3? = null
            var max: Vec3? = null
            for (p in points) {
                min = min?.minWith(p) ?: p
                max = max?.maxWith(p) ?: p
            }
            val lo = min ?: return null
            val hi = max ?: return null
            return Aabb(lo, hi)
        }

        fun ofPoints(points: FloatArray, strideFloats: Int = 3): Aabb? {
            if (points.size < strideFloats) return null
            var lo = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            var hi = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var i = 0
            while (i + 2 < points.size) {
                val p = Vec3(points[i], points[i + 1], points[i + 2])
                lo = lo.minWith(p)
                hi = hi.maxWith(p)
                i += strideFloats
            }
            return Aabb(lo, hi)
        }
    }
}

/** Point–polygon containment on a plane (the `isPoseInPolygon` fallback). */
object PolygonMath {

    /**
     * True when [point] lies inside the convex or concave planar [polygon] (non-self-
     * intersecting, vertices in order). Uses the classic "sum of signed angles" test in the
     * polygon's own plane, which is robust for both winding directions and copes with
     * polygons whose vertices are only approximately coplanar (ARCore wall planes).
     *
     * Points within [toleranceMeters] of an edge count as inside: a worker tapping the very
     * edge of a detected wall must not be told "no surface here".
     */
    fun contains(polygon: List<Vec3>, point: Vec3, toleranceMeters: Float = 0.01f): Boolean {
        if (polygon.size < 3) return false
        val fit = Plane3.fromPoints(polygon[0], polygon[1], polygon[2])
            ?: return containsFallback(polygon, point, toleranceMeters)
        val n = fit.normal
        val projected = polygon.map { fit.projectPoint(it) }
        val p = fit.projectPoint(point)
        if (projected.any { (it - p).length <= toleranceMeters }) return true
        var angleSum = 0f
        for (i in projected.indices) {
            val a = projected[i] - p
            val b = projected[(i + 1) % projected.size] - p
            if (a.isDegenerate(1e-6f) || b.isDegenerate(1e-6f)) return true
            val cross = a cross b
            val sign = if ((cross dot n) >= 0f) 1f else -1f
            angleSum += sign * a.angleTo(b)
        }
        return abs(abs(angleSum) - 2f * kotlin.math.PI.toFloat()) < 0.1f
    }

    /** Degenerate-polygon path: nearest-edge distance test only. */
    private fun containsFallback(polygon: List<Vec3>, point: Vec3, toleranceMeters: Float): Boolean {
        var best = Float.MAX_VALUE
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            best = min(best, distanceToSegment(point, a, b))
        }
        return best <= max(toleranceMeters, 1e-3f)
    }

    /** Shortest distance from [p] to segment `a..b`. */
    fun distanceToSegment(p: Vec3, a: Vec3, b: Vec3): Float {
        val ab = b - a
        val lengthSq = ab.lengthSquared
        if (lengthSq < 1e-9f) return p.distanceTo(a)
        val t = ((p - a) dot ab) / lengthSq
        return p.distanceTo(a + ab * t.coerceIn(0f, 1f))
    }

    /** Area of a planar polygon via Newell's method (also yields a good normal). */
    fun area(polygon: List<Vec3>): Float {
        if (polygon.size < 3) return 0f
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            nx += (a.y - b.y) * (a.z + b.z)
            ny += (a.z - b.z) * (a.x + b.x)
            nz += (a.x - b.x) * (a.y + b.y)
        }
        return Vec3(nx, ny, nz).length * 0.5f
    }
}

object MathUtil {
    fun clamp(value: Float, min: Float, max: Float): Float = if (value < min) min else if (value > max) max else value
    fun clamp(value: Int, min: Int, max: Int): Int = if (value < min) min else if (value > max) max else value
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 == edge0) return if (x < edge0) 0f else 1f
        val t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Shortest signed angle (radians) from [from] to [to] around [axis]. */
    fun signedAngle(from: Vec3, to: Vec3, axis: Vec3): Float {
        val cross = from cross to
        val angle = kotlin.math.atan2((cross dot axis), (from dot to))
        return angle
    }
}
