/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.relocalisation

import com.constructar.core.math.Vec3
import com.constructar.core.model.FeatureCluster
import com.constructar.core.model.RouteDraft
import com.constructar.core.model.RouteNodeRecord
import kotlin.math.abs
import kotlin.math.sqrt

/** One newly observed pose of a persisted route anchor. */
data class AnchorObservation(
    val nodeId: String,
    val position: Vec3,
    val normal: Vec3,
    val featureCluster: FeatureCluster,
)

/** Proper rigid motion (rotation + translation), with no scale component. */
data class RigidTransform3(val rotation: FloatArray, val translation: Vec3) {
    init {
        require(rotation.size == 9) { "A 3x3 rotation has nine entries" }
    }

    fun point(value: Vec3): Vec3 = Vec3(
        rotation[0] * value.x + rotation[1] * value.y + rotation[2] * value.z,
        rotation[3] * value.x + rotation[4] * value.y + rotation[5] * value.z,
        rotation[6] * value.x + rotation[7] * value.y + rotation[8] * value.z,
    ) + translation

    fun direction(value: Vec3): Vec3 = Vec3(
        rotation[0] * value.x + rotation[1] * value.y + rotation[2] * value.z,
        rotation[3] * value.x + rotation[4] * value.y + rotation[5] * value.z,
        rotation[6] * value.x + rotation[7] * value.y + rotation[8] * value.z,
    ).normalizedOrZero()
}

/** Offline, evidence-gated anchor-graph recovery using a least-squares Kabsch fit. */
object AnchorGraphRelocaliser {

    enum class Failure {
        INSUFFICIENT_OBSERVATIONS,
        INSUFFICIENT_EVIDENCE,
        DEGENERATE_GEOMETRY,
        FEATURE_MISMATCH,
        RESIDUAL_TOO_LARGE,
    }

    data class Config(
        val minimumObservations: Int = 3,
        val maximumResidualMeters: Float = 0.05f,
        val maximumFeatureResidualMeters: Float = 0.12f,
    )

    sealed interface Result {
        data class Success(
            val nodes: List<RouteNodeRecord>,
            val transform: RigidTransform3,
            val residualMeters: Float,
            val featureResidualMeters: Float,
        ) : Result

        data class Rejected(val reason: Failure, val detail: String) : Result
    }

    fun restore(
        draft: RouteDraft,
        observations: List<AnchorObservation>,
        newSessionId: String,
        config: Config = Config(),
    ): Result {
        val byId = draft.nodes.associateBy { it.id }
        val matches = observations.mapNotNull { observation ->
            byId[observation.nodeId]?.let { observation to it }
        }
        if (matches.size < config.minimumObservations || matches.map { it.first.nodeId }.toSet().size != matches.size) {
            return Result.Rejected(Failure.INSUFFICIENT_OBSERVATIONS, "Need three distinct persisted anchors")
        }
        if (matches.any { match ->
                val evidence = draft.evidence.firstOrNull { it.nodeId == match.second.id }
                (evidence?.featureCluster?.relativePoints?.size ?: 0) < 2 ||
                    match.first.featureCluster.relativePoints.size < 2
            }
        ) {
            return Result.Rejected(Failure.INSUFFICIENT_EVIDENCE, "Every matched anchor needs old and new feature clusters")
        }
        if (matches.any { !it.first.position.isFinite || !it.second.position.isFinite }) {
            return Result.Rejected(Failure.DEGENERATE_GEOMETRY, "Non-finite observation")
        }

        val sourceCentre = centroid(matches.map { it.second.position })
        val targetCentre = centroid(matches.map { it.first.position })
        // H = sum(target * source^T). With H = U S V^T, Kabsch's proper rotation is U V^T.
        val h = FloatArray(9)
        matches.forEach { (observation, old) ->
            val q = observation.position - targetCentre
            val p = old.position - sourceCentre
            for (row in 0..2) for (column in 0..2) {
                h[row * 3 + column] += q[row] * p[column]
            }
        }
        val v = eigenVectorsDesc(multiply(transpose(h), h))
            ?: return Result.Rejected(Failure.DEGENERATE_GEOMETRY, "Singular covariance")
        val singular = singularValues(h, v)
        if (singular[0] < 1e-7f || singular[1] < 1e-7f) {
            return Result.Rejected(Failure.DEGENERATE_GEOMETRY, "Matched anchors are collinear or coincident")
        }
        val v0 = column(v, 0).normalizedOrZero()
        val v1 = column(v, 1).normalizedOrZero()
        val v2 = (v0 cross v1).normalizedOrZero()
        val u0 = multiply(h, v0) * (1f / singular[0])
        val rawU1 = multiply(h, v1) * (1f / singular[1])
        // Re-orthogonalise in floating point before constructing the missing planar axis.
        val u1 = (rawU1 - u0 * (rawU1 dot u0)).normalizedOrZero()
        if (u0.isDegenerate(1e-6f) || u1.isDegenerate(1e-6f)) {
            return Result.Rejected(Failure.DEGENERATE_GEOMETRY, "No two independent anchor directions")
        }
        val u2 = (u0 cross u1).normalizedOrZero()
        val u = columns(u0.normalizedOrZero(), u1, u2)
        val properV = columns(v0, v1, v2)
        var rotation = multiply(u, transpose(properV))
        if (determinant(rotation) < 0f) {
            rotation = multiply(u, multiply(diagonal(1f, 1f, -1f), transpose(properV)))
        }
        val transform = RigidTransform3(rotation, targetCentre - rotate(rotation, sourceCentre))
        val squared = matches.sumOf { (observation, old) ->
            val error = transform.point(old.position) - observation.position
            error.lengthSquared.toDouble()
        }
        val residual = sqrt(squared / matches.size).toFloat()
        if (!residual.isFinite() || residual > config.maximumResidualMeters) {
            return Result.Rejected(Failure.RESIDUAL_TOO_LARGE, "Kabsch residual $residual m")
        }

        val featureResiduals = matches.map { (observation, old) ->
            val oldCluster = draft.evidence.first { it.nodeId == old.id }.featureCluster!!
            featureResidual(
                old = oldCluster.relativePoints.map { rotate(rotation, it) },
                new = observation.featureCluster.relativePoints,
            )
        }
        val featureResidual = featureResiduals.average().toFloat()
        if (!featureResidual.isFinite() || featureResiduals.any { it > config.maximumFeatureResidualMeters }) {
            return Result.Rejected(Failure.FEATURE_MISMATCH, "Visual clusters do not support this transform")
        }

        val confidenceFactor = (1f - residual / config.maximumResidualMeters).coerceIn(0f, 1f)
        val restored = draft.nodes.map { node ->
            node.copy(
                position = transform.point(node.position),
                normal = transform.direction(node.normal),
                anchorId = null,
                sessionId = newSessionId,
                confidence = (node.confidence * confidenceFactor).coerceIn(0f, 1f),
            )
        }
        return Result.Success(restored, transform, residual, featureResidual)
    }

