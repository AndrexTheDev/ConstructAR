/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.csv

import com.constructar.core.bom.MaterialList
import com.constructar.core.model.ConduitKind
import java.util.Locale

/**
 * CSV writer for the bill of materials.
 *
 * Two details that matter in practice and are easy to get wrong:
 *  * **Semicolons, not commas, are the list separator in most European Excel installs.**
 *    A comma-separated file opens as one column on a German or Spanish machine. The separator
 *    is therefore configurable and defaults to `;`.
 *  * **Numbers use `.` as the decimal separator** regardless of locale (RFC 4180 + Excel's
 *    expectation for CSV), so the file is machine-readable everywhere. `Locale.ROOT` is used
 *    explicitly — the default locale would write `14,82` and break the column alignment.
 *
 * A UTF-8 BOM is prepended by default so Excel shows `Ø`/`mm²` correctly.
 *
 * Headers and trade names stay English, for the same reason as the decimal point: this file is
 * data for a spreadsheet, not a document for the customer. The protocol (PDF) is the localised
 * one — it carries all six languages, and it is the file that gets signed.
 */
object MaterialCsvWriter {

    data class Options(
        /** `;` for European Excel, `,` for US Excel and most BI tools. */
        val separator: Char = ';',
        val includeBom: Boolean = true,
        /** Adds the per-run breakdown below the aggregate lines. */
        val includeRunDetail: Boolean = false,
    )

    /**
     * Writes the aggregated procurement list.
     *
     * @param runDetails optional `(label, spec, meters)` triples for the detail block.
     */
    fun write(
        materialList: MaterialList,
        options: Options = Options(),
        generatedAtLabel: String = "",
        runDetails: List<Triple<String, String, Float>> = emptyList(),
    ): String {
        val sb = StringBuilder(1024)
        if (options.includeBom) sb.append('\uFEFF')
        val sep = options.separator

        row(sb, sep, listOf("Project", materialList.projectName))
        row(sb, sep, listOf("Material list generated", generatedAtLabel.ifBlank { materialList.generatedAtEpochMs.toString() }))
        row(sb, sep, listOf("Total metres (net)", number(materialList.totalMeters)))
        row(sb, sep, listOf("Total metres (incl. waste)", number(materialList.totalWithWasteMeters)))
        row(sb, sep, listOf("Total clips", materialList.totalClips.toString()))
        row(sb, sep, listOf("Total couplings", materialList.totalCouplings.toString()))
        row(sb, sep, listOf("Total bends", materialList.totalElbows.toString()))
        sb.append(lineEnding)
        sb.append(lineEnding)

        row(
            sb, sep,
            listOf(
                "Kind", "Specification", "Material", "Runs", "Net length (m)",
                "Waste (%)", "Order length (m)", "Clips", "Couplings", "90° elbows",
                "45° elbows", "Other bends", "Low-confidence runs",
            ),
        )

        for (line in materialList.lines) {
            row(
                sb, sep,
                listOf(
                    kindLabel(line.kind),
                    line.spec.sizeLabel,
                    line.spec.material,
                    line.runCount.toString(),
                    number(line.totalMeters),
                    number(line.wasteFactor * 100f),
                    number(line.withWasteMeters),
                    line.clipCount.toString(),
                    line.couplingCount.toString(),
                    line.elbow90Count.toString(),
                    line.elbow45Count.toString(),
                    line.otherBendCount.toString(),
                    line.lowConfidenceRuns.toString(),
                ),
            )
        }

        // Both lengths per trade, and a name over each of them.
        //
        // This block used to print `label; 112.34 m` under a header row whose second cell was
        // empty, and the figure was the *net* metres - in a file whose top block distinguishes
        // "Total metres (net)" from "Total metres (incl. waste)" and whose line table has both a
        // net and an order column. It was the only length in the document that did not say which
        // of the two it was, and it answered the question the file exists to answer ("how much
        // cable altogether") with the number the buyer does not order by (finding 155). The label
        // and the net figure keep their positions, so a reader of the two old columns is
        // unaffected; the order length is new, at the end.
        val byKind = ConduitKind.entries.mapNotNull { kind ->
            val meters = materialList.metersFor(kind)
            if (meters <= 0f) null else Triple(kindLabel(kind), meters, materialList.orderMetersFor(kind))
        }
        if (byKind.isNotEmpty()) {
            sb.append(lineEnding)
            row(sb, sep, listOf("Summary by trade", "Net length (m)", "Order length (m)"))
            byKind.forEach { (label, meters, orderMeters) ->
                row(sb, sep, listOf(label, number(meters), number(orderMeters)))
            }
        }

        if (options.includeRunDetail && runDetails.isNotEmpty()) {
            sb.append(lineEnding)
            row(sb, sep, listOf("Run", "Specification", "Length (m)"))
            runDetails.forEach { (label, spec, meters) -> row(sb, sep, listOf(label, spec, number(meters))) }
        }
        return sb.toString()
    }

