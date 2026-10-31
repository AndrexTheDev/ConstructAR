package com.constructar.core.geo

import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3

/**
 * An orthonormal frame on a curve: [tangent] along the path, [normal]/[binormal] spanning
 * the cross-section. Used to place every ring of a swept tube and every rectangle of a
 * swept duct.
 */
data class CurveFrame(
    val origin: Vec3,
    val tangent: Vec3,
    val normal: Vec3,
    val binormal: Vec3,
) {
    fun pointAt(lateral: Float, radial: Float): Vec3 =
        origin + binormal * lateral + normal * radial

    /** Rotation whose local +Z is the tangent (Filament / glTF convention). */
    val rotation: Quat get() = Quat.fromBasis(binormal, normal, tangent)
}

/**
 * Rotation-minimising frames (double reflection method, Wang et al. 2008).
 *
 * A naive "up = world up" frame twists badly when a run passes a ceiling or turns through
 * 180°, which shows up as a visibly pinched tube. Parallel transport instead propagates
 * the first frame along the curve with the minimum possible rotation, giving the smooth,
 * twist-free sweep a cable actually describes.
 */
object ParallelTransport {

    /**
     * @param points       Dense centreline (≥ 2 points).
     * @param initialUp    Seed for the first cross-section; [PathSmoother.initialUpHint]
     *                     yields a sensible world-up-based seed.
     */
    fun frames(points: List<Vec3>, initialUp: Vec3? = null): List<CurveFrame> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) {
            val tangent = Vec3.Z_AXIS
            val normal = (initialUp ?: PathSmoother.initialUpHint(tangent)).normalized()
            return listOf(CurveFrame(points[0], tangent, normal, tangent cross normal))
        }

        val tangents = ArrayList<Vec3>(points.size)
        for (i in points.indices) {
            val tangent = when (i) {
                0 -> points[1] - points[0]
                points.size - 1 -> points[i] - points[i - 1]
                else -> points[i + 1] - points[i - 1]
            }.normalizedOrZero()
            tangents.add(if (tangent.isDegenerate()) (tangents.lastOrNull() ?: Vec3.Z_AXIS) else tangent)
        }

        val seed = initialUp ?: PathSmoother.initialUpHint(tangents.first())
        // Project the seed onto the plane perpendicular to the first tangent.
        var normal = (seed - tangents[0] * (seed dot tangents[0])).normalizedOrZero()
        if (normal.isDegenerate()) normal = tangents[0].anyOrthogonal()

        val frames = ArrayList<CurveFrame>(points.size)
        frames.add(CurveFrame(points[0], tangents[0], normal, tangents[0] cross normal))

        for (i in 0 until points.size - 1) {
            val previous = frames[i]
            var r = previous.normal
            val l = previous.tangent
            val t1 = tangents[i + 1]

            // v1: the chord between consecutive samples.
            val v1 = points[i + 1] - points[i]
            val c1 = v1 dot v1

            var reflectedR: Vec3
            var reflectedT: Vec3
            if (c1 < 1e-10f) {
                // Coincident samples: the frame simply carries over.
                reflectedR = r
                reflectedT = l
            } else {
                // First reflection: send v1 onto itself (rotates r and l together).
                val k1 = (2f / c1) * (v1 dot r)
                reflectedR = r - v1 * k1
                val k2 = (2f / c1) * (v1 dot l)
                reflectedT = l - v1 * k2
            }

            // Second reflection: rotate so the reflected tangent matches the next tangent.
            val v2 = t1 - reflectedT
            val c2 = v2 dot v2
            val nextNormal = if (c2 < 1e-10f) {
                reflectedR
            } else {
                reflectedR - v2 * ((2f / c2) * (v2 dot reflectedR))
            }

            r = nextNormal.normalizedOrZero()
            if (r.isDegenerate()) r = t1.anyOrthogonal()
            // Re-orthogonalise to absorb accumulated floating point error.
            r = (r - t1 * (r dot t1)).normalizedOrZero()
            if (r.isDegenerate()) r = t1.anyOrthogonal()

            frames.add(CurveFrame(points[i + 1], t1, r, t1 cross r))
        }
        return frames
    }
}

/**
 * Surface-relative orientation helpers.
 *
 * Everything the app snaps to a wall/floor/ceiling passes through here:
 *  * [facingCamera] fixes ARCore's plane normal sign (its normals point *out of the
 *    material*, so a wall tapped from the room side can arrive pointing into the masonry).
 *  * [offsetAlongNormal] lifts the conduit centre axis so the cable/duct rests *on* the
 *    plaster instead of half-buried in it.
 *  * [tangentFrame] gives a rectangular channel its "lies flat on the wall" orientation.
 */
object SurfaceAlignment {

    /** Returns [normal] flipped so that it points towards [viewer]. */
    fun facingCamera(normal: Vec3, surfacePoint: Vec3, viewer: Vec3): Vec3 {
        val n = normal.normalizedOrZero()
        if (n.isDegenerate()) return Vec3.Y_AXIS
        val toViewer = viewer - surfacePoint
        return if ((n dot toViewer) < 0f) -n else n
    }

    /** Moves [point] along [normal] by [offsetMeters] (negative digs into the surface). */
    fun offsetAlongNormal(point: Vec3, normal: Vec3, offsetMeters: Float): Vec3 =
        point + normal.normalized() * offsetMeters

    /**
     * Cross-section frame for a rectangular duct at [station].
     *
     * The duct lies flat against [surfaceNormal] (its back face on the plaster) and runs
     * along [tangent]. The lateral axis is `tangent × surfaceNormal`, so the returned
     * frame's normal is *always* the surface normal — no twisting, which is what makes two
     * stacked ducts look parallel.
     */
    fun tangentFrame(station: Vec3, tangent: Vec3, surfaceNormal: Vec3): CurveFrame {
        val t = tangent.normalized()
        var n = (surfaceNormal - t * (surfaceNormal dot t)).normalizedOrZero()
        if (n.isDegenerate(0.05f)) {
            // Tangent almost parallel to the surface normal (e.g. a duct leaving a wall):
            // fall back to the world-up reference instead of producing a zero frame.
            n = PathSmoother.initialUpHint(t)
        }
        // Keep the normal on the same side as the surface normal we were given.
        if ((n dot surfaceNormal) < 0f) n = -n
        val lateral = t cross n
        return CurveFrame(station, t, n, lateral)
    }

    /**
     * True when the surface normal is consistent with the previous one (≤ [maxDegrees]
     * apart). Used to reject a node that snapped through a corner onto a 90°-opposed face.
     */
    fun isConsistent(previous: Vec3, next: Vec3, maxDegrees: Float = 75f): Boolean {
        val angle = previous.angleTo(next)
        return angle <= Math.toRadians(maxDegrees.toDouble()).toFloat()
    }
}
