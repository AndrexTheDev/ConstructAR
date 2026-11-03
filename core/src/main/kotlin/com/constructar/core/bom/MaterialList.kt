/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.bom

import com.constructar.core.geo.RouteMetrics
import com.constructar.core.model.ConduitKind
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.ProjectRecord
import com.constructar.core.units.LengthFormatter

/**
 * One procurement line. Aggregated per conduit type across every run in the project, so
 * the buyer sees "112 m of 16 mm cable" rather than fourteen separate runs.
 */
data class MaterialLine(
    val bomKey: String,
    val kind: ConduitKind,
    val spec: ConduitSpec,
    val runCount: Int,
    val totalMeters: Float,
    val withWasteMeters: Float,
    val wasteFactor: Float,
    val clipCount: Int,
    val couplingCount: Int,
    val elbow90Count: Int,
    val elbow45Count: Int,
    val otherBendCount: Int,
    val bendDeductionMeters: Float,
    /** Runs in this line whose measurement came from a soft hit source. */
    val lowConfidenceRuns: Int,
)

/** The complete bill of materials for a project. */
data class MaterialList(
    val projectId: String,
    val projectName: String,
    val lines: List<MaterialLine>,
    val generatedAtEpochMs: Long,
) {
    /**
     * The totals add up **the way the documents print them** - one line at a time, to the
     * centimetre.
     *
     * Every surface prints a row per article and a total under the rows (the screen's list, the
     * bill of materials, the order list, the protocol), and a reader with a calculator adds the
     * column. Summing the raw floats rounded once at the end did not give the number under the
     * column: three articles of 3.335 m print "3.34" each and a total of "10.01", and the operator
     * who adds the column in front of a wholesaler is holding a sheet that does not add up. The
     * same problem was fixed for minutes and money (findings 76 and 81) and the length column was
     * left behind (finding 154).
     *
     * [printedMeters] is the one place that decides, and it is *the formatter itself*, so the
     * arithmetic cannot drift from what is on the page by a half-centimetre boundary either.
     */
    val totalMeters: Float get() = lines.sumOf { printedMeters(it.totalMeters) }.toFloat()
    val totalWithWasteMeters: Float get() = lines.sumOf { printedMeters(it.withWasteMeters) }.toFloat()
    val totalClips: Int get() = lines.sumOf { it.clipCount }
    val totalCouplings: Int get() = lines.sumOf { it.couplingCount }
    /**
     * The bend count, per article - because a wholesaler orders articles, not bends.
     *
     * The totals used to exist only as one number. The order list, whose whole job is a purchase
     * order, printed it as a single "Bends" column: a line saying "4" was four 90° elbows, two 90°
     * and two 45°, or four bends nobody can identify - and the buyer cannot order any of them
     * without ringing the electrician (finding 90).
     */
    val totalElbow90: Int get() = lines.sumOf { it.elbow90Count }
    val totalElbow45: Int get() = lines.sumOf { it.elbow45Count }
    val totalOtherBends: Int get() = lines.sumOf { it.otherBendCount }
    val totalElbows: Int get() = totalElbow90 + totalElbow45 + totalOtherBends
    val isEmpty: Boolean get() = lines.isEmpty()

    /** The net metres of one trade, added up the same way the summary block prints them. */
    fun metersFor(kind: ConduitKind): Float =
        lines.filter { it.kind == kind }.sumOf { printedMeters(it.totalMeters) }.toFloat()

    companion object {
        /**
         * One length as the documents print it, back as a number.
         *
         * Deliberately a round trip through the formatter: `Math.round(x * 100)` and
         * `String.format("%.2f", x)` disagree exactly at the half-centimetre boundaries
         * (`1.005f` is `1.0049999952316284` as a double, which the formatter prints as `1.01` and
         * `Math.round` turns into `100`), so the only way for a total to be the sum of the printed
         * rows is to sum what the formatter returned.
         */
        internal fun printedMeters(meters: Float): Double {
            if (!meters.isFinite()) return 0.0
            return LengthFormatter.number(meters).toDoubleOrNull() ?: 0.0
        }
    }
}

/** Tunables for the estimate. Defaults follow common European site practice. */
data class MaterialListOptions(
    /** When null, each conduit family uses its own default waste factor. */
    val wasteFactorOverride: Float? = null,
    /** When null, each conduit family uses its own default clip spacing. */
    val clipSpacingMetersOverride: Float? = null,
    /** Include runs flagged as low confidence in the totals (default: yes, with a warning). */
    val includeLowConfidenceRuns: Boolean = true,
)

/**
 * Builds the bill of materials. This is the single source of truth for the CSV export,
 * the PDF protocol and the on-screen summary — so the numbers on the phone, in the PDF
 * and in the spreadsheet can never disagree.
 */
object MaterialListBuilder {

    fun build(
        project: ProjectRecord,
        options: MaterialListOptions = MaterialListOptions(),
        nowMs: Long = System.currentTimeMillis(),
    ): MaterialList {
        val groups = LinkedHashMap<String, MutableList<Pair<ConduitSpec, RouteMetrics>>>()

        for (route in project.routes) {
            if (!route.isRoutable) continue
            val spec = route.spec
            // Both overrides go into the measurement, not just into the line arithmetic below:
            // `clipSpacingMetersOverride` used to be documented, offered on this data class and
            // read by nobody, so every job was clipped at the family default (0.6 m cable,
            // 0.9 m pipe, 0.5 m duct) however the caller asked for it - the fittings are the
            // most numerous article on the order sheet (finding 113).
            val metrics = com.constructar.core.geo.PolylineMetrics.measure(
                route = route,
                wasteFactor = options.wasteFactorOverride
                    ?: com.constructar.core.geo.PolylineMetrics.defaultWasteFactor(spec),
                clipSpacingMeters = options.clipSpacingMetersOverride
                    ?: com.constructar.core.geo.PolylineMetrics.defaultClipSpacing(spec),
            )
            if (!options.includeLowConfidenceRuns && metrics.lowConfidenceNodes > 0) continue
            groups.getOrPut(spec.bomKey) { mutableListOf() }.add(spec to metrics)
        }

        val lines = groups.map { (key, entries) ->
            val spec = entries.first().first
            val waste = options.wasteFactorOverride
                ?: com.constructar.core.geo.PolylineMetrics.defaultWasteFactor(spec)
            val total = entries.sumOf { it.second.totalMeters.toDouble() }.toFloat()
            MaterialLine(
                bomKey = key,
                kind = spec.kind,
                spec = spec,
                runCount = entries.size,
                totalMeters = total,
                withWasteMeters = total * (1f + waste),
                wasteFactor = waste,
                clipCount = entries.sumOf { it.second.clipCount },
                couplingCount = entries.sumOf { it.second.couplingCount },
                elbow90Count = entries.sumOf { it.second.elbow90Count },
                elbow45Count = entries.sumOf { it.second.elbow45Count },
                otherBendCount = entries.sumOf { it.second.otherBendCount },
                bendDeductionMeters = entries.sumOf { it.second.bendDeductionMeters.toDouble() }.toFloat(),
                lowConfidenceRuns = entries.count { it.second.lowConfidenceNodes > 0 },
            )
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.spec.label }))

        return MaterialList(
            projectId = project.id,
            projectName = project.name,
            lines = lines,
            generatedAtEpochMs = nowMs,
        )
    }
}
