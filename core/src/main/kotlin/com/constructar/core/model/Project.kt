package com.constructar.core.model

import com.constructar.core.math.Aabb
import kotlinx.serialization.Serializable

/**
 * Statistics produced by the scan pipeline (TSDF in `:native-slam`) and attached to a
 * project for the protocol header and the BIM export metadata.
 */
@Serializable
data class ScanSummary(
    val meshVertexCount: Int = 0,
    val meshTriangleCount: Int = 0,
    /** Horizon-normalised floor area in m² — "how much wall/ceiling we actually saw". */
    val floorAreaSquareMeters: Float = 0f,
    /** Free ceiling height derived from the floor/ceiling plane pair, metres. */
    val ceilingHeightMeters: Float = 0f,
    /** Bounding box of the reconstructed volume, metres (origin = AR world origin). */
    val extentMin: List<Float> = emptyList(),
    val extentMax: List<Float> = emptyList(),
    val voxelSizeMeters: Float = 0f,
    /** Loop closures accepted by the SLAM back-end during this scan. */
    val loopClosures: Int = 0,
    /** Final RMS residual of the pose-graph optimisation, metres. */
    val poseGraphRmsMeters: Float = 0f,
    /** Fraction of the depth buffer that passed the confidence filter, 0..1. */
    val depthCoverage: Float = 0f,
) {
    val volumeCubicMeters: Float
        get() = if (extentMin.size == 3 && extentMax.size == 3) {
            val dx = extentMax[0] - extentMin[0]
            val dy = extentMax[1] - extentMin[1]
            val dz = extentMax[2] - extentMin[2]
            (dx * dy * dz).coerceAtLeast(0f)
        } else {
            0f
        }

    companion object {
        fun fromBounds(bounds: Aabb?, voxelSizeMeters: Float): ScanSummary = if (bounds == null) {
            ScanSummary(voxelSizeMeters = voxelSizeMeters)
        } else {
            ScanSummary(
                extentMin = listOf(bounds.min.x, bounds.min.y, bounds.min.z),
                extentMax = listOf(bounds.max.x, bounds.max.y, bounds.max.z),
                voxelSizeMeters = voxelSizeMeters,
            )
        }
    }
}

/**
 * The on-device project: one room (or one zone) of one site.
 *
 * Everything here is plain data and `@Serializable`, so a project is a single JSON file
 * in app-private storage. No database, no network, no account.
 */
@Serializable
data class ProjectRecord(
    val id: String,
    val name: String,
    val siteName: String = "",
    val technician: String = "",
    val notes: String = "",
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val routes: List<ConduitRoute> = emptyList(),
    val scan: ScanSummary? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val totalLengthMeters: Float
        get() = routes.sumOf { it.polylineLengthMeters.toDouble() }.toFloat()

    fun routeById(routeId: String): ConduitRoute? = routes.firstOrNull { it.id == routeId }

    fun upsertRoute(route: ConduitRoute): ProjectRecord = copy(
        routes = if (routes.any { it.id == route.id }) {
            routes.map { if (it.id == route.id) route else it }
        } else {
            routes + route
        },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    fun removeRoute(routeId: String): ProjectRecord = copy(
        routes = routes.filterNot { it.id == routeId },
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    companion object {
        /** Bump when the JSON shape changes; migrations live in `ProjectMigrations`. */
        const val SCHEMA_VERSION: Int = 1

        fun empty(id: String, name: String, nowMs: Long = System.currentTimeMillis()) = ProjectRecord(
            id = id,
            name = name,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs,
        )
    }
}
