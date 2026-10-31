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
            page.canvas.drawText("Measurement protocol", MARGIN.toFloat(), y.toFloat(), titlePaint)
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
                "Offline measurement — computed on device, no cloud",
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
                    "AR overlay at time of measurement (the runs below are the same geometry).",
                    MARGIN.toFloat(), y.toFloat(), subtitlePaint,
                )
                y += 18
            }

            // ── Summary ─────────────────────────────────────────────────────────────────
            y = drawSectionTitle(page.canvas, "Summary", y)
            val scan = content.project.scan
            val summary = ArrayList<Pair<String, String>>()
            summary += "Runs" to content.project.routes.size.toString()
            summary += "Total length" to LengthFormatter.format(content.project.totalLengthMeters)
            summary += "With waste" to LengthFormatter.format(content.materialList.totalWithWasteMeters)
            summary += "Clips" to content.materialList.totalClips.toString()
            summary += "Couplings" to content.materialList.totalCouplings.toString()
            if (scan != null && scan.meshTriangleCount > 0) {
                summary += "Scanned triangles" to "%,d".format(Locale.ROOT, scan.meshTriangleCount)
                if (scan.ceilingHeightMeters > 0f) {
                    summary += "Ceiling height" to LengthFormatter.format(scan.ceilingHeightMeters)
                }
            }
            y = drawKeyValueGrid(page.canvas, summary, y)

            // ── Runs table ──────────────────────────────────────────────────────────────
            if (content.project.routes.isNotEmpty()) {
                y = drawSectionTitle(page.canvas, "Runs", y)
                val columns = listOf(180, 90, 60, 55, 80)
                val header = listOf("Label", "Type", "Net (m)", "Bends", "Confidence")
                y = drawTableHeader(page.canvas, header, columns, y)
                for (route in content.project.routes) {
                    if (y > pageHeight - FOOTER_HEIGHT - 40) {
                        drawFooter(page.canvas, pageNumber, content.appVersion)
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
                    drawFooter(page.canvas, pageNumber, content.appVersion)
                    document.finishPage(page)
                    pageNumber++
                    page = newPage(document, pageNumber)
                    y = MARGIN
                }
                y = drawSectionTitle(page.canvas, "Procurement", y)
                val columns = listOf(150, 80, 60, 60, 90)
                y = drawTableHeader(page.canvas, listOf("Material", "Type", "Net (m)", "Waste", "Order (m)"), columns, y)
                for (line in content.materialList.lines) {
                    if (y > pageHeight - FOOTER_HEIGHT - 30) {
                        drawFooter(page.canvas, pageNumber, content.appVersion)
                        document.finishPage(page)
                        pageNumber++
                        page = newPage(document, pageNumber)
                        y = MARGIN
                        y = drawTableHeader(page.canvas, listOf("Material", "Type", "Net (m)", "Waste", "Order (m)"), columns, y)
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
                drawNote(
                    page.canvas,
                    "Order lengths include the site waste factor. Figures are computed from anchor-refined " +
                        "node positions; verify critical runs with a tape measure before cutting.",
                    y,
                )
            }

            drawFooter(page.canvas, pageNumber, content.appVersion)
            document.finishPage(page)
            document.writeTo(output)
        } finally {
            document.close()
        }
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

    private fun drawFooter(canvas: Canvas, pageNumber: Int, appVersion: String) {
        val y = pageHeight - FOOTER_HEIGHT
        canvas.drawLine(MARGIN.toFloat(), y.toFloat(), (pageWidth - MARGIN).toFloat(), y.toFloat(), rulePaint)
        val footer = Paint(textPaint).apply { textSize = 8f; color = Color.GRAY }
        canvas.drawText("ConstructAR $appVersion · generated on device", MARGIN.toFloat(), (y + 14).toFloat(), footer)
        val pageLabel = "Page $pageNumber"
        canvas.drawText(pageLabel, (pageWidth - MARGIN - footer.measureText(pageLabel)).toFloat(), (y + 14).toFloat(), footer)
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
