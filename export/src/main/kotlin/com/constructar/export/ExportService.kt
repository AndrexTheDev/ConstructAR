/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export

import android.graphics.Bitmap
import com.constructar.core.bom.MaterialList
import com.constructar.core.math.Mat4
import com.constructar.core.mesh.MeshData
import com.constructar.core.mesh.RouteGeometry
import com.constructar.core.model.ProjectRecord
import com.constructar.export.csv.MaterialCsvWriter
import com.constructar.export.glb.GlbWriter
import com.constructar.export.ply.PlyWriter
import com.constructar.export.pdf.PdfLabels
import com.constructar.export.pdf.PdfPhoto
import com.constructar.export.pdf.PdfSection
import com.constructar.export.pdf.ProtocolPdfWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Produces the complete local export bundle.
 *
 * The service is **storage-agnostic on purpose**: it hands back [ExportArtifact]s, each of
 * which writes itself into a caller-supplied [OutputStream]. The app layer decides whether
 * that stream goes to the Storage Access Framework (a folder the operator picked), to
 * app-private storage, or to a `ContentProvider` for sharing. This keeps the export logic
 * unit-testable, keeps `:export` free of permissions, and — most importantly — keeps the
 * "100 % offline" promise honest: nothing here can reach a network, because it never sees a
 * network API.
 */
