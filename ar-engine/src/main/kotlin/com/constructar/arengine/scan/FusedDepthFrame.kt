/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.arengine.depth.DepthFrameSnapshot
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
)

/** Selects a fused result only while its dimensions and bounded lifetime still match. */
internal object DepthIntegrationSelector {
    fun select(
        snapshot: DepthFrameSnapshot,
        fused: FusedDepthFrame?,
        nowMillis: Long,
        fusionTtlMillis: Long,
    ): DepthIntegrationSelection {
        if (fused != null) {
            val age = nowMillis - fused.receivedAtMillis
            val fresh = age >= 0L && age < fusionTtlMillis
            if (fused.width == snapshot.width && fused.height == snapshot.height && fresh) {
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
                )
            }
        }
        return DepthIntegrationSelection(
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
        )
    }
}
