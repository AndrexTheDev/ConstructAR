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
    /**
     * The accuracy the protocol promises, printed under the summary; `%1$d` = the confidence bar.
     *
     * The shop page told every visitor, in six languages, that "the protocol states the expected
     * error too" - and the protocol did not (finding 88). A customer signed a document that named
     * no tolerance anywhere, next to a "Confidence" column that nobody had explained: a percentage
     * with no unit and no bar is a number the reader has to take on trust, in the one file he signs.
     *
     * The figures are the design targets published in `README.md`, not new claims. The threshold
     * is not written into the translation but filled in by `accuracyNote()`, so it cannot drift
     * from [com.constructar.core.model.HitConfidence.RUN_TRUSTED] - the bar the HUD questions a
     * run at and the bill of materials flags it by. Literal percent signs are `%%` because the
     * writer formats this string.
     */
    val accuracyNote: String =
        "Expected accuracy: 1-2 cm on a flat wall between 1 and 4 m, 3-6 cm where only depth " +
            "data is available, about 1 %% of the length on a diagonal. Confidence is the " +
            "weakest point of a run - below %1\$d %% it needs a second look.",
    /** Printed under the runs table when part of the room could not be placed; `%1$s` = labels. */
    val recoveredNote: String =
        "Recovered after an interruption - lengths and fittings are exact, the 3D position is not " +
            "in the model: %1\$s",
    /**
     * Printed under the runs table when a run is priced but could not be swept; `%1$s` = labels.
     *
     * The sibling of [recoveredNote], for the other way a run stays out of the GLB: not lost
     * coordinates, but a spec or a node path the mesh generator refuses to build from.
     */
    val unbuildableNote: String =
        "No 3D geometry could be built for these runs - their lengths and fittings are still " +
            "counted: %1\$s",
    val footer: String = "ConstructAR %1\$s - generated on device",
    val pageLabel: String = "Page %1\$d",
)
