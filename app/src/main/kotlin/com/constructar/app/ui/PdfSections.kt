/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.DefectItem
import com.constructar.core.model.DefectSeverity
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.WorkTime
import com.constructar.core.price.MoneyFormatter
import com.constructar.core.price.Pricing
import com.constructar.export.pdf.PdfPhoto
import com.constructar.export.pdf.PdfSection
import java.util.Locale

/**
 * Turns the site file into the sections of the protocol.
 *
 * Kept apart from [ExportScreen] for one reason: the export itself is not composable, so every
 * sentence has to be resolved *before* the operator picks a folder. [SiteFileTexts] is that
 * resolved vocabulary; [buildSiteFileSections] is then a pure function of project + words, and
 * can be reasoned about (and later tested) without a Compose runtime.
 *
 * Order matters: the customer first, because that is the line the reader checks; then the hours
 * that are billed; then what happened; then what is still broken.
 */
internal data class SiteFileTexts(
    val customerTitle: String,
    val customerLabel: String,
    val addressLabel: String,
    val phoneLabel: String,
    val timesTitle: String,
    val columnDate: String,
    val columnStart: String,
    val columnEnd: String,
    val columnDuration: String,
    val totalLabel: String,
    val runningLabel: String,
    val diaryTitle: String,
    val defectsTitle: String,
    val columnDefect: String,
    val columnSeverity: String,
    val columnStatus: String,
    val openStatus: String,
    val resolvedStatus: String,
    val severityLow: String,
    val severityMedium: String,
    val severityHigh: String,
    val hoursMinutes: String,
    val minutesOnly: String,
    val costsTitle: String,
    val labourLabel: String,
    val materialLabel: String,
    val netTotalLabel: String,
    val netNote: String,
    val photosTitle: String,
    val photoDiaryPrefix: String,
    val photoDefectPrefix: String,
)

/** A4 printable width at 72 dpi, the budget the tables divide between their columns. */
private const val PRINTABLE_PT = 523

internal fun buildSiteFileSections(
    project: ProjectRecord,
    texts: SiteFileTexts,
    nowMs: Long = System.currentTimeMillis(),
): List<PdfSection> {
    val sections = ArrayList<PdfSection>(4)

    // ── Customer / object ───────────────────────────────────────────────────────────────
    val customerLines = listOfNotNull(
        project.customerName.takeIf { it.isNotBlank() }?.let { "${texts.customerLabel}: $it" },
        project.customerAddress.takeIf { it.isNotBlank() }?.let { "${texts.addressLabel}: $it" },
        project.customerPhone.takeIf { it.isNotBlank() }?.let { "${texts.phoneLabel}: $it" },
    )
    if (customerLines.isNotEmpty()) {
        sections += PdfSection(title = texts.customerTitle, paragraphs = customerLines)
    }

    // ── Working time ────────────────────────────────────────────────────────────────────
    if (project.sessions.isNotEmpty()) {
        val rows = project.sessions.map { session ->
            listOf(
                formatDateLabel(session.startEpochMs),
                formatTimeLabel(session.startEpochMs),
                session.endEpochMs?.let { formatTimeLabel(it) } ?: texts.runningLabel,
                duration(WorkTime.sessionMillis(session, nowMs), texts),
            )
        }
        val total = duration(WorkTime.totalMillis(project.sessions, nowMs), texts)
        sections += PdfSection(
            title = texts.timesTitle,
            columns = listOf(texts.columnDate, texts.columnStart, texts.columnEnd, texts.columnDuration),
            columnWidths = columns(0.34f, 0.20f, 0.20f, 0.26f),
            rows = rows,
            note = "${texts.totalLabel}: $total",
        )
    }

    // ── Site diary ──────────────────────────────────────────────────────────────────────
    // Stored newest first for the screen; the protocol reads forwards, the way the job happened.
    val diary = project.diary.asReversed()
    if (diary.isNotEmpty()) {
        sections += PdfSection(
            title = texts.diaryTitle,
            paragraphs = diary.map { "${formatDateTimeLabel(it.createdAtEpochMs)} — ${it.text}" },
        )
    }

    // ── Defects ─────────────────────────────────────────────────────────────────────────
    if (project.defects.isNotEmpty()) {
        val rows = project.defects.map { defect -> defectRow(defect, texts) }
        sections += PdfSection(
            title = texts.defectsTitle,
            columns = listOf(texts.columnDate, texts.columnDefect, texts.columnSeverity, texts.columnStatus),
            columnWidths = columns(0.15f, 0.47f, 0.16f, 0.22f),
            rows = rows,
        )
    }

    return sections
}

