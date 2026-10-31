package com.constructar.export

import com.constructar.core.mesh.MeshData
import com.constructar.export.glb.GlbWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A `.glb` that no viewer can open is worse than no export at all, so the container and the
 * JSON are both validated structurally.
 */
class GlbWriterTest {

    /** A single quad in the z = 0 plane. */
    private fun quad(): MeshData = MeshData(
        positions = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            1f, 1f, 0f,
            0f, 1f, 0f,
        ),
        normals = floatArrayOf(
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f,
        ),
        uvs = null,
        indices = intArrayOf(0, 1, 2, 0, 2, 3),
    )

    @Test
    fun `container header is a valid glb`() {
        val bytes = GlbWriter.write(listOf(GlbWriter.GlbPart("quad", quad())))
        assertTrue(GlbWriter.isGlb(bytes))
        assertEquals(0, bytes.size % 4)
    }

    @Test
    fun `json chunk declares one mesh with position normal and index accessors`() {
        val bytes = GlbWriter.write(listOf(GlbWriter.GlbPart("quad", quad(), colorArgb = 0xFFFF0000.toInt())))
        val json = jsonOf(bytes)
        assertTrue(json.contains("\"asset\""))
        assertTrue(json.contains("\"version\":\"2.0\""))
        assertTrue(json.contains("\"meshes\""))
        assertTrue(json.contains("\"POSITION\":0"))
        assertTrue(json.contains("\"NORMAL\":1"))
        assertTrue(json.contains("\"mode\":4"))
        assertTrue(json.contains("\"doubleSided\":true"))
        // 4 vertices, 6 indices, min/max mandatory on POSITION accessors.
        assertTrue(json.contains("\"count\":4"))
        assertTrue(json.contains("\"count\":6"))
        assertTrue(json.contains("\"min\":[0.0,0.0,0.0]"))
        assertTrue(json.contains("\"max\":[1.0,1.0,0.0]"))
        // Red base colour, alpha 1.
        assertTrue(json.contains("\"baseColorFactor\":[1.0,0.0,0.0,1.0]"))
    }

    @Test
    fun `binary chunk holds exactly the mesh data`() {
        val parts = listOf(GlbWriter.GlbPart("quad", quad()))
        val bytes = GlbWriter.write(parts)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = buffer.getInt(12)
        val binLength = buffer.getInt(20 + jsonLength)
        // 4 vertices × (3 position + 3 normal) floats + 6 uint32 indices.
        assertEquals(4 * 6 * 4 + 6 * 4, binLength)
    }

    @Test
    fun `two meshes produce two nodes and two materials`() {
        val bytes = GlbWriter.write(
            listOf(
                GlbWriter.GlbPart("cable", quad(), 0xFFFF8A00.toInt()),
                GlbWriter.GlbPart("pipe", quad(), 0xFF2E9BFF.toInt()),
            ),
        )
        val json = jsonOf(bytes)
        assertTrue(json.contains("\"nodes\":[{\"name\":\"cable\",\"mesh\":0},{\"name\":\"pipe\",\"mesh\":1}]"))
        assertEquals(2, Regex("\"pbrMetallicRoughness\"").findAll(json).count())
    }

    @Test
    fun `names with quotes and newlines do not break the json`() {
        val bytes = GlbWriter.write(listOf(GlbWriter.GlbPart("run \"A\"\nlevel 2", quad())))
        val json = jsonOf(bytes)
        assertTrue(json.contains("run \\\"A\\\" level 2"))
    }

    private fun jsonOf(bytes: ByteArray): String {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = buffer.getInt(12)
        val jsonBytes = ByteArray(jsonLength)
        buffer.position(20)
        buffer.get(jsonBytes)
        return String(jsonBytes, Charsets.UTF_8)
    }
}
