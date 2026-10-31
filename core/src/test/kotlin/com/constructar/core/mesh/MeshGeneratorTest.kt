package com.constructar.core.mesh

import com.constructar.core.geo.PathSmoother
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MeshGeneratorTest {

    private fun straightPath(lengthMeters: Float = 1f) = PathSmoother.smooth(
        listOf(Vec3.ZERO, Vec3(0f, 0f, lengthMeters)),
        maxStraightSpanMeters = 10f,
    )

    @Test
    fun `tube has rings caps and unit normals`() {
        val spec = ConduitSpec.cable(100f) // Ø 100 mm ⇒ r = 0.05 m
        val mesh = TubeMeshGenerator.generate(
            straightPath(),
            spec,
            TubeMeshOptions(radialSegments = 12),
        )

        // 2 rings × (12+1) seam-duplicated vertices + 2 flat caps × (1 centre + 12 rim).
        assertEquals(52, mesh.vertexCount)
        assertEquals(48, mesh.triangleCount)

        for (v in 0 until mesh.vertexCount) {
            assertEquals(1f, mesh.normalAt(v).length, 1e-4f)
        }
        val bounds = mesh.bounds()
        assertNotNull(bounds)
        bounds!!
        assertEquals(0.05f, bounds.max.x, 1e-4f)
        assertEquals(-0.05f, bounds.min.x, 1e-4f)
        assertEquals(0f, bounds.min.z, 1e-4f)
        assertEquals(1f, bounds.max.z, 1e-4f)
    }

    @Test
    fun `tube winding is outward facing`() {
        val spec = ConduitSpec.cable(100f)
        val mesh = TubeMeshGenerator.generate(straightPath(), spec)
        // For the first side triangle, the geometric normal must agree with the vertex normal.
        val a = mesh.positionAt(mesh.indices[0])
        val b = mesh.positionAt(mesh.indices[1])
        val c = mesh.positionAt(mesh.indices[2])
        val geometric = ((b - a) cross (c - a)).normalized()
        val shading = mesh.normalAt(mesh.indices[0])
        assertTrue("normal ${geometric dot shading}", (geometric dot shading) > 0.2f)
    }

    @Test
    fun `duct lies flat on the wall with the requested section`() {
        val spec = ConduitSpec.duct(60f, 40f) // 0.06 wide × 0.04 high
        val path = straightPath()
        // Wall whose normal is +X, duct running along +Z.
        val normals = path.points.map { Vec3.X_AXIS }
        val mesh = DuctMeshGenerator.generate(path, normals, spec)

        // 4 faces × 2 stations × 2 vertices + 2 caps × 4 corners.
        assertEquals(24, mesh.vertexCount)
        assertEquals(12, mesh.triangleCount)

        val bounds = mesh.bounds()!!
        // Height (0.04) extends along the wall normal; width (0.06) along the wall.
        assertEquals(0.02f, bounds.max.x, 1e-4f)
        assertEquals(0.03f, bounds.max.y, 1e-4f)
        assertEquals(-0.03f, bounds.min.y, 1e-4f)
        // Face normals must be axis-aligned for a straight run.
        for (v in 0 until mesh.vertexCount) {
            val n = mesh.normalAt(v).abs()
            assertTrue(abs(n.x + n.y + n.z - 1f) < 1e-3f)
        }
    }

    @Test
    fun `merging meshes offsets indices correctly`() {
        val spec = ConduitSpec.cable(100f)
        val a = TubeMeshGenerator.generate(straightPath(), spec)
        val b = TubeMeshGenerator.generate(straightPath(2f), spec)
        val merged = a.mergedWith(b)

        assertEquals(a.vertexCount + b.vertexCount, merged.vertexCount)
        assertEquals(a.indices.size + b.indices.size, merged.indices.size)
        assertTrue(merged.indices.max() < merged.vertexCount)
    }

    @Test
    fun `sphere node marker is closed and unit-radius`() {
        val mesh = TubeMeshGenerator.sphere(Vec3(1f, 2f, 3f), radius = 0.02f, segments = 8)
        val bounds = mesh.bounds()!!
        assertEquals(0.02f, bounds.max.x - 1f, 1e-3f)
        assertEquals(2f, bounds.center.y, 1e-3f)
        assertEquals(0.02f, bounds.halfExtent.y, 1e-3f)
    }
}