class ExportService(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pdfWriter: ProtocolPdfWriter = ProtocolPdfWriter(),
    private val glbWriter: () -> GlbWriter = { GlbWriter },
) {

    /** One file to be written. */
    data class ExportArtifact(
        val fileName: String,
        val mimeType: String,
        val byteSizeEstimate: Long,
        val write: (OutputStream) -> Unit,
    )

    data class Request(
        val project: ProjectRecord,
        val materialList: MaterialList,
        /** Geometry of every route, already generated (world space, metres). */
        val routeMeshes: List<Pair<String, MeshData>> = emptyList(),
        /** The TSDF room scan, if the operator scanned one. */
        val scanMesh: MeshData? = null,
        val screenshot: Bitmap? = null,
        val operatorName: String = "",
        val appVersion: String = "1.0",
        val includePdf: Boolean = true,
        val includeCsv: Boolean = true,
        val includeGlb: Boolean = true,
        val includePly: Boolean = true,
        /** Include the raw scan mesh (not just the runs) in the 3D exports. */
        val includeScanGeometry: Boolean = true,
        /**
         * Text to stamp across every page of the protocol, already localised by the caller.
         * Blank for a licensed build - see [ProtocolPdfWriter.Content.watermark].
         */
        val watermark: String = "",
        /** Customer signature for the handover page; `null` for an unsigned protocol. */
        val signature: Bitmap? = null,
        val signatureTitle: String = "",
        val signatureCaption: String = "",
        /** All text on the protocol, localised by the caller. See [PdfLabels]. */
        val labels: PdfLabels = PdfLabels(),
        /** The product badge drawn on the first page. */
        val logo: Bitmap? = null,
        /** Site file sections (customer, time, diary, defects, costs), localised by the caller. */
        val sections: List<PdfSection> = emptyList(),
        /** Documentation photos for the protocol appendix, with localised captions. */
        val photos: List<PdfPhoto> = emptyList(),
        /** Heading of the photo appendix. */
        val photosTitle: String = "",
    )

    /** Human/machine-readable file stem, e.g. `ConstructAR_Bad-Keller_20260314-1015`. */
    fun fileStem(project: ProjectRecord, atMillis: Long = clock()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(atMillis))
        return "ConstructAR_${slug(project.name)}_$stamp"
    }

    /** Builds every requested artifact. Never throws for a missing mesh: it just omits it. */
    fun build(request: Request): List<ExportArtifact> {
        val stamp = clock()
        val stem = fileStem(request.project, stamp)
        val timestampLabel = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
            .format(Date(stamp))
        val artifacts = ArrayList<ExportArtifact>(4)

        if (request.includePdf) {
            artifacts += ExportArtifact(
                fileName = "$stem.pdf",
                mimeType = "application/pdf",
                byteSizeEstimate = 350_000L,
                write = { output ->
                    pdfWriter.write(
                        ProtocolPdfWriter.Content(
                            project = request.project,
                            materialList = request.materialList,
                            screenshot = request.screenshot,
                            generatedAtLabel = timestampLabel,
                            appVersion = request.appVersion,
                            operatorName = request.operatorName,
                            watermark = request.watermark,
                            signature = request.signature,
                            signatureTitle = request.signatureTitle,
                            signatureCaption = request.signatureCaption,
                            labels = request.labels,
                            sections = request.sections,
                            photos = request.photos,
                            photosTitle = request.photosTitle,
                            logo = request.logo,
                        ),
                        output,
                    )
                },
            )
        }

        if (request.includeCsv) {
            val runDetails = request.project.routes.map { route ->
                Triple(route.label, route.spec.sizeLabel, route.polylineLengthMeters)
            }
            val csv = MaterialCsvWriter.write(
                materialList = request.materialList,
                options = MaterialCsvWriter.Options(includeRunDetail = true),
                generatedAtLabel = timestampLabel,
                runDetails = runDetails,
            )
            artifacts += ExportArtifact(
                fileName = "$stem.csv",
                mimeType = "text/csv",
                byteSizeEstimate = csv.toByteArray(Charsets.UTF_8).size.toLong(),
                write = { output -> output.write(csv.toByteArray(Charsets.UTF_8)) },
            )
        }

        val parts = buildList {
            request.routeMeshes.forEach { (label, mesh) ->
                if (!mesh.isEmpty) {
                    add(GlbWriter.GlbPart(label, mesh, colourFor(label, request.project)))
                }
            }
            if (request.includeScanGeometry) {
                request.scanMesh?.takeIf { !it.isEmpty }?.let {
                    add(GlbWriter.GlbPart("Room scan", it, 0xFF8A8F98.toInt()))
                }
            }
        }

        if (request.includeGlb && parts.isNotEmpty()) {
            val bytes = glbWriter().write(parts, generator = "ConstructAR ${request.appVersion}", sceneName = request.project.name)
            artifacts += ExportArtifact(
                fileName = "$stem.glb",
                mimeType = "model/gltf-binary",
                byteSizeEstimate = bytes.size.toLong(),
                write = { output -> output.write(bytes) },
            )
        }

        if (request.includePly && parts.isNotEmpty()) {
            val plyParts = parts.map { PlyWriter.PlyPart(it.name, it.mesh) }
            val ascii = PlyWriter.writeAscii(plyParts)
            artifacts += ExportArtifact(
                fileName = "$stem.ply",
                mimeType = "application/octet-stream",
                byteSizeEstimate = ascii.length.toLong(),
                write = { output -> output.write(ascii.toByteArray(Charsets.US_ASCII)) },
            )
        }

        return artifacts
    }

    /**
     * Writes every artifact into [directory] (created when missing) and returns the files in
     * the order they were produced. Failures are per-file: one bad mesh must not cost the
     * operator the protocol PDF.
     */
    fun writeToDirectory(request: Request, directory: java.io.File): Result {
        if (!directory.exists() && !directory.mkdirs()) {
            return Result(emptyList(), listOf("Cannot create export folder: ${directory.absolutePath}"))
        }
        val written = ArrayList<java.io.File>(4)
        val errors = ArrayList<String>(2)
        for (artifact in build(request)) {
            val file = java.io.File(directory, artifact.fileName)
            try {
                file.outputStream().use { artifact.write(it) }
                written += file
            } catch (error: Throwable) {
                errors += "${artifact.fileName}: ${error.message ?: error.javaClass.simpleName}"
                file.delete()
            }
        }
        return Result(written, errors)
    }

    data class Result(val files: List<java.io.File>, val errors: List<String>) {
        val isSuccess: Boolean get() = files.isNotEmpty()
    }

    companion object {
        /** Filesystem-safe stem: ASCII letters/digits/dash, ≤ 40 chars. */
        fun slug(value: String, maxLength: Int = 40): String {
            val cleaned = value.trim().map { character ->
                when {
                    character.isLetterOrDigit() && character.code < 128 -> character
                    character == ' ' || character == '-' || character == '_' -> '-'
                    else -> null
                }
            }.filterNotNull().joinToString("").trim('-')
            val collapsed = cleaned.replace(Regex("-{2,}"), "-")
            return (collapsed.ifBlank { "project" }).take(maxLength).trim('-').ifBlank { "project" }
        }

        private fun colourFor(label: String, project: ProjectRecord): Int =
            project.routes.firstOrNull { it.label == label || it.spec.label == label }?.spec?.colorArgb
                ?: 0xFFFF6B00.toInt()

        /** Convenience for callers that only need geometry: builds every route mesh. */
        fun routeMeshes(project: ProjectRecord): List<Pair<String, MeshData>> =
            project.routes.mapNotNull { route ->
                if (!route.isRoutable) return@mapNotNull null
                runCatching { route.label to RouteGeometry.build(route).mesh }.getOrNull()
            }

        /**
         * Identity transform. Exported meshes are already in world space (Y-up, metres), so
         * every glTF node gets this transform and BIM viewers place the model without a
         * transform stack.
         */
        val WORLD_SPACE: Mat4 = Mat4.IDENTITY
    }
}
