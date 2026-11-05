/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.keyframe

import com.constructar.nativebridge.NativeSlamEngine

/**
 * The last link: a [PoseGraphSink] over the JNI pose graph.
 *
 * Four lines of delegation, and that is the point - everything that could be wrong (which frames
 * are kept, what the measurement is, whether an edge may reference a node) is decided outside it
 * and tested without a device. This class exists so that exactly one place in the app calls
 * `addPoseNode` / `addPoseEdge`, which is what makes the threading contract in [NativeSlamEngine]
 * checkable by reading one file.
 *
 * **Worker thread only.** Every other thread reaches the graph through [KeyframeQueue], which
 * enqueues, and the pipeline's single worker applies the queue through
 * [NativePoseGraphSink] - see [KeyframeApplier].
 */
class NativePoseGraphSink(private val engine: NativeSlamEngine) : PoseGraphSink {

    override fun addNode(poseColumnMajor: FloatArray, fixed: Boolean): Int =
        engine.addPoseNode(poseColumnMajor, fixed)

    override fun addEdge(from: Int, to: Int, measurementColumnMajor: FloatArray) {
        engine.addPoseEdge(from, to, measurementColumnMajor)
    }
}
