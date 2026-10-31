package com.constructar.core.mesh

import com.constructar.core.geo.CurveFrame
import com.constructar.core.geo.PathSmoother
import com.constructar.core.geo.SmoothedPath
import com.constructar.core.geo.SurfaceAlignment
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitKind
import com.constructar.core.model.ConduitSpec
import kotlin.math.max

/** Tuning for the rectangular-channel sweep. */
data class DuctMeshOptions(
    val startCap: CapStyle = CapStyle.FLAT,
    val endCap: CapStyle = CapStyle.FLAT,
    /** Texture repeat distance along the run, metres. */
    val uvMetersPerTile: Float = 1f,
    /** Slight inset (<1) shrinks the section so butt joints do not z-fight. */
    val sectionScale: Float = 1f,
)

/**
 * Sweeps a rectangular section along a smoothed path — the procedural mesh generator for
 * ducts and cable channels.
 *
 * Unlike a tube, a duct must stay *flat against the surface*: its cross-section is built on
 * a [SurfaceAlignment.tangentFrame], whose normal is the (interpolated) surface normal at
 * that station. Two ducts stacked on the same wall therefore stay parallel instead of
 * twisting with the curve.
 *
 * Each of the four faces is its own vertex strip with a constant face normal, giving the
 * crisp arrises a channel has in reality (a shared-normal sweep would shade it like a
 * sausage). `2` vertices per station per face ⇒ `8·S` vertices, `2·(S−1)` triangles per
 * face, plus two end caps.
 */
object DuctMeshGenerator {

    fun generate(
        path: SmoothedPath,
        surfaceNormals: List<Vec3>,
        spec: ConduitSpec,
        options: DuctMeshOptions = DuctMeshOptions(),
    ): MeshData {
        require(spec.kind == ConduitKind.DUCT) { "DuctMeshGenerator handles rectangular profiles only" }
        require(path.points.size >= 2) { "Path needs at least two points" }
        require(surfaceNormals.size == path.points.size) {
            "Expected one surface normal per path point (${path.points.size}), got ${surfaceNormals.size}"
        }
        val scale = options.sectionScale.coerceIn(0.5f, 1.5f)
        val halfWidth = spec.widthMeters * 0.5f * scale
        val halfHeight = spec.heightMeters * 0.5f * scale
        require(halfWidth > 0f && halfHeight > 0f) { "Duct needs positive width and height" }

        val builder = MeshBuilder(path.points.size * 8 + 16)
        val frames = ArrayList<CurveFrame>(path.points.size)
        for (i in path.points.indices) {
            frames += SurfaceAlignment.tangentFrame(path.points[i], path.tangents[i], surfaceNormals[i])
        }
        val tile = max(0.01f, options.uvMetersPerTile)

        /**
         * Emits one flat ribbon for a face with the given outward normal.
         * `b = n_face × tangent` is the in-face lateral direction, chosen so the quad
         * winding `(p0_i, p0_i+1, p1_i+1, p1_i)` faces along `n_face`.
         */
        fun addFace(faceNormalOf: (CurveFrame) -> Vec3, centerOffsetOf: (CurveFrame) -> Vec3, halfExtent: Float) {
            val start = builder.vertexCount
            for (i in frames.indices) {
                val frame = frames[i]
                val faceNormal = faceNormalOf(frame).normalized()
                val center = frame.origin + centerOffsetOf(frame)
                val along = (faceNormal cross frame.tangent).normalized() * halfExtent
                val uv = path.arcLengths[i] / tile
                builder.addVertex(center - along, faceNormal, 0f to uv)
                builder.addVertex(center + along, faceNormal, 1f to uv)
            }
            for (i in 0 until frames.size - 1) {
                val a = start + i * 2
                val b = a + 2
                val c = b + 1
                val d = a + 1
                builder.addQuad(a, b, c, d)
            }
        }

        // +normal face (front, looking into the room)
        addFace({ f -> f.normal }, { f -> f.normal * halfHeight }, halfWidth)
        // −normal face (back, against the plaster)
        addFace({ f -> -f.normal }, { f -> -f.normal * halfHeight }, halfWidth)
        // +lateral face (right)
        addFace({ f -> f.binormal }, { f -> f.binormal * halfWidth }, halfHeight)
        // −lateral face (left)
        addFace({ f -> -f.binormal }, { f -> -f.binormal * halfWidth }, halfHeight)

        if (options.startCap != CapStyle.NONE) {
            addCap(builder, frames.first(), halfWidth, halfHeight, isStart = true)
        }
        if (options.endCap != CapStyle.NONE) {
            addCap(builder, frames.last(), halfWidth, halfHeight, isStart = false)
        }
        return builder.build()
    }

    /** Rectangular end cap: four corners, two triangles, normal = ±tangent. */
    private fun addCap(
        builder: MeshBuilder,
        frame: CurveFrame,
        halfWidth: Float,
        halfHeight: Float,
        isStart: Boolean,
    ) {
        val outward = if (isStart) -frame.tangent else frame.tangent
        val l = frame.binormal * halfWidth
        val n = frame.normal * halfHeight
        val c = frame.origin
        // Corners ordered counter-clockwise seen from outside the cap.
        val corners = if (isStart) {
            listOf(c + l + n, c - l + n, c - l - n, c + l - n)
        } else {
            listOf(c - l + n, c + l + n, c + l - n, c - l - n)
        }
        val base = builder.vertexCount
        corners.forEachIndexed { index, corner ->
            builder.addVertex(corner, outward, index / 3f to (index % 2).toFloat())
        }
        // (base, base+1, base+2, base+3) is a convex quad; split along the 0–2 diagonal.
        builder.addTriangle(base, base + 1, base + 2)
        builder.addTriangle(base, base + 2, base + 3)
    }
}
