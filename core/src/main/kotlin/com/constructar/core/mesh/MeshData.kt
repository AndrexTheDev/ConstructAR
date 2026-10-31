package com.constructar.core.mesh

import com.constructar.core.math.Aabb
import com.constructar.core.math.Mat4
import com.constructar.core.math.Vec3

/**
 * Engine-agnostic triangle mesh: world-space positions, per-vertex normals, optional UVs
 * and 32-bit indices.
 *
 * One representation feeds three consumers, which is why it lives in :core and not in the
 * renderer:
 *  1. `:ar-engine` uploads it into a Filament `VertexBuffer`/`IndexBuffer` (MeshNode).
 *  2. `:export` writes it into `.glb` (glTF 2.0) and `.ply`.
 *  3. Unit tests assert geometric invariants (normals unit length, indices in range).
 *
 * Positions are metres in ARCore world space. The mesh is indexed and un-welded along UV
 * seams, which is what a GPU wants and what glTF expects.
 */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray?,
    val indices: IntArray,
) {
    init {
        require(positions.size % 3 == 0) { "positions must be a multiple of 3" }
        require(normals.size == positions.size) { "normals must match positions" }
        require(uvs == null || uvs.size / 2 == vertexCount) { "uvs must be 2 per vertex" }
        require(indices.size % 3 == 0) { "indices must be triangles" }
    }

    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
    val isEmpty: Boolean get() = vertexCount == 0 || indices.isEmpty()

    fun positionAt(vertex: Int): Vec3 =
        Vec3(positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2])

    fun normalAt(vertex: Int): Vec3 =
        Vec3(normals[vertex * 3], normals[vertex * 3 + 1], normals[vertex * 3 + 2])

    fun uvAt(vertex: Int): Pair<Float, Float>? =
        uvs?.let { it[vertex * 2] to it[vertex * 2 + 1] }

    fun bounds(): Aabb? = Aabb.ofPoints(positions)

    /** Applies a rigid transform — used to move a scan mesh or to bake a node transform. */
    fun transformed(matrix: Mat4): MeshData {
        val outPositions = FloatArray(positions.size)
        val outNormals = FloatArray(normals.size)
        for (v in 0 until vertexCount) {
            val p = matrix.transformPoint(positionAt(v))
            val n = matrix.transformDirection(normalAt(v)).normalizedOrZero()
            outPositions[v * 3] = p.x; outPositions[v * 3 + 1] = p.y; outPositions[v * 3 + 2] = p.z
            outNormals[v * 3] = n.x; outNormals[v * 3 + 1] = n.y; outNormals[v * 3 + 2] = n.z
        }
        return MeshData(outPositions, outNormals, uvs?.copyOf(), indices.copyOf())
    }

    /** Concatenates meshes into one buffer (used for "export whole room" and multi-run overlays). */
    fun mergedWith(other: MeshData): MeshData {
        if (isEmpty) return other
        if (other.isEmpty) return this
        val builder = MeshBuilder(vertexCount + other.vertexCount)
        appendTo(builder)
        other.appendTo(builder)
        return builder.build()
    }

    internal fun appendTo(builder: MeshBuilder) {
        val base = builder.vertexCount
        for (v in 0 until vertexCount) {
            builder.addVertex(positionAt(v), normalAt(v), uvAt(v))
        }
        var i = 0
        while (i + 2 < indices.size) {
            builder.addTriangle(base + indices[i], base + indices[i + 1], base + indices[i + 2])
            i += 3
        }
    }

    companion object {
        val EMPTY = MeshData(FloatArray(0), FloatArray(0), null, IntArray(0))

        fun fromTriangles(positions: FloatArray, normals: FloatArray, indices: IntArray) =
            MeshData(positions, normals, null, indices)
    }
}

/**
 * Growable mesh accumulator.
 *
 * Capacity grows geometrically (never per-vertex) so building a 10 k-vertex tube costs a
 * handful of allocations rather than thousands — this runs on the routing thread while
 * the operator is still dragging out points.
 */
class MeshBuilder(initialVertexCapacity: Int = 256) {
    private var positions = FloatArray(initialVertexCapacity.coerceAtLeast(4) * 3)
    private var normals = FloatArray(initialVertexCapacity.coerceAtLeast(4) * 3)
    private var uvs = FloatArray(initialVertexCapacity.coerceAtLeast(4) * 2)
    private var indices = IntArray((initialVertexCapacity.coerceAtLeast(4)) * 6)
    private var vertices = 0
    private var indexCount = 0
    private var hasUvs = false

    /** Vertices added so far. */
    val vertexCount: Int get() = vertices

    fun addVertex(position: Vec3, normal: Vec3, uv: Pair<Float, Float>? = null): Int {
        if (vertices * 3 + 3 > positions.size) growVertices()
        positions[vertices * 3] = position.x
        positions[vertices * 3 + 1] = position.y
        positions[vertices * 3 + 2] = position.z
        normals[vertices * 3] = normal.x
        normals[vertices * 3 + 1] = normal.y
        normals[vertices * 3 + 2] = normal.z
        if (uv != null) {
            hasUvs = true
            uvs[vertices * 2] = uv.first
            uvs[vertices * 2 + 1] = uv.second
        }
        return vertices++
    }

    fun addTriangle(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > indices.size) indices = indices.copyOf(maxOf(6, indices.size * 2))
        indices[indexCount++] = a
        indices[indexCount++] = b
        indices[indexCount++] = c
    }

    /** Quad as two triangles, `(a,b,c)` and `(a,c,d)` — counter-clockwise seen from outside. */
    fun addQuad(a: Int, b: Int, c: Int, d: Int) {
        addTriangle(a, b, c)
        addTriangle(a, c, d)
    }

    fun build(): MeshData {
        if (vertices == 0 || indexCount == 0) return MeshData.EMPTY
        return MeshData(
            positions = positions.copyOf(vertices * 3),
            normals = normals.copyOf(vertices * 3),
            uvs = if (hasUvs) uvs.copyOf(vertices * 2) else null,
            indices = indices.copyOf(indexCount),
        )
    }

    private fun growVertices() {
        val capacity = positions.size / 3
        val newCapacity = maxOf(8, capacity * 2)
        positions = positions.copyOf(newCapacity * 3)
        normals = normals.copyOf(newCapacity * 3)
        uvs = uvs.copyOf(newCapacity * 2)
    }
}
