/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.ply

import com.constructar.core.mesh.MeshData
import java.util.Locale

/**
 * PLY (Stanford polygon format) writer — the lowest-common-denominator hand-off.
 *
 * Cloud-point-processing tools, MeshLab, CloudCompare and most survey pipelines read PLY even
 * when they cannot open glTF, and a scanned room is as much a point cloud as it is a mesh.
 *
 * There are two layouts of the same geometry, and [write] is the one the export calls:
 *
 *  * **ASCII** — human-inspectable and diff-able: a vertex is six numbers at five decimals, about
 *    60 bytes, and a face is a line of about 22;
 *  * **binary little-endian** — the same vertex is 24 bytes and the same face 13, so the file is
 *    a little over half the size and grows that way on every vertex.
 *
 * Which one a bundle gets is decided by how much geometry is in it, not by taste: a flat of
 * carried runs is a megabyte or two of text that a surveyor can open, read the header of and
 * diff against last week's export, and a bundle big enough to stop being readable is the case
 * where the bytes are the only thing that matters ([ASCII_LIMIT_TRIANGLES]). The writer used to
 * describe both variants as "produced" while `ExportService` only ever called [writeAscii], which
 * also left the size claim around them unmeasured (finding 167).
 *
 * Both use metres and Y-up (PLY has no up-axis convention, so it is written as a comment —
 * losers of the format, but a comment has saved more imports than it cost).
 *
 * The default comment says **export**, not "scan": what this writer receives is whatever the caller
 * hands it, and the export hands it the measured runs. The header used to call every file a room
 * scan, so a wholesaler opening a 40 kB PLY of conduit runs read that the room itself was in there
 * (finding 87). The TSDF scan mesh never leaves the AR session it was captured in, so a file that
 * really is a scan is not something this app produces yet.
 */
object PlyWriter {

    /** What the file is: an export, in metres, Y-up. See the class comment (finding 87). */
    const val DEFAULT_COMMENT: String = "ConstructAR export, metres, Y-up"

    /**
     * Past this many triangles the text form stops being worth having.
     *
     * The number is a reading budget, not a measurement limit: 100 000 triangles is roughly three
     * megabytes of text, which is about as much as anyone opens to look at, and past it the file
     * is going to a tool - and that tool reads binary just as well for half the bytes.
     */
    const val ASCII_LIMIT_TRIANGLES: Int = 100_000

    /**
     * The PLY artifact the export writes, in the layout that suits its size.
     *
     * See [ASCII_LIMIT_TRIANGLES] for where the line is drawn; both layouts carry exactly the same
     * geometry, and the file says which one it is in its second line.
     */
    fun write(parts: List<PlyPart>, comment: String = DEFAULT_COMMENT): ByteArray =
        if (parts.sumOf { it.mesh.triangleCount } > ASCII_LIMIT_TRIANGLES) {
            writeBinaryLittleEndian(parts, comment)
        } else {
            writeAscii(parts, comment).toByteArray(Charsets.US_ASCII)
        }

    data class PlyPart(val name: String, val mesh: MeshData)

    fun writeAscii(parts: List<PlyPart>, comment: String = DEFAULT_COMMENT): String {
        val totalVertices = parts.sumOf { it.mesh.vertexCount }
        val totalFaces = parts.sumOf { it.mesh.triangleCount }
        val builder = StringBuilder(1 shl 20)
        builder.append("ply\n")
        builder.append("format ascii 1.0\n")
        builder.append("comment ${oneLine(comment)}\n")
        parts.forEach { builder.append("comment mesh ${oneLine(it.name)}\n") }
        builder.append("element vertex $totalVertices\n")
        builder.append("property float x\n")
        builder.append("property float y\n")
        builder.append("property float z\n")
        builder.append("property float nx\n")
        builder.append("property float ny\n")
        builder.append("property float nz\n")
        builder.append("element face $totalFaces\n")
        builder.append("property list uchar int vertex_indices\n")
        builder.append("end_header\n")

        for (part in parts) {
            val mesh = part.mesh
            for (v in 0 until mesh.vertexCount) {
                builder.append(format(mesh.positions[v * 3])).append(' ')
                builder.append(format(mesh.positions[v * 3 + 1])).append(' ')
                builder.append(format(mesh.positions[v * 3 + 2])).append(' ')
                builder.append(format(mesh.normals[v * 3])).append(' ')
                builder.append(format(mesh.normals[v * 3 + 1])).append(' ')
                builder.append(format(mesh.normals[v * 3 + 2])).append('\n')
            }
        }
        var vertexOffset = 0
        for (part in parts) {
            val mesh = part.mesh
            var i = 0
            while (i + 2 < mesh.indices.size) {
                builder.append("3 ")
                    .append(mesh.indices[i] + vertexOffset).append(' ')
                    .append(mesh.indices[i + 1] + vertexOffset).append(' ')
                    .append(mesh.indices[i + 2] + vertexOffset).append('\n')
                i += 3
            }
            vertexOffset += mesh.vertexCount
        }
        return builder.toString()
    }

    /** Binary little-endian PLY; the payload is `float32 × 6` per vertex, `uint8 + int32 × 3` per face. */
    fun writeBinaryLittleEndian(parts: List<PlyPart>, comment: String = DEFAULT_COMMENT): ByteArray {
        val header = buildString {
            append("ply\n")
            append("format binary_little_endian 1.0\n")
            append("comment ${oneLine(comment)}\n")
            append("element vertex ${parts.sumOf { it.mesh.vertexCount }}\n")
            append("property float x\nproperty float y\nproperty float z\n")
            append("property float nx\nproperty float ny\nproperty float nz\n")
            append("element face ${parts.sumOf { it.mesh.triangleCount }}\n")
            append("property list uchar int vertex_indices\n")
            append("end_header\n")
        }.toByteArray(Charsets.US_ASCII)

        val vertexBytes = parts.sumOf { it.mesh.vertexCount } * 6 * 4
        val faceBytes = parts.sumOf { it.mesh.triangleCount } * (1 + 3 * 4)
        val out = java.nio.ByteBuffer.allocate(header.size + vertexBytes + faceBytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put(header)
        for (part in parts) {
            val mesh = part.mesh
            for (v in 0 until mesh.vertexCount) {
                out.putFloat(mesh.positions[v * 3])
                out.putFloat(mesh.positions[v * 3 + 1])
                out.putFloat(mesh.positions[v * 3 + 2])
                out.putFloat(mesh.normals[v * 3])
                out.putFloat(mesh.normals[v * 3 + 1])
                out.putFloat(mesh.normals[v * 3 + 2])
            }
        }
        var vertexOffset = 0
        for (part in parts) {
            val mesh = part.mesh
            var i = 0
            while (i + 2 < mesh.indices.size) {
                out.put(3.toByte())
                out.putInt(mesh.indices[i] + vertexOffset)
                out.putInt(mesh.indices[i + 1] + vertexOffset)
                out.putInt(mesh.indices[i + 2] + vertexOffset)
                i += 3
            }
            vertexOffset += mesh.vertexCount
        }
        return out.array()
    }

    /**
     * A PLY comment ends at the first newline, and a run label is user text.
     *
     * A label that carried a line break - pasted from a note, typed on a keyboard that inserted
     * one - would end the `comment` and let the rest of the label be read as a header directive:
     * `element vertex 999999` in the middle of a room name turns a valid file into one that
     * crashes a viewer. Comments are therefore flattened, never escaped.
     */
    private fun oneLine(value: String): String =
        value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()

    private fun format(value: Float): String =
        if (value.isFinite()) String.format(Locale.ROOT, "%.5f", value) else "0.00000"
}
