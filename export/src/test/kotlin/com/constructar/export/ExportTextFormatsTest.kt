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
import org.junit.Assert.assertFalse
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
    fun `a cell a spreadsheet would evaluate is prefixed as text`() {
        // A restored backup can carry any name: the cell must be data, not a formula.
        assertEquals("'=1+1", MaterialCsvWriter.escape("=1+1"))
        assertTrue(MaterialCsvWriter.escape("+1+1").startsWith("'"))
        assertTrue(MaterialCsvWriter.escape("@SUM(A1)").startsWith("'"))
        assertTrue(MaterialCsvWriter.escape("\tk").startsWith("'"))
        // The guard survives the RFC 4180 quoting when the value also needs quotes.
        assertEquals("\"'=A;B\"", MaterialCsvWriter.escape("=A;B"))
    }

    @Test
    fun `a minus sign is guarded only when it is not a number`() {
        // -5.00 is a value that must survive untouched; -2+3 is a formula.
        assertEquals("-5.00", MaterialCsvWriter.escape("-5.00"))
        assertTrue(MaterialCsvWriter.escape("-2+3").startsWith("'"))
        assertEquals("", MaterialCsvWriter.escape(""))
    }

    @Test
    fun `a hostile project name cannot become a formula in the list`() {
        val project = sampleProject().copy(name = "=cmd|' /C calc'!A0")
        val csv = MaterialCsvWriter.write(
            MaterialListBuilder.build(project, nowMs = 0L),
            MaterialCsvWriter.Options(includeBom = false),
        )
        assertTrue(csv.contains("'=cmd"))
        // Nothing in the file may begin a line with an evaluating character.
        csv.lineSequence().forEach { line ->
            assertFalse("line starts a formula: $line", line.startsWith("=") || line.startsWith("@"))
        }
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
        assertEquals("project", ExportService.slug("///"))
        assertEquals("Bad-Keller", ExportService.slug("Bad/Keller"))
        // Nothing a path cannot hold survives, whatever an operator pasted in.
        for (hostile in listOf("a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\u0000b", "..\\..")) {
            val stem = ExportService.slug(hostile)
            assertTrue("$hostile -> $stem", stem.none { it in "/\\:*?\"<>|" })
            assertTrue("$hostile -> $stem", stem.isNotBlank())
        }
    }

    @Test
    fun `a room keeps its own name in the file stem`() {
        // The umlaut used to be deleted, not transliterated: "Küche" became "Kche", which is not
        // a short name but a different one. Every locale of this app types letters like these.
        assertEquals("Küche", ExportService.slug("Küche"))
        assertEquals("Chantier-Château", ExportService.slug("Chantier Château"))
        assertEquals("Baño-2", ExportService.slug("Baño (2)"))
        assertEquals("Łazienka", ExportService.slug("Łazienka"))
        assertEquals("Cucina-Öl-1", ExportService.slug("Cucina Öl 1"))
        assertEquals("Café-1-OG", ExportService.slug("Café 1. OG"))
        // A surrogate pair is never cut in half.
        val emoji = ExportService.slug("A" + "🛠".repeat(40))
        assertTrue(emoji.none { it.isHighSurrogate() && emoji.indexOf(it) == emoji.length - 1 })
    }

    @Test
    fun `a ply run label is flattened into the header, not spliced into it`() {
        val nasty = "tri\nelement vertex 999999\nend_header"
        val ply = PlyWriter.writeAscii(listOf(PlyWriter.PlyPart(nasty, triangleMesh())))
        val lines = ply.lines()
        val diagnostic = lines.joinToString(" | ")
        assertEquals(diagnostic, 1, lines.count { it == "element vertex 3" })
        assertEquals(diagnostic, 1, lines.count { it == "element face 1" })
        assertEquals(diagnostic, 1, lines.count { it == "end_header" })
        assertEquals(diagnostic, 1, lines.count { it.startsWith("comment mesh ") })
        assertTrue(diagnostic, lines.none { it == "element vertex 999999" })
    }

    @Test
    fun `a ply comment is flattened into one header line`() {
        val bytes = PlyWriter.writeBinaryLittleEndian(
            parts = listOf(PlyWriter.PlyPart("tri", triangleMesh())),
            comment = "scan\nelement vertex 999999",
        )
        val marker = "end_header\n".toByteArray()
        val headerLength = indexOf(bytes, marker) + marker.size
        assertTrue("end_header not found in ${String(bytes, Charsets.US_ASCII).take(200)}", headerLength >= marker.size)
        val header = String(bytes, 0, headerLength, Charsets.US_ASCII).lines()
        val diagnostic = header.joinToString(" | ")
        assertEquals(diagnostic, 1, header.count { it.startsWith("comment ") })
        assertTrue(diagnostic, header.none { it == "element vertex 999999" })
        assertEquals(diagnostic, 1, header.count { it == "element vertex 3" })
        assertEquals(diagnostic, 1, header.count { it == "end_header" })
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
