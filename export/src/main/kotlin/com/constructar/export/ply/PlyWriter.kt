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
 * when they cannot open glTF, and a scanned room is as much a point cloud as it is a mesh. Two
 * variants are produced:
 *
 *  * **ASCII** — human-inspectable, diff-able, and the one to send to a client;
 *  * **binary little-endian** — ~4× smaller, for a 300 k-triangle room scan it is the
 *    difference between 40 MB and 10 MB.
 *
 * Both use metres and Y-up (PLY has no up-axis convention, so it is written as a comment —
 * losers of the format, but a comment has saved more imports than it cost).
 */
object PlyWriter {

    data class PlyPart(val name: String, val mesh: MeshData)

    fun writeAscii(parts: List<PlyPart>, comment: String = "ConstructAR scan, metres, Y-up"): String {
        val totalVertices = parts.sumOf { it.mesh.vertexCount }
        val totalFaces = parts.sumOf { it.mesh.triangleCount }
        val builder = StringBuilder(1 shl 20)
        builder.append("ply\n")
        builder.append("format ascii 1.0\n")
        builder.append("comment $comment\n")
        parts.forEach { builder.append("comment mesh ${it.name}\n") }
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
    fun writeBinaryLittleEndian(parts: List<PlyPart>, comment: String = "ConstructAR scan, metres, Y-up"): ByteArray {
        val header = buildString {
            append("ply\n")
            append("format binary_little_endian 1.0\n")
            append("comment $comment\n")
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

    private fun format(value: Float): String =
        if (value.isFinite()) String.format(Locale.ROOT, "%.5f", value) else "0.00000"
}
