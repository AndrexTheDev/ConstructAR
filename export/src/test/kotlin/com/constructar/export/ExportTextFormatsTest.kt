/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export

import com.constructar.core.bom.MaterialList
import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.mesh.MeshData
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.HitSource
import com.constructar.core.model.SurfaceKind
import com.constructar.core.math.Vec3
import com.constructar.export.csv.MaterialCsvWriter
import com.constructar.export.ply.PlyWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** CSV and PLY: the two plain-text exports a site laptop actually opens. */
class ExportTextFormatsTest {

    private fun triangleMesh(): MeshData = MeshData(
        positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
        normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
        uvs = null,
        indices = intArrayOf(0, 1, 2),
    )

    private fun sampleProject(): ProjectRecord {
        val spec = ConduitSpec.cable(16f, "Cable Ø16")
        val route = ConduitRoute(
            id = "r1",
            label = "Kitchen – worktop",
            spec = spec,
            nodes = listOf(
                RouteNodeRecord("n1", Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON),
                RouteNodeRecord("n2", Vec3(3.5f, 0f, 0f), Vec3(0f, 0f, 1f), SurfaceKind.WALL, HitSource.PLANE_POLYGON),
            ),
            createdAtEpochMs = 0L,
        )
        return ProjectRecord.empty("p1", "Keller; Raum 2", 0L).copy(routes = listOf(route))
    }

    @Test
    fun `csv quotes the separator and keeps dots as decimal marks`() {
        val project = sampleProject()
        val list: MaterialList = MaterialListBuilder.build(project, nowMs = 0L)
        val csv = MaterialCsvWriter.write(list, MaterialCsvWriter.Options(includeBom = false), "2026-03-14 10:15")

        // The project name contains the separator, so it must be quoted.
        assertTrue(csv.contains("\"Keller; Raum 2\""))
        assertTrue(csv.contains("3.50"))
        assertTrue(csv.contains("Cable"))
        assertTrue(csv.lineSequence().first().startsWith("Project;"))
        assertTrue(csv.contains("\r\n"))
    }

    @Test
    fun `csv escaping doubles internal quotes`() {
        assertEquals("\"a\"\"b\"", MaterialCsvWriter.escape("a\"b"))
        assertEquals("plain", MaterialCsvWriter.escape("plain"))
    }

    @Test
    fun `ply ascii header and body agree on counts`() {
        val ply = PlyWriter.writeAscii(listOf(PlyWriter.PlyPart("tri", triangleMesh())))
        val lines = ply.lines()
        assertTrue(lines[0] == "ply")
        assertTrue(lines.any { it == "element vertex 3" })
        assertTrue(lines.any { it == "element face 1" })
        val headerEnd = lines.indexOf("end_header")
        val body = lines.subList(headerEnd + 1, lines.size).filter { it.isNotBlank() }
        assertEquals(4, body.size) // 3 vertices + 1 face
        assertTrue(body.last().startsWith("3 ")) // a triangle
    }

    @Test
    fun `ply binary length matches the declared header`() {
        val bytes = PlyWriter.writeBinaryLittleEndian(listOf(PlyWriter.PlyPart("tri", triangleMesh())))
        val headerEnd = indexOf(bytes, "end_header\n".toByteArray()) + "end_header\n".length
        val expected = headerEnd + 3 * 6 * 4 + (1 + 3 * 4)
        assertEquals(expected, bytes.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // First vertex is the origin; second is (1, 0, 0).
        assertEquals(0f, buffer.getFloat(headerEnd), 0f)
        assertEquals(1f, buffer.getFloat(headerEnd + 24), 0f)
    }

    @Test
    fun `file stems are filesystem safe`() {
        assertEquals("Keller-Raum-2", ExportService.slug("Keller / Raum: 2"))
        assertEquals("project", ExportService.slug("   "))
        assertTrue(ExportService.slug("A".repeat(200)).length <= 40)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
