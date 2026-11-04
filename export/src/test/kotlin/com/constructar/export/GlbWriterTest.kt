/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

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
        // The name is `run "A"<newline>level 2`; JSON needs both the quotes and the newline
        // escaped, so the chunk must contain the literal characters \" and \n.
        assertTrue(json.contains("run \\\"A\\\"\\nlevel 2"))
    }

    /**
     * The container, byte by byte.
     *
     * A `.glb` is four little-endian integers, two chunk headers and two chunks whose lengths
     * must be multiples of four - a single wrong field makes the file unreadable for every
     * viewer, and the viewer on the customer's desk is the only test that matters. The earlier
     * cases checked that *something* plausible came out; these pin the arithmetic: the header
     * knows its own size, both chunk types are the ones the spec names, and the buffer the JSON
     * declares is exactly as long as the data that follows it.
     */
    @Test
    fun `the container declares its own size and both chunk types`() {
        val bytes = GlbWriter.write(listOf(GlbWriter.GlbPart("quad", quad())))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("magic", 0x46546C67, buffer.getInt(0))
        assertEquals("version", 2, buffer.getInt(4))
        assertEquals("declared file length", bytes.size, buffer.getInt(8))
        assertEquals("total length must be 4-aligned", 0, bytes.size % 4)

        val jsonChunkLength = buffer.getInt(12)
        assertEquals("JSON chunk type", 0x4E4F534A, buffer.getInt(16))
        assertEquals("JSON chunk must be 4-aligned", 0, jsonChunkLength % 4)

        val binHeader = 20 + jsonChunkLength
        val binChunkLength = buffer.getInt(binHeader)
        assertEquals("BIN chunk type", 0x004E4942, buffer.getInt(binHeader + 4))
        assertEquals("BIN chunk must be 4-aligned", 0, binChunkLength % 4)
        assertEquals("chunks fill the file exactly", bytes.size, binHeader + 8 + binChunkLength)
    }

    @Test
    fun `the json chunk is padded with spaces and the binary buffer matches its declaration`() {
        val bytes = GlbWriter.write(listOf(GlbWriter.GlbPart("quad", quad())))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val jsonChunkLength = buffer.getInt(12)

        val jsonBytes = ByteArray(jsonChunkLength)
        buffer.position(20)
        buffer.get(jsonBytes)
        val json = String(jsonBytes, Charsets.UTF_8)

        // Spaces, not NULs: a NUL after the closing brace breaks viewers that read the chunk as text.
        val padding = json.length - json.trimEnd(' ').length
        assertTrue("padding was $padding bytes, must be 0..3", padding in 0..3)
        assertTrue("JSON chunk must start with a brace", json.trimEnd(' ').startsWith("{"))
        assertTrue("JSON chunk must end with a brace", json.trimEnd(' ').endsWith("}"))

        val declaredBuffer = Regex("\"buffers\":\\[\\{\"byteLength\":(\\d+)\\}").find(json)
            ?.groupValues?.get(1)?.toInt()
        val dataLength = requireNotNull(declaredBuffer) { "JSON declares no buffer length" }
        val binChunkLength = buffer.getInt(20 + jsonChunkLength)
        val binPadding = binChunkLength - dataLength
        assertTrue("binary padding was $binPadding, must be 0..3", binPadding in 0..3)
        // The BIN chunk is padded with zeros, so the declared length is the data length.
        val binStart = 20 + jsonChunkLength + 8
        repeat(binPadding) { offset ->
            assertEquals(
                "binary padding byte at ${binStart + dataLength + offset} must be zero",
                0,
                bytes[binStart + dataLength + offset].toInt(),
            )
        }
    }

    @Test
    fun `every buffer view fits inside the buffer`() {
        val bytes = GlbWriter.write(
            listOf(
                GlbWriter.GlbPart("cable", quad(), 0xFFFF8A00.toInt()),
                GlbWriter.GlbPart("pipe", quad(), 0xFF2E9BFF.toInt()),
            ),
        )
        val json = jsonOf(bytes)
        val bufferLength = requireNotNull(
            Regex("\"buffers\":\\[\\{\"byteLength\":(\\d+)\\}").find(json)
                ?.groupValues?.get(1)?.toInt(),
        ) { "JSON declares no buffer length" }
        val views = Regex("\"byteOffset\":(\\d+),\"byteLength\":(\\d+)").findAll(json).toList()
        assertEquals("two meshes x (position, normal, index)", 6, views.size)
        views.forEach { match ->
            val offset = match.groupValues[1].toInt()
            val length = match.groupValues[2].toInt()
            assertEquals("view offset $offset must be 4-aligned", 0, offset % 4)
            assertTrue(
                "view $offset+$length runs past the ${bufferLength}B buffer",
                offset + length <= bufferLength,
            )
        }
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