    private fun featureResidual(old: List<Vec3>, new: List<Vec3>): Float {
        if (old.isEmpty() || new.isEmpty()) return Float.POSITIVE_INFINITY
        val forward = old.sumOf { point -> new.minOf { it.distanceTo(point).toDouble() } } / old.size
        val backward = new.sumOf { point -> old.minOf { it.distanceTo(point).toDouble() } } / new.size
        return ((forward + backward) * 0.5).toFloat()
    }

    private fun centroid(points: List<Vec3>): Vec3 = points.fold(Vec3.ZERO, Vec3::plus) * (1f / points.size)

    private fun singularValues(h: FloatArray, v: FloatArray): FloatArray = FloatArray(3) { index ->
        sqrt((multiply(h, column(v, index)).lengthSquared).coerceAtLeast(0f))
    }

    /** Jacobi diagonalisation of a symmetric 3x3 matrix; eigenvectors are returned by column. */
    private fun eigenVectorsDesc(input: FloatArray): FloatArray? {
        if (input.size != 9 || input.any { !it.isFinite() }) return null
        val a = input.copyOf()
        val vectors = diagonal(1f, 1f, 1f)
        repeat(48) {
            var p = 0
            var q = 1
            var largest = abs(a[1])
            if (abs(a[2]) > largest) { p = 0; q = 2; largest = abs(a[2]) }
            if (abs(a[5]) > largest) { p = 1; q = 2; largest = abs(a[5]) }
            if (largest < 1e-12f) return@repeat
            val app = a[p * 3 + p]
            val aqq = a[q * 3 + q]
            val apq = a[p * 3 + q]
            val angle = 0.5f * kotlin.math.atan2(2f * apq, aqq - app)
            val c = kotlin.math.cos(angle)
            val s = kotlin.math.sin(angle)
            for (k in 0..2) {
                if (k != p && k != q) {
                    val akp = a[k * 3 + p]
                    val akq = a[k * 3 + q]
                    val nextP = c * akp - s * akq
                    val nextQ = s * akp + c * akq
                    a[k * 3 + p] = nextP
                    a[p * 3 + k] = nextP
                    a[k * 3 + q] = nextQ
                    a[q * 3 + k] = nextQ
                }
                val vkp = vectors[k * 3 + p]
                val vkq = vectors[k * 3 + q]
                vectors[k * 3 + p] = c * vkp - s * vkq
                vectors[k * 3 + q] = s * vkp + c * vkq
            }
            a[p * 3 + p] = c * c * app - 2f * s * c * apq + s * s * aqq
            a[q * 3 + q] = s * s * app + 2f * s * c * apq + c * c * aqq
            a[p * 3 + q] = 0f
            a[q * 3 + p] = 0f
        }
        val order = (0..2).sortedByDescending { a[it * 3 + it] }
        return columns(column(vectors, order[0]), column(vectors, order[1]), column(vectors, order[2]))
    }

    private fun transpose(a: FloatArray) = FloatArray(9) { index -> a[(index % 3) * 3 + index / 3] }
    private fun diagonal(x: Float, y: Float, z: Float) = floatArrayOf(x, 0f, 0f, 0f, y, 0f, 0f, 0f, z)
    private fun column(a: FloatArray, index: Int) = Vec3(a[index], a[3 + index], a[6 + index])
    private fun columns(a: Vec3, b: Vec3, c: Vec3) = floatArrayOf(a.x, b.x, c.x, a.y, b.y, c.y, a.z, b.z, c.z)

    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(9) { index ->
        val row = index / 3
        val column = index % 3
        a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column]
    }

    private fun multiply(a: FloatArray, value: Vec3) = Vec3(
        a[0] * value.x + a[1] * value.y + a[2] * value.z,
        a[3] * value.x + a[4] * value.y + a[5] * value.z,
        a[6] * value.x + a[7] * value.y + a[8] * value.z,
    )

    private fun rotate(rotation: FloatArray, value: Vec3): Vec3 = multiply(rotation, value)

    private fun determinant(a: FloatArray): Float =
        a[0] * (a[4] * a[8] - a[5] * a[7]) -
            a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])
}
