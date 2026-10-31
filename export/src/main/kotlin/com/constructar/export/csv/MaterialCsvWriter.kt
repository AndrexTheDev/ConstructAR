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

        val byKind = ConduitKind.entries.mapNotNull { kind ->
            val meters = materialList.metersFor(kind)
            if (meters <= 0f) null else kindLabel(kind) to meters
        }
        if (byKind.isNotEmpty()) {
            sb.append(lineEnding)
            row(sb, sep, listOf("Summary by trade", ""))
            byKind.forEach { (label, meters) -> row(sb, sep, listOf(label, number(meters) + " m")) }
        }

        if (options.includeRunDetail && runDetails.isNotEmpty()) {
            sb.append(lineEnding)
            row(sb, sep, listOf("Run", "Specification", "Length (m)"))
            runDetails.forEach { (label, spec, meters) -> row(sb, sep, listOf(label, spec, number(meters))) }
        }
        return sb.toString()
    }

    /** Field quoting per RFC 4180: double quotes, doubled internal quotes. */
    fun escape(value: String, separator: Char = ';'): String {
        val needsQuotes = value.any { it == separator || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
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
