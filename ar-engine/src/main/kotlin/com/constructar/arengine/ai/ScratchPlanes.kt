/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.ai

import com.constructar.arengine.depth.DepthFrameSnapshot

/**
 * The two planes a fusion pass takes from the frame it was captured with, copied into buffers
 * that are reused pass after pass.
 *
 * The copy is unavoidable: the pass runs on its own worker, long after the snapshot's pooled
 * arrays have gone back to `DepthBufferPool` (finding 168). What is avoidable is a fresh
 * `copyOf()` per pass — 600 kB of depth plus the confidence image every half second, about
 * 1.2 MB/s of garbage on the render thread, in a class that already reuses its ARGB buffer and
 * its output buffer for exactly that reason (finding 173).
 *
 * One pair of planes is enough because one pass runs at a time: `MonocularDepthSource` holds a
 * `busy` flag that stays set until the worker has finished with these arrays, and only the
 * render thread fills them.
 */
internal class ScratchPlanes {

    private var depth = ShortArray(0)
    private var confidence = ByteArray(0)

    /**
     * The depth plane of [snapshot], tightly packed (`DepthCapture` guarantees rowStride ==
     * width), copied into a buffer that the next pass fills again.
     */
    fun depthFrom(snapshot: DepthFrameSnapshot): ShortArray {
        val needed = snapshot.width * snapshot.height
        if (depth.size < needed) depth = ShortArray(needed)
        System.arraycopy(snapshot.depthMm, 0, depth, 0, minOf(needed, snapshot.depthMm.size))
        return depth
    }

    /**
     * The confidence plane of [snapshot], or null when the device does not offer one — a null
     * result clears nothing, so the buffer stays available for the next frame that has one.
     */
    fun confidenceFrom(snapshot: DepthFrameSnapshot): ByteArray? {
        val source = snapshot.confidence ?: return null
        if (confidence.size < source.size) confidence = ByteArray(source.size)
        System.arraycopy(source, 0, confidence, 0, minOf(source.size, confidence.size))
        return confidence
    }
}
