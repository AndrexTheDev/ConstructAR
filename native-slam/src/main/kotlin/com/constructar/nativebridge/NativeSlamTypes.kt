/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

import com.constructar.core.geo.DepthIntrinsics
import com.constructar.core.math.Aabb
import com.constructar.core.mesh.MeshData
import com.constructar.core.math.Vec3
import com.constructar.core.mesh.MeshBuilder

/**
 * TSDF tuning exposed to Kotlin. Mirrors `constructar::TsdfConfig`.
 *
 * Defaults are the ones validated on-device:
 *  * 2 cm voxels resolve cables and door reveals without melting a phone;
 *  * 4-voxel truncation (8 cm) tolerates ARCore depth noise of ±3 cm;
 *  * confidence ≥ 96 discards the raw-depth pixels ARCore itself marks as unreliable.
 */
data class TsdfConfig(
    val voxelSizeMeters: Float = 0.02f,
    val truncationVoxels: Float = 4f,
    val maxBlocks: Int = 160_000,
    /** 0 ⇒ native default (hardware_concurrency − 1). */
    val workerThreads: Int = 0,
    /** Raw-depth confidence floor, 0..255. */
    val minConfidence: Int = 96,
) {
    init {
        require(voxelSizeMeters in 0.005f..0.10f) { "voxelSizeMeters must be in 5..100 mm" }
        require(truncationVoxels >= 1f) { "truncationVoxels must be ≥ 1" }
    }

    /** Integration stride in pixels: 3 keeps 640×480 at ~21 k rays/frame. */
    val defaultPixelStep: Int get() = if (voxelSizeMeters >= 0.03f) 2 else 3
}

/** What one integration call did. */
data class IntegrationResult(
    val raysProcessed: Int,
    val voxelUpdates: Int,
    val blocksAllocated: Int,
    val blocksTotal: Int,
    val hitBlockBudget: Boolean,
    val milliseconds: Int,
    val voxelCount: Int,
    val truncationMillimeters: Int,
) {
    companion object {
        fun from(values: IntArray): IntegrationResult? {
            if (values.size < 8) return null
            return IntegrationResult(
                raysProcessed = values[0],
                voxelUpdates = values[1],
                blocksAllocated = values[2],
                blocksTotal = values[3],
                hitBlockBudget = values[4] != 0,
                milliseconds = values[5],
                voxelCount = values[6],
                truncationMillimeters = values[7],
            )
        }
    }
}

/**
 * A snapshot of the reconstructed surface, ready for both Filament and the exporters.
 *
 * The native layer hands back flat arrays; wrapping them in [MeshData] means the AR overlay
 * and a `.glb` export of the same scan are guaranteed to describe the same triangles.
 */
class ScanMesh(
    val mesh: MeshData,
    val extractionMillis: Int = 0,
) {
    val vertexCount: Int get() = mesh.vertexCount
    val triangleCount: Int get() = mesh.triangleCount
    val bounds: Aabb? get() = mesh.bounds()

    companion object {
        /**
         * @param payload `[vertexCount, triangleCount, positions…, normals…]` as produced by
         *                nativeExtractMesh.
         */
        fun fromPayload(payload: FloatArray, indices: IntArray): ScanMesh? {
            if (payload.size < 2) return null
            val vertexCount = payload[0].toInt()
            val triangleCount = payload[1].toInt()
            if (vertexCount <= 0 || triangleCount <= 0) return null
            val positionsStart = 2
            val normalsStart = positionsStart + vertexCount * 3
            if (payload.size < normalsStart + vertexCount * 3 || indices.size < triangleCount * 3) {
                return null
            }
            val positions = payload.copyOfRange(positionsStart, positionsStart + vertexCount * 3)
            val normals = payload.copyOfRange(normalsStart, normalsStart + vertexCount * 3)
            return ScanMesh(MeshData(positions, normals, null, indices.copyOf(triangleCount * 3)))
        }
    }
}

/** Result of a pose-graph optimisation, in Kotlin-friendly form. */
data class PoseGraphResult(
    val initialCost: Float,
    val finalCost: Float,
    val rmsTranslationMeters: Float,
    val rmsRotationRadians: Float,
    val iterations: Int,
    val milliseconds: Float,
    /** Column-major camera→world matrices, one per node. */
    val poses: List<FloatArray>,
) {
    val converged: Boolean get() = iterations > 0 && finalCost <= initialCost

    companion object {
        fun from(values: FloatArray): PoseGraphResult? {
            if (values.size < 6) return null
            val poseCount = (values.size - 6) / 16
            val poses = ArrayList<FloatArray>(poseCount)
            for (i in 0 until poseCount) {
                val offset = 6 + i * 16
                if (offset + 16 > values.size) break
                poses.add(values.copyOfRange(offset, offset + 16))
            }
            return PoseGraphResult(
                initialCost = values[0],
                finalCost = values[1],
                rmsTranslationMeters = values[2],
                rmsRotationRadians = values[3],
                iterations = values[4].toInt(),
                milliseconds = values[5],
                poses = poses,
            )
        }
    }
}

/** A proposed loop closure (not yet trusted — RTAB-Map or the operator must confirm it). */
data class LoopCandidate(val from: Int, val to: Int, val distanceMeters: Float)

/** Converts a native scan mesh into a Filament-ready vertex/index stream (see :ar-engine). */
fun ScanMesh.toMeshBuilder(create: () -> MeshBuilder = { MeshBuilder(vertexCount) }): MeshBuilder {
    val builder = create()
    for (vertex in 0 until mesh.vertexCount) {
        builder.addVertex(mesh.positionAt(vertex), mesh.normalAt(vertex), mesh.uvAt(vertex))
    }
    var i = 0
    while (i + 2 < mesh.indices.size) {
        builder.addTriangle(mesh.indices[i], mesh.indices[i + 1], mesh.indices[i + 2])
        i += 3
    }
    return builder
}

/** Bounds helper for the scan summary (`ScanSummary.fromBounds`). */
fun ScanMesh.toAabbOrNull(): Aabb? = mesh.bounds()

/** Convenience: centroid of the reconstructed surface — used to aim the HUD camera. */
fun ScanMesh.centroid(): Vec3 = bounds?.center ?: Vec3.ZERO
