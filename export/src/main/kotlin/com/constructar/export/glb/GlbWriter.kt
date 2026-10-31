/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.glb

import com.constructar.core.mesh.MeshData
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A binary glTF 2.0 (`.glb`) writer for the BIM hand-off.
 *
 * Why hand-rolled instead of a library: the only other offline-available option on Android is
 * a 3 MB JVM glTF library that would pull in unused extensions (Draco needs a proprietary-ish
 * encoder, KTX2 needs basisu). A `.glb` is a 12-byte header plus two chunks; writing the
 * ~"2 meshes, 3 accessors" subset correctly is more dependable than adapting a general
 * exporter, and it keeps the app 100 % offline with no extra binary blobs.
 *
 * Output conventions (matching glTF and therefore every BIM viewer):
 *  * **Y-up**, right-handed, metres;
 *  * one *node* per run, geometry in world space so the node transform stays identity
 *    (Revit/Navisworks then place the model correctly without a transform stack);
 *  * `doubleSided = true` — a scanned surface has no reliable winding after Surface Nets,
 *    and a single-sided model would appear to have holes.
 */
object GlbWriter {

    const val MAGIC = 0x46546C67 // "glTF"
    const val VERSION = 2
    private const val JSON_CHUNK = 0x4E4F534A // "JSON"
    private const val BIN_CHUNK = 0x004E4942 // "BIN\0"

    /** One primitive to export. */
    data class GlbPart(
        val name: String,
        val mesh: MeshData,
        /** ARGB base colour. */
        val colorArgb: Int = 0xFFFF6B00.toInt(),
        val metallic: Float = 0.05f,
        val roughness: Float = 0.6f,
    )

