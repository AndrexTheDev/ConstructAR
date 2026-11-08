/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.arengine.depth.DepthFrameSnapshot
import com.constructar.arengine.depth.DepthSource
import com.constructar.core.geo.DepthIntrinsics

/**
 * A densified depth image together with the acquisition geometry of its **source** frame.
 *
 * Inference is asynchronous. The newest ARCore snapshot at integration time is usually several
 * frames newer than the image Depth Anything actually saw, so borrowing that newer snapshot's
 * camera pose would project correct pixels into the wrong place. These values are copied when
 * the result is submitted and thereafter travel as one immutable unit.
 */
data class FusedDepthFrame(
    val depthMm: ShortArray,
    /** Tightly packed integration confidence: measured ARCore value, fill floor, or zero for a hole. */
    val confidence: ByteArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val intrinsics: DepthIntrinsics,
    /** Camera→world of the frame used for inference, column-major. */
    val cameraToWorld: FloatArray,
    /** Monotonic clock at result submission, used only for the bounded result lifetime. */
    val receivedAtMillis: Long,
    val depthTimestampNs: Long = timestampNs,
    val source: DepthSource = DepthSource.FULL,
) {
    init {
        require(width > 0 && height > 0) { "fused depth dimensions must be positive" }
        require(depthMm.size >= width * height) { "fused depth buffer is shorter than width×height" }
        require(confidence.size >= width * height) { "fused confidence is shorter than width×height" }
        require(cameraToWorld.size == 16) { "cameraToWorld must contain 16 values" }
    }
}

/** All acquisition-dependent inputs of one native integration call. */
internal data class DepthIntegrationSelection(
    val depthMm: ShortArray,
    val width: Int,
    val height: Int,
    val rowStrideElements: Int,
    val confidence: ByteArray?,
    val confidenceWidth: Int,
    val confidenceHeight: Int,
    val confidenceRowStride: Int,
    val intrinsics: DepthIntrinsics,
    val cameraToWorld: FloatArray,
    val timestampNs: Long,
    val usesFusedDepth: Boolean,
    val depthTimestampNs: Long,
    val source: DepthSource,
    /** Null when no fresh fused candidate existed; zero comparisons is not a verification claim. */
    val temporalReport: TemporalConsistencyReport? = null,
)

/** Selects a fresh, dimension-matching fused result only while new measured evidence does not contradict it. */
internal object DepthIntegrationSelector {
    fun select(
        snapshot: DepthFrameSnapshot,
        fused: FusedDepthFrame?,
        nowMillis: Long,
        fusionTtlMillis: Long,
        minConfidence: Int = 96,
        temporalPolicy: TemporalConsistencyPolicy = TemporalConsistencyPolicy(),
        /** Explicit deterministic A/B control; production integration keeps the guard enabled. */
        temporalGuardEnabled: Boolean = true,
    ): DepthIntegrationSelection {
        if (fused != null) {
            val age = nowMillis - fused.receivedAtMillis
            val fresh = age >= 0L && age < fusionTtlMillis
            if (fused.width == snapshot.width && fused.height == snapshot.height && fresh) {
                val temporalReport = if (temporalGuardEnabled) {
                    TemporalDepthConsistency.evaluate(
                        fused = fused,
                        current = snapshot,
                        minConfidence = minConfidence,
                        policy = temporalPolicy,
                    )
                } else {
                    null
                }
                if (temporalReport?.rejected != true) {
                    return DepthIntegrationSelection(
                        depthMm = fused.depthMm,
                        width = fused.width,
                        height = fused.height,
                        // AiDepthFusion emits a tightly packed buffer, independent of the camera's stride.
                        rowStrideElements = fused.width,
                        confidence = fused.confidence,
                        confidenceWidth = fused.width,
                        confidenceHeight = fused.height,
                        confidenceRowStride = fused.width,
                        intrinsics = fused.intrinsics,
                        cameraToWorld = fused.cameraToWorld,
                        timestampNs = fused.timestampNs,
                        usesFusedDepth = true,
                        depthTimestampNs = fused.depthTimestampNs,
                        source = fused.source,
                        temporalReport = temporalReport,
                    )
                }
                return currentAcquisition(snapshot, temporalReport)
            }
        }
        return currentAcquisition(snapshot)
    }

    private fun currentAcquisition(
        snapshot: DepthFrameSnapshot,
        temporalReport: TemporalConsistencyReport? = null,
    ) = DepthIntegrationSelection(
        depthMm = snapshot.depthMm,
        width = snapshot.width,
        height = snapshot.height,
        rowStrideElements = snapshot.rowStride,
        confidence = snapshot.confidence,
        confidenceWidth = snapshot.confidenceWidth,
        confidenceHeight = snapshot.confidenceHeight,
        confidenceRowStride = snapshot.confidenceRowStride,
        intrinsics = snapshot.intrinsics,
        cameraToWorld = snapshot.cameraToWorld,
        timestampNs = snapshot.timestampNs,
        usesFusedDepth = false,
        depthTimestampNs = snapshot.depthTimestampNs,
        source = snapshot.source,
        temporalReport = temporalReport,
    )
}
