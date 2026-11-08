/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.arengine.depth.DepthFrameSnapshot
import kotlin.math.abs
import kotlin.math.max

/** Bounded sampling and conservative disagreement gates for an asynchronous fused depth result. */
internal data class TemporalConsistencyPolicy(
    val sampleStride: Int = 8,
    val minComparisons: Int = 6,
    val minConflicts: Int = 3,
    val maxConflictRatio: Float = 0.20f,
    val absoluteToleranceMeters: Float = 0.08f,
    val relativeTolerance: Float = 0.03f,
) {
    init {
        require(sampleStride > 0) { "temporal sample stride must be positive" }
        require(minComparisons > 0 && minConflicts > 0) { "temporal evidence counts must be positive" }
        require(maxConflictRatio.isFinite() && maxConflictRatio in 0f..1f) { "invalid conflict ratio" }
        require(absoluteToleranceMeters.isFinite() && absoluteToleranceMeters > 0f) { "invalid absolute tolerance" }
        require(relativeTolerance.isFinite() && relativeTolerance >= 0f) { "invalid relative tolerance" }
    }
}

internal enum class TemporalRejectionReason { INVALID_GEOMETRY, CONTRADICTED }

/** A report describes only measured comparisons; zero comparisons never means "temporally verified". */
internal data class TemporalConsistencyReport(
    val comparedSamples: Int,
    val conflictingSamples: Int,
    val reason: TemporalRejectionReason? = null,
) {
    val rejected: Boolean get() = reason != null
    val conflictRatio: Float
        get() = if (comparedSamples == 0) 0f else conflictingSamples.toFloat() / comparedSamples
}

/**
 * Reprojects a fused source point into the newest ARCore camera before comparing metric depth.
 * No pixel arrays or world-point collections are allocated, and sampling is bounded to 32×24 cells.
 */
internal object TemporalDepthConsistency {
    fun evaluate(
        fused: FusedDepthFrame,
        current: DepthFrameSnapshot,
        minConfidence: Int,
        policy: TemporalConsistencyPolicy,
    ): TemporalConsistencyReport {
        if (!fused.intrinsics.isFiniteAndPositive() || !current.intrinsics.isFiniteAndPositive() ||
            !fused.cameraToWorld.isFinitePose() || !current.cameraToWorld.isFinitePose() ||
            fused.width != current.width || fused.height != current.height
        ) {
            return TemporalConsistencyReport(0, 0, TemporalRejectionReason.INVALID_GEOMETRY)
        }

        val stride = maxOf(policy.sampleStride, (fused.width + 31) / 32, (fused.height + 23) / 24)
        val acceptanceConfidence = minConfidence.coerceIn(0, 255)
        val sourcePose = fused.cameraToWorld
        val currentPose = current.cameraToWorld
        var compared = 0
        var conflicts = 0

        for (y in 0 until fused.height step stride) {
            for (x in 0 until fused.width step stride) {
                val index = y * fused.width + x
                val sourceDepth = (fused.depthMm[index].toInt() and 0xFFFF) / 1000f
                if (sourceDepth <= 0f) continue
                val sourceConfidence = fused.confidence[index].toInt() and 0xFF
                if (sourceConfidence < acceptanceConfidence) continue

                // Same convention as DepthImageMath.unproject: +Y up, -Z forward. Keeping the
                // arithmetic scalar avoids hundreds of short-lived Vec3 instances per integration.
                val sourceX = (x + 0.5f - fused.intrinsics.cx) / fused.intrinsics.fx * sourceDepth
                val sourceY = -(y + 0.5f - fused.intrinsics.cy) / fused.intrinsics.fy * sourceDepth
                val sourceZ = -sourceDepth
                val worldX = sourcePose[0] * sourceX + sourcePose[4] * sourceY + sourcePose[8] * sourceZ + sourcePose[12]
                val worldY = sourcePose[1] * sourceX + sourcePose[5] * sourceY + sourcePose[9] * sourceZ + sourcePose[13]
                val worldZ = sourcePose[2] * sourceX + sourcePose[6] * sourceY + sourcePose[10] * sourceZ + sourcePose[14]

                // Inverse of a rigid camera→world transform: Rᵀ(world − translation).
                val dx = worldX - currentPose[12]
                val dy = worldY - currentPose[13]
                val dz = worldZ - currentPose[14]
                val currentX = currentPose[0] * dx + currentPose[1] * dy + currentPose[2] * dz
                val currentY = currentPose[4] * dx + currentPose[5] * dy + currentPose[6] * dz
                val currentZ = currentPose[8] * dx + currentPose[9] * dy + currentPose[10] * dz
                val expectedDepth = -currentZ
                if (!expectedDepth.isFinite() || expectedDepth <= 0.05f) continue

                val projectedX = current.intrinsics.fx * currentX / expectedDepth + current.intrinsics.cx
                val projectedY = -current.intrinsics.fy * currentY / expectedDepth + current.intrinsics.cy
                if (!projectedX.isFinite() || !projectedY.isFinite() ||
                    projectedX < 0f || projectedY < 0f ||
                    projectedX >= current.width || projectedY >= current.height
                ) {
                    continue
                }

                val pixelX = projectedX.toInt()
                val pixelY = projectedY.toInt()
                val measuredDepth = current.depthMetersAt(pixelX, pixelY) ?: continue
                if (current.confidenceAt(pixelX, pixelY) < acceptanceConfidence) continue
                compared++
                val tolerance = max(
                    policy.absoluteToleranceMeters,
                    policy.relativeTolerance * expectedDepth,
                )
                // Both directions matter: a new nearer object occludes the old hypothesis, while
                // a farther measurement can reveal that the old foreground object has gone away.
                if (abs(measuredDepth - expectedDepth) > tolerance) conflicts++
            }
        }

        val rejected = compared >= policy.minComparisons && conflicts >= policy.minConflicts &&
            conflicts.toFloat() / compared > policy.maxConflictRatio
        return TemporalConsistencyReport(
            comparedSamples = compared,
            conflictingSamples = conflicts,
            reason = if (rejected) TemporalRejectionReason.CONTRADICTED else null,
        )
    }

    private fun com.constructar.core.geo.DepthIntrinsics.isFiniteAndPositive(): Boolean =
        fx.isFinite() && fy.isFinite() && cx.isFinite() && cy.isFinite() && fx > 0f && fy > 0f

    private fun FloatArray.isFinitePose(): Boolean = size == 16 && all { it.isFinite() }
}
