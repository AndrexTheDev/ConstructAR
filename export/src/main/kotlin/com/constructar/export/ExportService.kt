/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export

import android.graphics.Bitmap
import com.constructar.core.bom.MaterialList
import com.constructar.core.calib.CalibrationTransform
import com.constructar.core.math.Mat4
import com.constructar.core.mesh.MeshData
import com.constructar.core.mesh.RouteGeometry
import com.constructar.core.model.ProjectRecord
import com.constructar.export.csv.MaterialCsvWriter
import com.constructar.export.glb.GlbWriter
import com.constructar.export.ply.PlyWriter
import com.constructar.export.pdf.FloorplanSection
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
        val write: (OutputStream) -> Unit,
    )

    /**
     * The 3D geometry of a project: the meshes that could be built, and the runs that could not.
     *
     * The two halves travel together on purpose. A `List<Pair<String, MeshData>>` could only say
     * "these are the runs in the model"; it could not say which runs of the same project are
     * missing from it, and that silence is exactly what finding 80 was about.
     */
    data class RouteMeshes(
        /** Label → mesh, in the order the runs table prints them. */
        val parts: List<Pair<String, MeshData>> = emptyList(),
        /** Labels of runs that are measured and priced but have no geometry. */
        val unbuildable: List<String> = emptyList(),
    )

    data class Request(
        val project: ProjectRecord,
        val materialList: MaterialList,
        /** Geometry of every route, already generated (world space, metres). */
        val routeMeshes: RouteMeshes = RouteMeshes(),
        /**
         * The TSDF room scan, if a caller has one in hand.
         *
         * No caller does today: the volume lives in the AR session and is gone by the time the
         * export screen exists, and nothing writes it to disk (a 10 MB mesh per room against an app
         * that fits in 25 MB). It stays in the model for the day a scan is stored - but the screen
         * must not promise a model that contains it (finding 87).
         */
        val scanMesh: MeshData? = null,
        val screenshot: Bitmap? = null,
        val operatorName: String = "",
        val appVersion: String = "1.0",
        val includePdf: Boolean = true,
        val includeCsv: Boolean = true,
        /** The one-line-per-article order list for the wholesaler, next to the full CSV. */
        val includeOrderList: Boolean = false,
        val includeGlb: Boolean = true,
        val includePly: Boolean = true,
        /**
         * Include a supplied [scanMesh] (not just the runs) in the 3D exports.
         *
         * `false` by default, because that is the truth of every call site: the export contains the
         * measured runs. A default of `true` claimed a scan the app cannot deliver (finding 87).
         */
        val includeScanGeometry: Boolean = false,
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
        /**
         * The calibration the reported metres were corrected by, already localised; blank when
         * none was applied. Printed in the protocol header and as a CSV preamble row, so a
         * document that shows corrected metres also shows what corrected them.
         */
        val calibrationLabel: String = "",
        /** The product badge drawn on the first page. */
        val logo: Bitmap? = null,
        /**
         * The room's plan for the protocol page, built by the caller from the stored outline.
         *
         * `:app` owns the words on it (title, caption, the note that a wall was drawn by hand);
         * this module owns the page. `null` when the project has no closed outline, and the
         * document then has no plan page at all.
         */
        val floorplan: FloorplanSection? = null,
        /** Site file sections (customer, time, diary, defects, costs), localised by the caller. */
        val sections: List<PdfSection> = emptyList(),
        /** Documentation photos for the protocol appendix, with localised captions. */
        val photos: List<PdfPhoto> = emptyList(),
        /** Heading of the photo appendix. */
        val photosTitle: String = "",
        /** Every photo on the job; [photos] is only the ones the appendix can hold. */
        val photosTotal: Int = photos.size,
    )

    /** Human/machine-readable file stem, e.g. `ConstructAR_Bad-Keller_20260314-1015`. */
    fun fileStem(project: ProjectRecord, atMillis: Long = clock()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(atMillis))
        return "ConstructAR_${slug(project.name)}_$stamp"
    }

    /**
     * Builds every requested artifact. Never throws for a missing mesh: it just omits it - and
     * says so in the protocol, through [Request.routeMeshes]'s `unbuildable` labels.
     *
     * @param atMillis the instant this export is stamped with. The caller that also names the
     *   destination folder **must** pass the same value it used for [fileStem]: the folder and the
     *   files inside it are one export, and a screen that read the clock twice could create
     *   `ConstructAR_Kueche_20260314-1015/` holding `ConstructAR_Kueche_20260314-1016.pdf` when the
     *   minute turned between the two reads (finding 122).
     * @param stem the file-name stem, derived from [atMillis] unless the caller already has it.
     */
    fun build(
        request: Request,
        atMillis: Long = clock(),
        stem: String = fileStem(request.project, atMillis),
    ): List<ExportArtifact> {
        val timestampLabel = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
            .format(Date(atMillis))
        val artifacts = ArrayList<ExportArtifact>(4)
        val calibration = CalibrationTransform.of(request.project.calibrationScale)

        if (request.includePdf) {
            artifacts += ExportArtifact(
                fileName = "$stem.pdf",
                mimeType = "application/pdf",
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
                            calibrationLabel = request.calibrationLabel,
                            unbuildableRuns = request.routeMeshes.unbuildable,
                            floorplan = request.floorplan,
                            sections = request.sections,
                            photos = request.photos,
                            photosTitle = request.photosTitle,
                            photosTotal = request.photosTotal,
                            logo = request.logo,
                        ),
                        output,
                    )
                },
            )
        }

        if (request.includeCsv) {
            val runDetails = request.project.routes.map { route ->
                Triple(route.label, route.spec.sizeLabel, request.project.calibratedMeters(route))
            }
            val csv = MaterialCsvWriter.write(
                materialList = request.materialList,
                options = MaterialCsvWriter.Options(includeRunDetail = true),
                generatedAtLabel = timestampLabel,
                runDetails = runDetails,
                calibrationLabel = request.calibrationLabel,
            )
            artifacts += ExportArtifact(
                fileName = "$stem.csv",
                mimeType = "text/csv",
                write = { output -> output.write(csv.toByteArray(Charsets.UTF_8)) },
            )
        }

        if (request.includeOrderList) {
            val orderList = MaterialCsvWriter.writeOrderList(
                materialList = request.materialList,
                generatedAtLabel = timestampLabel,
                calibrationLabel = request.calibrationLabel,
            )
            artifacts += ExportArtifact(
                fileName = "$stem-order.csv",
                mimeType = "text/csv",
                write = { output -> output.write(orderList.toByteArray(Charsets.UTF_8)) },
            )
        }

        val parts = buildList {
            request.routeMeshes.parts.forEach { (label, mesh) ->
                if (!mesh.isEmpty) {
                    add(GlbWriter.GlbPart(label, mesh, colourFor(label, request.project)))
                }
            }
            if (request.includeScanGeometry) {
                request.scanMesh?.takeIf { !it.isEmpty }?.let {
                    // The same violet the operator walked through on the glass (finding 267):
                    // the room in the file has to be recognisable as the room he captured, not as
                    // a grey lump that happens to be in the same place.
                    add(GlbWriter.GlbPart("Room scan", calibration.mesh(it), 0xFFA25BFF.toInt()))
                }
            }
        }

        if (request.includeGlb && parts.isNotEmpty()) {
            val bytes = glbWriter().write(parts, generator = "ConstructAR ${request.appVersion}", sceneName = request.project.name)
            artifacts += ExportArtifact(
                fileName = "$stem.glb",
                mimeType = "model/gltf-binary",
                write = { output -> output.write(bytes) },
            )
        }

        if (request.includePly && parts.isNotEmpty()) {
            val plyParts = parts.map { PlyWriter.PlyPart(it.name, it.mesh) }
            artifacts += ExportArtifact(
                fileName = "$stem.ply",
                mimeType = "application/octet-stream",
                // `PlyWriter.write` decides the layout by size: text while a person might still
                // open it, binary little-endian once the geometry is too big to read anyway
                // (finding 167). Written inside the lambda like every other artifact, so a bundle
                // never holds the page, the sheet and the model at once.
                write = { output -> output.write(PlyWriter.write(plyParts)) },
            )
        }

        return artifacts
    }


    companion object {
        /**
         * Filesystem-safe stem in the operator's own words: letters of any script, digits, dash.
         *
         * The previous version kept ASCII only, so a room called "Küche" was exported as
         * `ConstructAR_Kche_20260314-1015.pdf` - the umlaut was dropped rather than transliterated,
         * which is not a shortened name but a different one. A French "Château", a Spanish "Baño"
         * or a Polish "Łazienka" fared no better, and those are the names this app's users type.
         *
         * Safe does not mean ASCII: every filesystem the app can write to (app storage, SAF, a
         * FAT/exFAT stick) stores UTF-8 names. What has to go is only what a path cannot hold -
         * separators, the reserved Windows characters, control characters - and each of those is
         * replaced by a dash so the surrounding words stay separated.
         */
        fun slug(value: String, maxLength: Int = 40): String {
            val cleaned = StringBuilder(value.length)
            for (character in value.trim()) {
                when {
                    character.isLetterOrDigit() -> cleaned.append(character)
                    character == ' ' || character == '-' || character == '_' || character == '.' -> cleaned.append('-')
                    character == '/' || character == '\\' -> cleaned.append('-')
                    character.isISOControl() -> Unit
                    character in "\\:*?\"<>|" -> cleaned.append('-')
                    // Punctuation, emoji, quotes: dropped, so "Raum (2)" becomes "Raum-2" below.
                    else -> Unit
                }
            }
            val collapsed = cleaned.toString().replace(Regex("-{2,}"), "-").trim('-')
            val limit = maxLength.coerceAtLeast(1)
            // Never cut a surrogate pair in half: an emoji in a room name must not become a
            // replacement character in the file name.
            val cut = if (collapsed.length > limit && collapsed[limit - 1].isHighSurrogate()) limit - 1 else limit
            return collapsed.take(cut).trim('-').ifBlank { "project" }
        }

        private fun colourFor(label: String, project: ProjectRecord): Int =
            project.routes.firstOrNull { it.label == label || it.spec.label == label }?.spec?.colorArgb
                ?: 0xFFFF6B00.toInt()

        /**
         * Geometry for every run that has some — and the names of the runs that have none.
         *
         * A run can be measurable and still produce no geometry. A conduit spec whose diameter was
         * lost (a migrated or hand-edited project record: `ConduitSpec` only requires dimensions to
         * be *non-negative*, so 0 mm survives deserialisation) makes the tube sweep refuse to build;
         * two nodes that anchor refinement pulled onto the same point make the smoother refuse. The
         * earlier version swallowed both in `runCatching { … }.getOrNull()`, so the protocol listed
         * the run, the GLB did not contain it, and nothing in the bundle said why (finding 80). Now
         * the run is named, next to the recovered runs, in the same sentence that already explains a
         * run that is in the paperwork but not in the model.
         */
        fun routeMeshes(project: ProjectRecord): RouteMeshes {
            val parts = ArrayList<Pair<String, MeshData>>(project.routes.size)
            val unbuildable = ArrayList<String>(0)
            val calibratedRoutes = CalibrationTransform.of(project.calibrationScale).routes(project.routes)
            for (route in calibratedRoutes) {
                if (!route.isRoutable) continue
                // A recovered run's node coordinates are relative to an ARCore session that no
                // longer exists: its lengths belong in the paperwork, its geometry does not
                // belong in the room. Emitting it would put a floating length of conduit into
                // every BIM viewer. See `ConduitRoute.recovered` — and the note that names it.
                if (route.recovered) continue
                // Deliberately `try`/`catch (Exception)`, not `runCatching`: the latter also
                // swallows `OutOfMemoryError` and would hand back a model that is silently short a
                // run because the phone ran out of memory building it.
                val mesh = try {
                    RouteGeometry.build(route).mesh
                } catch (failure: Exception) {
                    unbuildable += route.label
                    continue
                }
                if (mesh.isEmpty) {
                    unbuildable += route.label
                    continue
                }
                parts += route.label to mesh
            }
            return RouteMeshes(parts = parts, unbuildable = unbuildable)
        }

        /**
         * Identity transform. Exported meshes are already in world space (Y-up, metres), so
         * every glTF node gets this transform and BIM viewers place the model without a
         * transform stack.
         */
        val WORLD_SPACE: Mat4 = Mat4.IDENTITY
    }
}