private fun defectRow(defect: DefectItem, texts: SiteFileTexts): List<String> = listOf(
    formatDateLabel(defect.createdAtEpochMs),
    if (defect.location.isBlank()) defect.title else "${defect.title} · ${defect.location}",
    when (defect.severity) {
        DefectSeverity.LOW -> texts.severityLow
        DefectSeverity.MEDIUM -> texts.severityMedium
        DefectSeverity.HIGH -> texts.severityHigh
    },
    if (defect.resolved) texts.resolvedStatus else texts.openStatus,
)

/** `2 h 15 min`, or `45 min` — the same split the site file shows on screen. */
private fun duration(millis: Long, texts: SiteFileTexts): String =
    durationText(millis, texts.hoursMinutes, texts.minutesOnly)

/**
 * The duration line of the time section.
 *
 * `Locale.ROOT`, like every other number in the document: `String.format(getDefault())` renders
 * digits in the device's own shapes, so the same protocol printed on an Arabic-locale phone said
 * `٢ h ١٥ min` next to `14.82 m` and `1.250,00 €`. A signed handover document must not change
 * its digits because the phone was set to another language - and the six locales this app speaks
 * all use Latin digits anyway, so nothing is lost. Money is the one deliberate exception (see
 * `MoneyFormatter`: a price is read by the customer and follows the phone's convention).
 */
internal fun durationText(millis: Long, hoursTemplate: String, minutesTemplate: String): String {
    val hours = WorkTime.hours(millis)
    val minutes = WorkTime.minutes(millis)
    return if (hours > 0) {
        String.format(Locale.ROOT, hoursTemplate, hours, minutes)
    } else {
        String.format(Locale.ROOT, minutesTemplate, minutes)
    }
}

/** Column widths as fractions of the printable width; the remainder lands in the last column. */
private fun columns(vararg fractions: Float): List<Int> {
    val widths = fractions.map { (PRINTABLE_PT * it).toInt() }
    val used = widths.sum()
    return widths.dropLast(1) + (PRINTABLE_PT - (used - widths.last()))
}

/**
 * Turns hours and material into the money block the customer actually reads.
 *
 * Returns `null` for a project without time and without material: an empty "0,00 €" block would
 * only invite the question whether the job was free.
 *
 * The arithmetic lives in [Pricing], not here, so the protocol and the on-screen card cannot
 * drift apart.
 */
internal fun buildCostSection(
    project: ProjectRecord,
    hourlyRateCents: Int,
    texts: SiteFileTexts,
    nowMs: Long = System.currentTimeMillis(),
): PdfSection? {
    val workMillis = WorkTime.totalMillis(project.sessions, nowMs)
    val labour = Pricing.labourCents(workMillis, hourlyRateCents)
    val material = project.materialLumpSumCents
    if (workMillis <= 0L && material <= 0L) return null

    val lines = ArrayList<String>(3)
    if (workMillis > 0L) {
        lines += "%s: %s x %s".format(
            texts.labourLabel,
            duration(workMillis, texts),
            MoneyFormatter.format(hourlyRateCents.toLong()),
        )
    }
    if (material > 0L) {
        lines += "%s: %s".format(texts.materialLabel, MoneyFormatter.format(material))
    }
    lines += "%s: %s".format(texts.netTotalLabel, MoneyFormatter.format(labour + material))

    return PdfSection(
        title = texts.costsTitle,
        paragraphs = lines,
        note = texts.netNote,
    )
}

/**
 * Collects the entry photos that exist on this phone, newest first.
 *
 * Only photos that are actually stored are listed - a token whose file failed to decode must not
 * produce a caption pointing at nothing.
 */
internal fun buildPhotoAppendix(
    project: ProjectRecord,
    photos: Map<String, android.graphics.Bitmap>,
    texts: SiteFileTexts,
): List<PdfPhoto> {
    if (photos.isEmpty()) return emptyList()
    // The captions, keyed by token, so the page can follow the one order the field book uses -
    // open defects first, because those are what the customer is asked to sign off as still
    // outstanding, then the diary. Before finding 42 each list was appended in its own order and
    // the cut at eight could drop the photo of every open defect for want of diary room.
    val captions = HashMap<String, String>()
    project.diary.forEach { entry ->
        entry.photo?.let { token ->
            captions.getOrPut(token) {
                "%s %s".format(texts.photoDiaryPrefix, formatDateLabel(entry.createdAtEpochMs))
            }
        }
    }
    project.defects.forEach { defect ->
        defect.photo?.let { token ->
            captions[token] = "%s: %s".format(texts.photoDefectPrefix, defect.title)
        }
    }
    return FieldPhotos.ordered(project)
        .mapNotNull { token ->
            val bitmap = photos[token] ?: return@mapNotNull null
            PdfPhoto(caption = captions[token] ?: return@mapNotNull null, bitmap = bitmap)
        }
        .take(FieldPhotos.PDF_BUDGET)
}