    /**
     * The order list: one line per conduit type, nothing else.
     *
     * The full bill of materials is a document the operator reads - summary, every column, a
     * per-run breakdown. The wholesaler (or the shop's buyer) needs the other thing: article,
     * how much of it to order, and the fittings that go with it. This writes exactly that, so
     * nobody has to delete eleven columns before mailing it, and it is a separate file rather
     * than another mode of the same one because both documents are wanted on the same job.
     *
     * Same separator, same dot decimals, same BOM and same formula guard as [write]: a second
     * convention in the same folder is how a spreadsheet ends up half-readable.
     */
    fun writeOrderList(
        materialList: MaterialList,
        options: Options = Options(),
        generatedAtLabel: String = "",
    ): String {
        val sb = StringBuilder(512)
        if (options.includeBom) sb.append('\uFEFF')
        val sep = options.separator

        row(sb, sep, listOf("Project", materialList.projectName))
        row(
            sb, sep,
            listOf("Order list", generatedAtLabel.ifBlank { materialList.generatedAtEpochMs.toString() }),
        )
        sb.append(lineEnding)
        // One column per bend article, not one for "bends": the buyer orders 90° elbows and 45°
        // elbows as different articles, and a single total told him only that there were four of
        // something. The full bill of materials split them from the start; the file written *for*
        // the wholesaler was the one that did not (finding 90).
        // One column per article on the left, the arithmetic that produced it on the right.
        //
        // The order length is the only number on this sheet that is not a measurement: it is the
        // route plus the waste factor, and the operator can now choose that factor (finding 151).
        // The screen states the pair, the protocol has a net and a waste column, the full CSV has
        // both - and the one document that leaves the shop printed the total alone, so 82 m on a
        // 77 m job read as an error to the fitter holding the sheet in the corridor (finding 152).
        // The two columns go at the end rather than beside the order length: `orderNote` promises
        // that a reader of the seven original columns is unaffected, and an importer that reads
        // them by position is the normal case for this file.
        row(
            sb, sep,
            listOf(
                "Article", "Order length (m)", "Clips", "Couplings",
                "90° elbows", "45° elbows", "Other bends",
                "Net length (m)", "Waste (%)",
            ),
        )
        for (line in materialList.lines) {
            // The article is the procureable description; a spec without one falls back to the
            // size label, exactly as the protocol's procurement table does.
            row(
                sb, sep,
                listOf(
                    line.spec.material.ifBlank { line.spec.sizeLabel },
                    number(line.withWasteMeters),
                    line.clipCount.toString(),
                    line.couplingCount.toString(),
                    line.elbow90Count.toString(),
                    line.elbow45Count.toString(),
                    line.otherBendCount.toString(),
                    number(line.totalMeters),
                    number(line.wasteFactor * 100f),
                ),
            )
        }
        if (!materialList.isEmpty) {
            sb.append(lineEnding)
            row(
                sb, sep,
                listOf(
                    "Total",
                    number(materialList.totalWithWasteMeters),
                    materialList.totalClips.toString(),
                    materialList.totalCouplings.toString(),
                    materialList.totalElbow90.toString(),
                    materialList.totalElbow45.toString(),
                    materialList.totalOtherBends.toString(),
                    number(materialList.totalMeters),
                    number(blendedWastePercent(materialList)),
                ),
            )
        }
        val uncertain = materialList.lines.sumOf { it.lowConfidenceRuns }
        if (uncertain > 0) {
            sb.append(lineEnding)
            row(sb, sep, listOf("Note", orderNote(uncertain)))
        }
        // A list built with the uncertain runs *excluded* is not a list with nothing to warn
        // about: it is a list that is missing them. The note above counts what survived, so on
        // such a list it printed nothing at all and the sheet read as if every length had been
        // checked (finding 161).
        if (materialList.excludedLowConfidenceRuns > 0) {
            sb.append(lineEnding)
            row(
                sb,
                sep,
                listOf(
                    "Note",
                    excludedNote(
                        materialList.excludedLowConfidenceRuns,
                        materialList.excludedLowConfidenceMeters,
                    ),
                ),
            )
        }
        return sb.toString()
    }

