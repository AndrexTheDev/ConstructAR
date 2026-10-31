package com.constructar.core.mesh

import com.constructar.core.geo.CurveFrame
import com.constructar.core.geo.ParallelTransport
import com.constructar.core.geo.PathSmoother
import com.constructar.core.geo.SmoothedPath
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitKind
import com.constructar.core.model.ConduitSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** How the open ends of a run are closed. */
enum class CapStyle {
    /** Flat disc — correct for a cable entering a wall box. */
    FLAT,

    /** Hemispherical cap — looks right for a bare cable end. */
    ROUND,

    /** Leaves the tube open (cheapest; used for debug overlays and LODs). */
    NONE,
}

/** Tuning for the round-conduit sweep. */
data class TubeMeshOptions(
    val radialSegments: Int = 12,
    val startCap: CapStyle = CapStyle.FLAT,
    val endCap: CapStyle = CapStyle.FLAT,
    /** Texture repeat distance along the run, metres (`UV.y = arcLength / this`). */
    val uvMetersPerTile: Float = 1f,
)

/**
 * Sweeps a circular cross-section along a smoothed path — the procedural mesh generator
 * for cables and pipes.
 *
 * Rings sit on rotation-minimising frames (no twist, no pinching at 180° turns) and are
 * welded by shared vertices. Vertex normals are exact (the radial direction), so
 * Filament's PBR shading reads the cylinder as round instead of faceted.
 *
 * `N` radial segments × `S` stations ⇒ `N·S` vertices and `2·N·(S−1)` triangles. A 15 m
 * run at 2 cm spacing with 12 segments is ~9.4 k vertices: trivial for a phone GPU and
 * small enough to keep GLB exports in the low hundreds of kB.
 *
 * Winding: every quad `(ring i, k) → (ring i, k+1) → (ring i+1, k+1) → (ring i+1, k)` is
 * counter-clockwise seen from outside, which is Filament's default front face. Caps reuse
 * the same rule with "advance direction = ±tangent", so the start cap gets mirrored
 * winding and stays visible under back-face culling.
 */
object TubeMeshGenerator {

    fun generate(
        path: SmoothedPath,
        spec: ConduitSpec,
        options: TubeMeshOptions = TubeMeshOptions(),
    ): MeshData {
        require(spec.kind.isRound) { "TubeMeshGenerator handles round profiles; use DuctMeshGenerator" }
        require(path.points.size >= 2) { "Path needs at least two points" }
        val radius = spec.outerDiameterMeters * 0.5f
        require(radius > 0f) { "Round conduit needs a positive diameter (was ${spec.diameterMm} mm)" }
        return generateRing(path, radius, options)
    }

    /** Core sweep for any radius (also used for in-scene node markers). */
    fun generateRing(
        path: SmoothedPath,
        radius: Float,
        options: TubeMeshOptions = TubeMeshOptions(),
    ): MeshData {
        val radial = options.radialSegments.coerceIn(3, 48)
        val frames: List<CurveFrame> = ParallelTransport.frames(
            path.points,
            PathSmoother.initialUpHint(path.tangents.first()),
        )
        val builder = MeshBuilder(frames.size * (radial + 1) + 64)
        val ringStride = radial + 1 // duplicate seam vertex so UV.x wraps 0 → 1 per ring
        val tile = max(0.01f, options.uvMetersPerTile)
        val twoPi = 2f * PI.toFloat()

        for (station in frames.indices) {
            val frame = frames[station]
            val v = path.arcLengths[station] / tile
            for (k in 0..radial) {
                val angle = twoPi * (k.toFloat() / radial)
                val direction = frame.normal * cos(angle) + frame.binormal * sin(angle)
                builder.addVertex(frame.origin + direction * radius, direction, k.toFloat() / radial to v)
            }
        }

        for (station in 0 until frames.size - 1) {
            for (k in 0 until radial) {
                val a = station * ringStride + k
                val b = a + 1
                val c = (station + 1) * ringStride + k + 1
                val d = (station + 1) * ringStride + k
                builder.addQuad(a, b, c, d)
            }
        }

        if (options.startCap != CapStyle.NONE) {
            addCap(builder, frames.first(), radius, radial, isStart = true, style = options.startCap)
        }
        if (options.endCap != CapStyle.NONE) {
            addCap(builder, frames.last(), radius, radial, isStart = false, style = options.endCap)
        }
        return builder.build()
    }

