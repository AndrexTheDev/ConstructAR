/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

/**
 * One tag the decoder read, in the camera's frame, in metres.
 *
 * The numbers are metric because they were computed with two things the caller had to supply: the
 * camera's intrinsics and the printed side of the tag's black square. Without them a detection is
 * four points in a picture - which is why `NativeSlamEngine.detectAprilTagScale` refuses the call
 * rather than returning a distance that was really a pixel count.
 */
data class AprilTagSightingReport(
    val id: Int,
    val xMetres: Float,
    val yMetres: Float,
    val zMetres: Float,
    /** How clearly the sampled bits agreed with the code, in `[0, 1]`. */
    val decisionMargin: Float,
    /** Bits the decoder had to correct; 0 is a clean read. */
    val hamming: Int,
) {

    /** Straight-line distance from the camera, metres. */
    val distanceMetres: Float
        get() = kotlin.math.sqrt(xMetres * xMetres + yMetres * yMetres + zMetres * zMetres)

    /** A read a scale correction may rest on: no corrected bits and beyond guessing distance. */
    val isClean: Boolean get() = hamming == 0
}

/**
 * What one frame held.
 *
 * An empty report is an answer ("no tag in view"), and a `null` report is a different one ("that
 * was not a frame"). The distinction is the same one `RtabMapBridge.isAvailable` had to make in
 * finding 98, and it matters more here: "no tag" invites the operator to move the phone, while "no
 * frame" means the app is asking the wrong question.
 */
data class AprilTagReport(val tags: List<AprilTagSightingReport>) {

    /** The nearest tag, which is the one a scale check wants: its corners are the sharpest. */
    val nearest: AprilTagSightingReport?
        get() = tags.minByOrNull { it.distanceMetres }

    /** The cleanest read, by the decoder's own margin. */
    val strongest: AprilTagSightingReport?
        get() = tags.maxByOrNull { it.decisionMargin }

    companion object {

        /** Floats the native layer writes per tag: id, x, y, z, margin, hamming. */
        const val FLOATS_PER_TAG = 6

        /** The most the native layer returns, so a corrupt header cannot ask for a huge list. */
        const val MAX_TAGS = 32

        fun from(values: FloatArray?): AprilTagReport? {
            if (values == null || values.isEmpty()) return null
            val countValue = values[0]
            if (!countValue.isFinite() || countValue < 0f || countValue != kotlin.math.floor(countValue)) {
                return null
            }
            val count = countValue.toInt()
            if (count > MAX_TAGS) return null
            if (values.size != 1 + count * FLOATS_PER_TAG) return null
            val tags = ArrayList<AprilTagSightingReport>(count)
            for (index in 0 until count) {
                val base = 1 + index * FLOATS_PER_TAG
                val idValue = values[base]
                val hammingValue = values[base + 5]
                if (!idValue.isFinite() || idValue < 0f || idValue != kotlin.math.floor(idValue)) return null
                if (!hammingValue.isFinite() || hammingValue < 0f ||
                    hammingValue != kotlin.math.floor(hammingValue)
                ) {
                    return null
                }
                val x = values[base + 1]
                val y = values[base + 2]
                val z = values[base + 3]
                val margin = values[base + 4]
                if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !margin.isFinite()) return null
                // A tag behind the camera is not a sighting, and a margin outside [0, 1] is not a
                // confidence: both would be printed as facts about a room.
                if (z <= 0f || margin < 0f || margin > 1f) return null
                tags += AprilTagSightingReport(
                    id = idValue.toInt(),
                    xMetres = x,
                    yMetres = y,
                    zMetres = z,
                    decisionMargin = margin,
                    hamming = hammingValue.toInt(),
                )
            }
            if (tags.map { it.id }.toSet().size != tags.size) return null
            return AprilTagReport(tags)
        }
    }
}