    /**
     * The waste percentage the total row implies, once the trades are mixed.
     *
     * Cable takes 7 % and duct 10 %, so a line-by-line factor cannot be printed in a single total
     * cell - the sheet would be off by the mix. This is the ratio the two totals actually hold,
     * which is the only figure that reconciles: `net * (1 + waste/100) == order`.
     */
    internal fun blendedWastePercent(materialList: MaterialList): Float {
        val net = materialList.totalMeters
        if (net <= 0f) return 0f
        return (materialList.totalWithWasteMeters - net) / net * 100f
    }

    /**
     * The line under the order list when part of it rests on a hit that was not fully trusted.
     *
     * The screen says it (`materials_low_confidence`), the bill of materials carries it as a
     * column, and the protocol carries the flag per run - but the order list, the one document
     * that turns a measurement into money at the wholesaler's counter, said nothing at all about
     * it. A run whose points came from a soft hit is a length that may be short by centimetres,
     * and the buyer had no way to know which of the articles on the sheet were affected
     * (finding 105). The note is one row under the total rather than an eighth column, so an
     * importer reading the seven columns it was built for keeps working.
     */
    internal fun orderNote(uncertainRuns: Int): String =
        "runs with an uncertain hit: $uncertainRuns - check those lengths before ordering"

    /**
     * The line under the order list when runs were left out of it.
     *
     * The figure has to be on the sheet: an order list is what the wholesaler is asked to deliver,
     * and "these metres are not in the totals" is the difference between a job that was measured
     * and a job that was measured except for the part nobody has checked yet (finding 161).
     */
    internal fun excludedNote(excludedRuns: Int, excludedMeters: Float): String =
        "runs left out as too uncertain: $excludedRuns (" + number(excludedMeters) +
            " m) - the totals above do not include them"

    /**
     * Field quoting per RFC 4180: double quotes, doubled internal quotes — plus a formula guard.
     *
     * A cell that begins `=`, `+`, `@`, a tab or a carriage return is a formula to Excel and
     * LibreOffice, not text. The names in this file are the operator's own (a room, a conduit
     * label) and they also arrive from **restored backups written by someone else**, so a project
     * called `=HYPERLINK("http://…","Rechnung")` runs on the foreman's machine the moment he opens
     * the list to order material. Quoting alone does not stop that — the quotes are stripped by
     * the parser before the cell is evaluated — so the cell is additionally prefixed with an
     * apostrophe, the spreadsheet's own "this is text" marker.
     *
     * A leading minus is only guarded when what follows is not a plain number: `-5.00` is a
     * value, `-2+3+cmd|' /C calc'!A0` is a formula.
     */
    fun escape(value: String, separator: Char = ';'): String {
        val guarded = if (needsFormulaGuard(value)) "'" + value else value
        val needsQuotes = guarded.any { it == separator || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + guarded.replace("\"", "\"\"") + "\"" else guarded
    }

    /** Whether a spreadsheet would evaluate this cell instead of displaying it. */
    internal fun needsFormulaGuard(value: String): Boolean = when (value.firstOrNull()) {
        '=', '+', '@', '\t', '\r' -> true
        '-' -> value.toDoubleOrNull() == null
        else -> false
    }

    private fun row(sb: StringBuilder, separator: Char, values: List<String>) {
        values.forEachIndexed { index, value ->
            if (index > 0) sb.append(separator)
            sb.append(escape(value, separator))
        }
        sb.append(lineEnding)
    }

    /** Two decimals, always with a dot — Excel-friendly and locale-independent. */
    fun number(value: Float): String =
        if (value.isFinite()) String.format(Locale.ROOT, "%.2f", value) else "0.00"

    fun kindLabel(kind: ConduitKind): String = when (kind) {
        ConduitKind.CABLE -> "Cable"
        ConduitKind.PIPE -> "Pipe"
        ConduitKind.DUCT -> "Duct / channel"
    }

    /** CRLF: the only line ending Excel on Windows handles without warnings. */
    private const val lineEnding = "\r\n"
}