    /**
     * Closes one end. [isStart] selects the outward direction (`−tangent` at the start) and
     * mirrors the triangle winding accordingly.
     */
    private fun addCap(
        builder: MeshBuilder,
        frame: CurveFrame,
        radius: Float,
        radial: Int,
        isStart: Boolean,
        style: CapStyle,
    ) {
        val outward = if (isStart) -frame.tangent else frame.tangent
        val twoPi = 2f * PI.toFloat()

        if (style == CapStyle.FLAT) {
            val center = builder.addVertex(frame.origin, outward, 0.5f to 0.5f)
            val ringStart = builder.vertexCount
            for (k in 0 until radial) {
                val angle = twoPi * (k.toFloat() / radial)
                val direction = frame.normal * cos(angle) + frame.binormal * sin(angle)
                builder.addVertex(
                    frame.origin + direction * radius,
                    outward,
                    0.5f + 0.5f * cos(angle) to 0.5f + 0.5f * sin(angle),
                )
            }
            for (k in 0 until radial) {
                val a = ringStart + k
                val b = ringStart + (k + 1) % radial
                if (isStart) builder.addTriangle(center, b, a) else builder.addTriangle(center, a, b)
            }
            return
        }

        // Hemispherical cap: rim ring, then `stacks` progressively shrinking rings along the
        // outward axis. Normals point radially out of the sphere so the cap shades smoothly.
        val stacks = 3
        val angleStep = (PI.toFloat() * 0.5f) / stacks
        val ringIndices = Array(stacks) { IntArray(radial) }
        val rimIndex = IntArray(radial)

        for (k in 0 until radial) {
            val angle = twoPi * k / radial
            val direction = frame.normal * cos(angle) + frame.binormal * sin(angle)
            rimIndex[k] = builder.addVertex(
                frame.origin + direction * radius,
                direction,
                0.5f + 0.5f * cos(angle) to 0.5f + 0.5f * sin(angle),
            )
        }
        for (stack in 0 until stacks) {
            val phi = angleStep * (stack + 1)
            val ringRadius = radius * cos(phi)
            val along = radius * sin(phi)
            val center = frame.origin + outward * along
            for (k in 0 until radial) {
                val angle = twoPi * k / radial
                val radialDir = frame.normal * cos(angle) + frame.binormal * sin(angle)
                val direction = (radialDir * cos(phi) + outward * sin(phi)).normalized()
                ringIndices[stack][k] = builder.addVertex(
                    center + radialDir * ringRadius,
                    direction,
                    (0.5f + 0.5f * cos(phi) * cos(angle)) to (0.5f + 0.5f * cos(phi) * sin(angle)),
                )
            }
        }

        /** Connects ring [lower] to [upper] with outward-facing winding for this cap. */
        fun band(lower: IntArray, upper: IntArray) {
            for (k in 0 until radial) {
                val next = (k + 1) % radial
                if (isStart) {
                    builder.addQuad(lower[k], upper[k], upper[next], lower[next])
                } else {
                    builder.addQuad(lower[k], lower[next], upper[next], upper[k])
                }
            }
        }

        band(rimIndex, ringIndices[0])
        for (stack in 0 until stacks - 1) band(ringIndices[stack], ringIndices[stack + 1])
    }

    /** Nominal outer surface area (m²) — drives paint/consumable estimates. */
    fun surfaceArea(path: SmoothedPath, spec: ConduitSpec): Float =
        path.totalLengthMeters * 2f * PI.toFloat() * (spec.outerDiameterMeters * 0.5f)

    /** Which generator a spec needs; keeps call sites free of duplicated `when`s. */
    fun supports(spec: ConduitSpec): Boolean =
        spec.kind == ConduitKind.CABLE || spec.kind == ConduitKind.PIPE

    /** A small sphere — in-scene node markers and the "next point" ghost. */
    fun sphere(center: Vec3, radius: Float, segments: Int = 8): MeshData {
        val builder = MeshBuilder(segments * segments)
        val stacks = max(2, segments / 2)
        val twoPi = 2f * PI.toFloat()
        for (stack in 0..stacks) {
            val phi = PI.toFloat() * stack / stacks
            val y = cos(phi)
            val r = sin(phi)
            for (k in 0..segments) {
                val angle = twoPi * k / segments
                val direction = Vec3(r * cos(angle), y, r * sin(angle))
                builder.addVertex(
                    center + direction * radius,
                    direction,
                    k.toFloat() / segments to stack.toFloat() / stacks,
                )
            }
        }
        val stride = segments + 1
        for (stack in 0 until stacks) {
            for (k in 0 until segments) {
                val a = stack * stride + k
                val b = a + 1
                val c = (stack + 1) * stride + k + 1
                val d = (stack + 1) * stride + k
                builder.addQuad(a, b, c, d)
            }
        }
        return builder.build()
    }
}
