/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.constructar.core.bom.MaterialList
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ProjectRecord
import com.constructar.core.units.LengthFormatter
import java.io.OutputStream
import java.util.Locale

/**
 * Writes the on-site measurement protocol as a PDF.
 *
 * Contents (the order a foreman actually needs them):
 *  1. header — project, site, technician, timestamp, "offline measurement, no cloud";
 *  2. the AR screenshot of the scanned room, so the numbers are traceable to what was seen;
 *  3. the run table — label, trade, spec, net length, bends, confidence;
 *  4. the procurement table from [MaterialList] — net, waste, order length, clips;
 *  5. per-page footer with page numbers and the app version.
 *
 * Everything is drawn with `android.graphics` primitives on `PdfDocument`, which needs no
 * third-party PDF library (all of them either phone home or ship a GPL/AGPL transitive).
 */
class ProtocolPdfWriter(
    private val pageWidth: Int = A4_WIDTH,
    private val pageHeight: Int = A4_HEIGHT,
) {
    data class Content(
        val project: ProjectRecord,
        val materialList: MaterialList,
        /** AR screenshot; optional (a re-export after the session ended has none). */
        val screenshot: Bitmap? = null,
        val generatedAtLabel: String = "",
        val appVersion: String = "1.0",
        val operatorName: String = "",
        /**
         * Text stamped diagonally across every page, or blank for a licensed build.
         *
         * Localised by the caller (the app owns the resources), never by this writer: the
         * export module has no Android string table by design, so it stays testable on the JVM.
         */
        val watermark: String = "",
        /**
         * Handover signature, drawn on its own final page.
         *
         * A protocol that leaves the site without a signature is a note; one that the customer
         * signed is the document a dispute is settled with. It gets a dedicated page because
         * the table pages are already full - a signature squeezed under a material table reads
         * as an afterthought, and this one is the point of the document.
         */
        val signature: Bitmap? = null,
        /** Heading of the signature page, already localised by the caller. */
        val signatureTitle: String = "",
        /** Line under the signature, e.g. who signed and what for. */
        val signatureCaption: String = "",
        /** All visible text, localised by the caller. See [PdfLabels]. */
        val labels: PdfLabels = PdfLabels(),
        /**
         * The product badge, printed top-right on the first page.
         *
         * Branding a document that leaves the site is the cheapest marketing there is: the
         * customer files it, the wholesaler sees it, the next foreman asks what made it.
         */
        val logo: Bitmap? = null,
    )

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 10f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 11f
    }
    private val headerRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 9.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rulePaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 0.5f
    }

    /** Renders the protocol into [output]; the caller owns stream lifetime and storage. */
    fun write(content: Content, output: OutputStream) {
        val document = PdfDocument()
        try {
            var pageNumber = 1
            var page = newPage(document, pageNumber)

            // ── Header ──────────────────────────────────────────────────────────────────
            var y = MARGIN
            page.canvas.drawText(content.labels.title, MARGIN.toFloat(), y.toFloat(), titlePaint)
            content.logo?.let { logo ->
                val target = 46
                page.canvas.drawBitmap(
                    logo,
                    null,
                    Rect(pageWidth - MARGIN - target, MARGIN - 30, pageWidth - MARGIN, MARGIN - 30 + target),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
            y += 18
            val subtitle = buildString {
                append(content.project.name)
                if (content.project.siteName.isNotBlank()) append(" · ").append(content.project.siteName)
            }
            page.canvas.drawText(subtitle, MARGIN.toFloat(), y.toFloat(), subtitlePaint)
            y += 16
            val metaLine = listOfNotNull(
                content.generatedAtLabel.ifBlank { null },
                content.operatorName.ifBlank { content.project.technician.ifBlank { null } },
                content.labels.subtitle,
            ).joinToString("  ·  ")
            page.canvas.drawText(metaLine, MARGIN.toFloat(), y.toFloat(), textPaint)
            y += 10
            page.canvas.drawLine(MARGIN.toFloat(), y.toFloat(), (pageWidth - MARGIN).toFloat(), y.toFloat(), rulePaint)
            y += 16

            // ── Screenshot ──────────────────────────────────────────────────────────────
            val screenshot = content.screenshot
            if (screenshot != null) {
                val maxWidth = pageWidth - 2 * MARGIN
                val scale = maxWidth.toFloat() / screenshot.width
                val height = (screenshot.height * scale).toInt().coerceAtMost(380)
                val destination = Rect(MARGIN, y, MARGIN + maxWidth, y + height)
                page.canvas.drawBitmap(screenshot, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
                y += height + 8
                page.canvas.drawText(
                    content.labels.screenshotCaption,
                    MARGIN.toFloat(), y.toFloat(), subtitlePaint,
                )
                y += 18
            }

            // ── Summary ─────────────────────────────────────────────────────────────────
            y = drawSectionTitle(page.canvas, content.labels.sectionSummary, y)
            val scan = content.project.scan
            val summary = ArrayList<Pair<String, String>>()
            summary += content.labels.labelRuns to content.project.routes.size.toString()
            summary += content.labels.labelTotalLength to LengthFormatter.format(content.project.totalLengthMeters)
            summary += content.labels.labelWithWaste to LengthFormatter.format(content.materialList.totalWithWasteMeters)
            summary += content.labels.labelClips to content.materialList.totalClips.toString()
            summary += content.labels.labelCouplings to content.materialList.totalCouplings.toString()
            if (scan != null && scan.meshTriangleCount > 0) {
                summary += content.labels.labelScannedTriangles to "%,d".format(Locale.ROOT, scan.meshTriangleCount)
                if (scan.ceilingHeightMeters > 0f) {
                    summary += content.labels.labelCeilingHeight to LengthFormatter.format(scan.ceilingHeightMeters)
                }
            }
            y = drawKeyValueGrid(page.canvas, summary, y)

            // ── Runs table ──────────────────────────────────────────────────────────────
            if (content.project.routes.isNotEmpty()) {
                y = drawSectionTitle(page.canvas, content.labels.sectionRuns, y)
                val columns = listOf(180, 90, 60, 55, 80)
                val header = listOf(
                    content.labels.columnLabel,
                    content.labels.columnType,
                    content.labels.columnNetMeters,
                    content.labels.columnBends,
                    content.labels.columnConfidence,
                )
                y = drawTableHeader(page.canvas, header, columns, y)
                for (route in content.project.routes) {
                    if (y > pageHeight - FOOTER_HEIGHT - 40) {
                        drawFooter(page.canvas, pageNumber, content)
                        document.finishPage(page)
                        pageNumber++
                        page = newPage(document, pageNumber)
                        y = MARGIN
                        y = drawTableHeader(page.canvas, header, columns, y)
                    }
                    y = drawTableRow(page.canvas, routeRow(route), columns, y)
                }
            }

            // ── Procurement table ───────────────────────────────────────────────────────
            if (!content.materialList.isEmpty) {
                if (y > pageHeight - FOOTER_HEIGHT - 120) {
                    drawFooter(page.canvas, pageNumber, content)
                    document.finishPage(page)
                    pageNumber++
                    page = newPage(document, pageNumber)
                    y = MARGIN
                }
                y = drawSectionTitle(page.canvas, content.labels.sectionProcurement, y)
                val columns = listOf(150, 80, 60, 60, 90)
                y = drawTableHeader(page.canvas, listOf(
                    content.labels.columnMaterial,
                    content.labels.columnType,
                    content.labels.columnNetMeters,
                    content.labels.columnWaste,
                    content.labels.columnOrderMeters,
                ), columns, y)
                for (line in content.materialList.lines) {
                    if (y > pageHeight - FOOTER_HEIGHT - 30) {
                        drawFooter(page.canvas, pageNumber, content)
                        document.finishPage(page)
                        pageNumber++
                        page = newPage(document, pageNumber)
                        y = MARGIN
                        y = drawTableHeader(page.canvas, listOf(
                    content.labels.columnMaterial,
                    content.labels.columnType,
                    content.labels.columnNetMeters,
                    content.labels.columnWaste,
                    content.labels.columnOrderMeters,
                ), columns, y)
                    }
                    y = drawTableRow(
                        page.canvas,
                        listOf(
                            line.spec.material.ifBlank { line.spec.sizeLabel },
                            line.spec.sizeLabel,
                            format(line.totalMeters),
                            "${(line.wasteFactor * 100).toInt()} %",
                            format(line.withWasteMeters),
                        ),
                        columns,
                        y,
                    )
                }
                y += 6
                drawNote(page.canvas, content.labels.procurementNote, y)
            }

            drawFooter(page.canvas, pageNumber, content)
            document.finishPage(page)

            content.signature?.let { signature ->
                pageNumber += 1
                drawSignaturePage(document, pageNumber, signature, content)
            }

            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    /** Signature, rule and caption on a page of their own. */
    private fun drawSignaturePage(
        document: PdfDocument,
        pageNumber: Int,
        signature: Bitmap,
        content: Content,
    ) {
        val page = newPage(document, pageNumber)
        var y = drawSectionTitle(page.canvas, content.signatureTitle, MARGIN)
        y += 8
        val width = pageWidth - 2 * MARGIN
        val height = (width * signature.height / signature.width.coerceAtLeast(1)).coerceIn(120, 420)
        page.canvas.drawBitmap(
            signature,
            null,
            Rect(MARGIN, y, MARGIN + width, y + height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        y += height + 18
        page.canvas.drawLine(MARGIN.toFloat(), y.toFloat(), (MARGIN + width).toFloat(), y.toFloat(), rulePaint)
        y += 16
        if (content.signatureCaption.isNotBlank()) {
            page.canvas.drawText(content.signatureCaption, MARGIN.toFloat(), y.toFloat(), subtitlePaint)
        }
        drawFooter(page.canvas, pageNumber, content)
        document.finishPage(page)
    }

    // ── Drawing primitives ──────────────────────────────────────────────────────────────

    private fun newPage(document: PdfDocument, pageNumber: Int): PdfDocument.Page =
        document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())

    private fun drawSectionTitle(canvas: Canvas, title: String, y: Int): Int {
        val next = y + 16
        canvas.drawText(title, MARGIN.toFloat(), next.toFloat(), headerRowPaint.apply { textSize = 12f })
        headerRowPaint.textSize = 9.5f
        canvas.drawLine(MARGIN.toFloat(), (next + 4).toFloat(), (pageWidth - MARGIN).toFloat(), (next + 4).toFloat(), rulePaint)
        return next + 16
    }

    private fun drawKeyValueGrid(canvas: Canvas, entries: List<Pair<String, String>>, startY: Int): Int {
        var y = startY
        var column = 0
        val columnWidth = (pageWidth - 2 * MARGIN) / 2
        for ((index, entry) in entries.withIndex()) {
            val x = MARGIN + column * columnWidth
            canvas.drawText(entry.first, x.toFloat(), y.toFloat(), subtitlePaint)
            canvas.drawText(entry.second, (x + 110).toFloat(), y.toFloat(), textPaint)
            if (column == 1 || index == entries.lastIndex) y += 15
            column = (column + 1) % 2
        }
        return y + 10
    }

    private fun drawTableHeader(canvas: Canvas, headers: List<String>, columns: List<Int>, y: Int): Int {
        var x = MARGIN
        for ((index, header) in headers.withIndex()) {
            canvas.drawText(header, x.toFloat(), y.toFloat(), headerRowPaint)
            x += columns[index]
        }
        canvas.drawLine(MARGIN.toFloat(), (y + 3).toFloat(), (pageWidth - MARGIN).toFloat(), (y + 3).toFloat(), rulePaint)
        return y + 14
    }

    private fun drawTableRow(canvas: Canvas, values: List<String>, columns: List<Int>, y: Int): Int {
        var x = MARGIN
        for ((index, value) in values.withIndex()) {
            val width = columns[index]
            canvas.drawText(ellipsize(value, width - 4), x.toFloat(), y.toFloat(), textPaint)
            x += width
        }
        return y + 13
    }

    private fun drawNote(canvas: Canvas, text: String, y: Int) {
        val paint = Paint(textPaint).apply { textSize = 8.5f; color = Color.DKGRAY }
        var lineY = y
        for (line in wrap(text, pageWidth - 2 * MARGIN, paint)) {
            canvas.drawText(line, MARGIN.toFloat(), lineY.toFloat(), paint)
            lineY += 11
        }
    }

    /**
     * The free-build stamp: one line of grey text rotated across the middle of the page.
     *
     * Drawn as part of the footer so it lands on *every* page including ones created by a
     * table overflow - a watermark that misses page 4 is worse than none.
     */
    private fun drawWatermark(canvas: Canvas, text: String) {
        if (text.isBlank()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(56, 0, 0, 0)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.save()
        canvas.rotate(-32f, pageWidth / 2f, pageHeight / 2f)
        canvas.drawText(text, pageWidth / 2f, pageHeight / 2f, paint)
        canvas.restore()
    }

    private fun drawFooter(canvas: Canvas, pageNumber: Int, content: Content) {
        drawWatermark(canvas, content.watermark)
        val y = pageHeight - FOOTER_HEIGHT
        canvas.drawLine(MARGIN.toFloat(), y.toFloat(), (pageWidth - MARGIN).toFloat(), y.toFloat(), rulePaint)
        val footer = Paint(textPaint).apply { textSize = 8f; color = Color.GRAY }
        canvas.drawText(
            String.format(Locale.ROOT, content.labels.footer, content.appVersion),
            MARGIN.toFloat(),
            (y + 14).toFloat(),
            footer,
        )
        val pageLabel = String.format(Locale.ROOT, content.labels.pageLabel, pageNumber)
        canvas.drawText(pageLabel, pageWidth - MARGIN - footer.measureText(pageLabel), (y + 14).toFloat(), footer)
    }

    private fun routeRow(route: ConduitRoute): List<String> {
        val metrics = com.constructar.core.geo.PolylineMetrics.measure(route)
        val confidence = (metrics.minimumConfidence * 100).toInt()
        return listOf(
            route.label,
            route.spec.sizeLabel,
            format(route.polylineLengthMeters),
            metrics.bendCount.toString(),
            "$confidence %",
        )
    }

    private fun ellipsize(value: String, maxWidth: Int): String {
        if (textPaint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && textPaint.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end) + "…"
    }

    private fun wrap(text: String, maxWidth: Int, paint: Paint): List<String> {
        val words = text.split(' ')
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun format(meters: Float): String = String.format(Locale.ROOT, "%.2f", meters)

    companion object {
        /** A4 at 72 dpi, the unit `PdfDocument` uses. */
        const val A4_WIDTH = 595
        const val A4_HEIGHT = 842
        private const val MARGIN = 36
        private const val FOOTER_HEIGHT = 32
    }
}
