/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.constructar.app.R
import com.constructar.export.pdf.PdfLabels

/**
 * Builds the protocol's text from the app's string resources.
 *
 * The split exists so the export module can draw a PDF without an Android resource table (it
 * stays JVM-testable) while the document itself is in the operator's language. Every key here
 * exists in all six locales and `verify_sources.py` fails the build if one goes missing.
 */
@Composable
fun rememberPdfLabels(): PdfLabels = PdfLabels(
    title = stringResource(R.string.pdf_title),
    subtitle = stringResource(R.string.pdf_subtitle),
    screenshotCaption = stringResource(R.string.pdf_screenshot_caption),
    sectionSummary = stringResource(R.string.pdf_section_summary),
    sectionRuns = stringResource(R.string.pdf_section_runs),
    sectionProcurement = stringResource(R.string.pdf_section_procurement),
    labelRuns = stringResource(R.string.pdf_label_runs),
    labelTotalLength = stringResource(R.string.pdf_label_total_length),
    labelWithWaste = stringResource(R.string.pdf_label_with_waste),
    labelClips = stringResource(R.string.pdf_label_clips),
    labelCouplings = stringResource(R.string.pdf_label_couplings),
    labelScannedTriangles = stringResource(R.string.pdf_label_scanned_triangles),
    labelCeilingHeight = stringResource(R.string.pdf_label_ceiling_height),
    columnLabel = stringResource(R.string.pdf_column_label),
    columnType = stringResource(R.string.pdf_column_type),
    columnNetMeters = stringResource(R.string.pdf_column_net_meters),
    columnBends = stringResource(R.string.pdf_column_bends),
    columnConfidence = stringResource(R.string.pdf_column_confidence),
    columnMaterial = stringResource(R.string.pdf_column_material),
    columnWaste = stringResource(R.string.pdf_column_waste),
    columnOrderMeters = stringResource(R.string.pdf_column_order_meters),
    procurementNote = stringResource(R.string.pdf_procurement_note),
    footer = stringResource(R.string.pdf_footer),
    pageLabel = stringResource(R.string.pdf_page_label),
)
