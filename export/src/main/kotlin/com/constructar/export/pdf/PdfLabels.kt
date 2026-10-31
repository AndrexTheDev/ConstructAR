/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

/**
 * Every piece of text that appears on the protocol, as data.
 *
 * The export module has no Android resource table on purpose: it must stay a plain JVM library so
 * the PDF can be generated and tested without a device. That used to mean the protocol was
 * English-only while the rest of the app spoke six languages — an odd result for a document a
 * Spanish-speaking electrician hands to a Spanish-speaking customer.
 *
 * So the *caller* localises: the app builds a [PdfLabels] from its string resources and passes it
 * in, and this module just draws whatever it is given. The defaults below are English so a test,
 * a script or a future CLI tool can generate a protocol without a resources table, and so a
 * missing field degrades to something readable instead of an empty page.
 *
 * Format arguments: [footer] takes `%1$s` (app version), [pageLabel] takes `%1$d` (page number).
 */
data class PdfLabels(
    val title: String = "Measurement protocol",
    val subtitle: String = "Offline measurement - computed on device, no cloud",
    val screenshotCaption: String = "AR overlay at time of measurement (the runs below are the same geometry).",
    val sectionSummary: String = "Summary",
    val sectionRuns: String = "Runs",
    val sectionProcurement: String = "Procurement",
    val labelRuns: String = "Runs",
    val labelTotalLength: String = "Total length",
    val labelWithWaste: String = "With waste",
    val labelClips: String = "Clips",
    val labelCouplings: String = "Couplings",
    val labelScannedTriangles: String = "Scanned triangles",
    val labelCeilingHeight: String = "Ceiling height",
    val columnLabel: String = "Label",
    val columnType: String = "Type",
    val columnNetMeters: String = "Net (m)",
    val columnBends: String = "Bends",
    val columnConfidence: String = "Confidence",
    val columnMaterial: String = "Material",
    val columnWaste: String = "Waste",
    val columnOrderMeters: String = "Order (m)",
    val procurementNote: String =
        "Order lengths include the site waste factor. Figures are computed from anchor-refined " +
            "node positions; verify critical runs with a tape measure before cutting.",
    val footer: String = "ConstructAR %1\$s - generated on device",
    val pageLabel: String = "Page %1\$d",
)
