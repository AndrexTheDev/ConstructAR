package com.constructar.core.bom

import com.constructar.core.geo.RouteMetrics
import com.constructar.core.model.ConduitKind
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.ProjectRecord

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
    val bendAllowanceMeters: Float,
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
    val totalMeters: Float get() = lines.sumOf { it.totalMeters.toDouble() }.toFloat()
    val totalWithWasteMeters: Float get() = lines.sumOf { it.withWasteMeters.toDouble() }.toFloat()
    val totalClips: Int get() = lines.sumOf { it.clipCount }
    val totalCouplings: Int get() = lines.sumOf { it.couplingCount }
    val totalElbows: Int get() = lines.sumOf { it.elbow90Count + it.elbow45Count + it.otherBendCount }
    val isEmpty: Boolean get() = lines.isEmpty()

    fun metersFor(kind: ConduitKind): Float =
        lines.filter { it.kind == kind }.sumOf { it.totalMeters.toDouble() }.toFloat()
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
            val metrics = com.constructar.core.geo.PolylineMetrics.measure(route)
            if (!options.includeLowConfidenceRuns && metrics.lowConfidenceNodes > 0) continue
            val spec = route.spec
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
                bendAllowanceMeters = entries.sumOf { it.second.bendAllowanceMeters.toDouble() }.toFloat(),
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
