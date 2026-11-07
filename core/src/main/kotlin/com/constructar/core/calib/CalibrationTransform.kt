/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import com.constructar.core.math.Aabb
import com.constructar.core.math.Mat4
import com.constructar.core.math.Vec3
import com.constructar.core.mesh.MeshData
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SketchPoint

/**
 * The only raw-point calibration boundary: `p_calibrated = k_calib × p_raw`.
 *
 * Persisted routes remain raw ARCore evidence so calibration can be corrected later. Every AR
 * renderer, material calculator and GLB/PLY exporter receives a transformed copy from here. A
 * malformed imported factor fails closed to identity rather than producing NaN geometry.
 */
class CalibrationTransform private constructor(private val scale: Double) {
    private val pointScale: Float get() = scale.toFloat()

    fun point(raw: Vec3): Vec3 = raw * pointScale
    fun distance(rawMeters: Float): Float = (rawMeters.toDouble() * scale).toFloat()
    fun bounds(raw: Aabb): Aabb = Aabb(point(raw.min), point(raw.max))

    fun sketch(raw: FloorplanSketch): FloorplanSketch = raw.copy(
        points = raw.points.map { SketchPoint(it.xMeters * pointScale, it.zMeters * pointScale) },
    )

    fun mesh(raw: MeshData): MeshData = if (scale == 1.0) {
        raw
    } else {
        raw.transformed(Mat4.scale(Vec3(pointScale, pointScale, pointScale)))
    }

    fun nodes(raw: List<RouteNodeRecord>): List<RouteNodeRecord> =
        raw.map { node -> node.copy(position = point(node.position)) }

    fun route(raw: ConduitRoute): ConduitRoute = if (scale == 1.0) {
        raw
    } else {
        raw.copy(
            nodes = nodes(raw.nodes),
            evidence = raw.evidence.map { evidence ->
                evidence.copy(
                    relativeVectorMeters = evidence.relativeVectorMeters?.let(::point),
                    featureCluster = evidence.featureCluster?.let { cluster ->
                        cluster.copy(relativePoints = cluster.relativePoints.map(::point))
                    },
                )
            },
        )
    }

    fun routes(raw: List<ConduitRoute>): List<ConduitRoute> = map(raw)

    private fun map(raw: List<ConduitRoute>): List<ConduitRoute> =
        if (scale == 1.0) raw else raw.map(::route)

    companion object {
        val IDENTITY = CalibrationTransform(1.0)

        fun of(scale: Double): CalibrationTransform {
            val pointScale = scale.toFloat()
            return if (scale.isFinite() && scale > 0.0 && pointScale.isFinite() && pointScale > 0f) {
                CalibrationTransform(scale)
            } else {
                IDENTITY
            }
        }
    }
}