    /** Builds a complete `.glb` byte array. */
    fun write(parts: List<GlbPart>, generator: String = "ConstructAR", sceneName: String = "ConstructAR scan"): ByteArray {
        val binary = ByteArrayOutputStream(1024 * 64)
        val bufferViews = StringBuilder()
        val accessors = StringBuilder()
        val meshes = StringBuilder()
        val materials = StringBuilder()
        val nodes = StringBuilder()

        var viewIndex = 0
        var accessorIndex = 0

        parts.forEachIndexed { partIndex, part ->
            val mesh = part.mesh
            val positionView = viewIndex++
            val positionAccessor = accessorIndex++
            val normalView = viewIndex++
            val normalAccessor = accessorIndex++
            val indexView = viewIndex++
            val indexAccessor = accessorIndex++

            val bounds = mesh.bounds()
            val (minPos, maxPos) = if (bounds == null) {
                FloatArray(3) to FloatArray(3)
            } else {
                floatArrayOf(bounds.min.x, bounds.min.y, bounds.min.z) to
                    floatArrayOf(bounds.max.x, bounds.max.y, bounds.max.z)
            }

            val positionOffset = binary.size()
            val positionBytes = toLittleEndianFloats(mesh.positions)
            binary.write(positionBytes)
            val normalOffset = binary.size()
            binary.write(toLittleEndianFloats(mesh.normals))
            val indexOffset = binary.size()
            binary.write(toLittleEndianInts(mesh.indices))

            appendView(bufferViews, viewIndex - 3, positionOffset, positionBytes.size, TARGET_ARRAY_BUFFER)
            appendView(bufferViews, viewIndex - 2, normalOffset, mesh.normals.size * 4, TARGET_ARRAY_BUFFER)
            appendView(bufferViews, viewIndex - 1, indexOffset, mesh.indices.size * 4, TARGET_ELEMENT_ARRAY_BUFFER)

            appendFloatAccessor(accessors, positionAccessor, positionView, mesh.vertexCount, minPos, maxPos)
            appendFloatAccessor(accessors, normalAccessor, normalView, mesh.vertexCount, null, null)
            appendScalarAccessor(accessors, indexAccessor, indexView, mesh.indices.size)

            val materialIndex = partIndex
            if (partIndex > 0) meshes.append(',')
            meshes.append(
                """{"name":${quote(part.name)},"primitives":[{"attributes":{"POSITION":$positionAccessor,"""
                    + """"NORMAL":$normalAccessor},"indices":$indexAccessor,"material":$materialIndex,"mode":4}]}""",
            )

            if (partIndex > 0) materials.append(',')
            materials.append(
                """{"name":${quote(part.name)},"pbrMetallicRoughness":{"baseColorFactor":"""
                    + colorFactor(part.colorArgb)
                    + ""","metallicFactor":${part.metallic},"roughnessFactor":${part.roughness}},"doubleSided":true}""",
            )

            if (partIndex > 0) nodes.append(',')
            nodes.append("""{"name":${quote(part.name)},"mesh":$partIndex}""")
        }

        val binaryBytes = binary.toByteArray()
        val nodeIndices = parts.indices.joinToString(",") { it.toString() }

        val json = buildString(4096) {
            append("""{"asset":{"version":"2.0","generator":${quote(generator)}},""")
            append(""""scene":0,""")
            append(""""scenes":[{"name":${quote(sceneName)},"nodes":[$nodeIndices]}],""")
            append(""""nodes":[$nodes],""")
            append(""""meshes":[$meshes],""")
            append(""""materials":[$materials],""")
            append(""""accessors":[$accessors],""")
            append(""""bufferViews":[$bufferViews],""")
            append(""""buffers":[{"byteLength":${binaryBytes.size}}]""")
            append("}")
        }

        // JSON must be padded with spaces, BIN with zeros, to a 4-byte boundary.
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        val binaryPad = (4 - binaryBytes.size % 4) % 4
        val totalLength = 12 + 8 + jsonBytes.size + jsonPad + 8 + binaryBytes.size + binaryPad

        val out = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC)
        out.putInt(VERSION)
        out.putInt(totalLength)
        out.putInt(jsonBytes.size + jsonPad)
        out.putInt(JSON_CHUNK)
        out.put(jsonBytes)
        repeat(jsonPad) { out.put(' '.code.toByte()) }
        out.putInt(binaryBytes.size + binaryPad)
        out.putInt(BIN_CHUNK)
        out.put(binaryBytes)
        repeat(binaryPad) { out.put(0) }
        return out.array()
    }

    // ── JSON helpers ────────────────────────────────────────────────────────────────────

    private const val TARGET_ARRAY_BUFFER = 34962
    private const val TARGET_ELEMENT_ARRAY_BUFFER = 34963

    private fun appendView(builder: StringBuilder, index: Int, offset: Int, length: Int, target: Int) {
        if (index > 0) builder.append(',')
        builder.append("""{"buffer":0,"byteOffset":$offset,"byteLength":$length,"target":$target}""")
    }

    private fun appendFloatAccessor(
        builder: StringBuilder,
        index: Int,
        view: Int,
        count: Int,
        minValues: FloatArray?,
        maxValues: FloatArray?,
    ) {
        if (index > 0) builder.append(',')
        builder.append("""{"bufferView":$view,"componentType":5126,"count":$count,"type":"VEC3"""")
        if (minValues != null && maxValues != null) {
            builder.append(""","min":${floatArrayJson(minValues)},"max":${floatArrayJson(maxValues)}""")
        }
        builder.append("}")
    }

    private fun appendScalarAccessor(builder: StringBuilder, index: Int, view: Int, count: Int) {
        if (index > 0) builder.append(',')
        builder.append("""{"bufferView":$view,"componentType":5125,"count":$count,"type":"SCALAR"}""")
    }

    private fun floatArrayJson(values: FloatArray): String =
        values.joinToString(",", "[", "]") { formatFloat(it) }

    private fun colorFactor(argb: Int): String {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return "[${formatFloat(r)},${formatFloat(g)},${formatFloat(b)},1.0]"
    }

    /** Locale-independent, no exponent junk, and never `NaN` (invalid JSON). */
    private fun formatFloat(value: Float): String {
        if (!value.isFinite()) return "0"
        val rounded = kotlin.math.round(value * 1_000_000f) / 1_000_000f
        return if (rounded == rounded.toLong().toFloat()) "${rounded.toLong()}.0" else rounded.toString()
    }

    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "").replace("\t", " ")
        return "\"$escaped\""
    }

    private fun toLittleEndianFloats(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(values)
        return buffer.array()
    }

    private fun toLittleEndianInts(values: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asIntBuffer().put(values)
        return buffer.array()
    }

    /** Merges every part into one mesh — used by the PDF route preview and by `--whole-room`. */
    fun mergedPart(parts: List<GlbPart>, name: String = "merged"): GlbPart? {
        val valid = parts.filter { !it.mesh.isEmpty }
        if (valid.isEmpty()) return null
        var vertices = 0
        var indices = 0
        valid.forEach {
            vertices += it.mesh.vertexCount
            indices += it.mesh.indices.size
        }
        val positions = FloatArray(vertices * 3)
        val normals = FloatArray(vertices * 3)
        val mergedIndices = IntArray(indices)
        var vertexOffset = 0
        var indexOffset = 0
        for (part in valid) {
            System.arraycopy(part.mesh.positions, 0, positions, vertexOffset * 3, part.mesh.positions.size)
            System.arraycopy(part.mesh.normals, 0, normals, vertexOffset * 3, part.mesh.normals.size)
            for (i in part.mesh.indices.indices) {
                mergedIndices[indexOffset + i] = part.mesh.indices[i] + vertexOffset
            }
            vertexOffset += part.mesh.vertexCount
            indexOffset += part.mesh.indices.size
        }
        return GlbPart(name, MeshData(positions, normals, null, mergedIndices), valid.first().colorArgb)
    }

    /** Small helper so callers can compute padding without duplicating the logic. */
    internal fun paddedLength(length: Int): Int = length + (4 - length % 4) % 4

    /** Exposed for tests: does the byte array look like a container we wrote? */
    fun isGlb(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return buffer.getInt(0) == MAGIC && buffer.getInt(4) == VERSION && buffer.getInt(8) == bytes.size
    }
}
