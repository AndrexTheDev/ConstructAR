/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

import com.constructar.core.math.Vec3

/**
 * Turns a jittering reticle into one settled point.
 *
 * Every AR hit on a real wall wobbles: plane polygon recomputation, anchor refinement, depth
 * sensor noise and the operator's own hand all move the sampled point by a few millimetres to
 * a couple of centimetres from frame to frame. Tapping the *first* sample therefore bakes that
 * frame's noise into the measurement - and the length error of a run is roughly the sum of the
 * endpoint errors.
 *
 * The stabiliser keeps the last sample window, takes the per-axis **median** (not the mean: one
 * frame that jumps onto a door reveal must not drag the point with it), and reports both the
 * spread and whether the operator is holding still. The app shows that as "hold steady" and
 * commits the median, so a still hand produces a sub-centimetre point instead of a 2 cm guess.
 *
 * All of it is plain arithmetic on [Vec3], so it is unit-tested on the host JVM rather than on
 * a device with a real camera.
 */
class HitStabilizer(
    /** Samples older than this are ignored: the phone (or the wall) may have moved since. */
    private val windowMillis: Long = 1_200L,
    /** At most this many recent samples are kept; five is enough for a median to be robust. */
    private val maxSamples: Int = 5,
    /** Spread at or below this counts as "held still". 1 cm is our accuracy claim. */
    private val settleMeters: Float = 0.010f,
    /** Fewer samples than this cannot settle, however tight they look. */
    private val minSamplesToSettle: Int = 3,
) {

    /** What the HUD and the tap handler need to know about the current window. */
    data class Reading(
        /** Median point of the window, or `null` when nothing recent was sampled. */
        val point: Vec3?,
        /** Number of samples inside the window. */
        val samples: Int,
        /** Largest distance of any sample from the median, metres. */
        val spreadMeters: Float,
        /** True when the window is full enough and tight enough to lock a point. */
        val settled: Boolean,
        /** Surface kind of the window, for the HUD label. */
        val lastHit: SurfaceHit? = null,
    ) {
        val hasPoint: Boolean get() = point != null
    }

    private data class Sample(val point: Vec3, val timestampMs: Long, val hit: SurfaceHit)

    private val samples = ArrayList<Sample>(8)

    /**
     * Adds one sample and returns the window's state.
     *
     * A `null` hit (reticle over nothing - a doorway, a mirror, the floor beyond the wall) ends
     * the window: the operator has moved on and an old point must not be committed later.
     */
    fun sample(hit: SurfaceHit?, nowMs: Long): Reading {
        if (hit == null) {
            samples.clear()
            return Reading(point = null, samples = 0, spreadMeters = 0f, settled = false)
        }
        // A different plane or a different cascade tier is a different measurement: mixing the
        // two would average a plane hit with a feature point and report a confidence that
        // belongs to neither.
        val previous = samples.lastOrNull()
        if (previous != null && !sameSurface(previous.hit, hit)) samples.clear()

        samples.add(Sample(hit.point, nowMs, hit))
        val cutoff = nowMs - windowMillis
        samples.removeAll { it.timestampMs < cutoff }
        while (samples.size > maxSamples) samples.removeAt(0)

        val median = medianOf(samples.map { it.point })
        val spread = samples.maxOf { it.point.distanceTo(median) }
        return Reading(
            point = median,
            samples = samples.size,
            spreadMeters = spread,
            settled = samples.size >= minSamplesToSettle && spread <= settleMeters,
            lastHit = samples.last().hit,
        )
    }

    /**
     * The hit to actually commit: the median of the window when it has settled, otherwise the
     * raw hit the cascade produced (the operator tapped deliberately - never swallow that).
     */
    fun stabilize(hit: SurfaceHit, nowMs: Long): SurfaceHit {
        val reading = sample(hit, nowMs)
        val point = reading.point
        if (!reading.settled || point == null) return hit
        // Only the position is replaced: the tier, its confidence and the surface kind are
        // properties of the hit that produced the window, and averaging must never upgrade them.
        return hit.copy(point = point)
    }

    fun reset() = samples.clear()

    private fun sameSurface(a: SurfaceHit, b: SurfaceHit): Boolean =
        a.source == b.source && a.planeId == b.planeId

    /** Per-axis median. An even count averages the two middle values. */
    private fun medianOf(points: List<Vec3>): Vec3 {
        val n = points.size
        require(n > 0) { "median of nothing" }
        fun axis(selector: (Vec3) -> Float): Float {
            val sorted = points.map(selector).sorted()
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
        }
        return Vec3(axis { it.x }, axis { it.y }, axis { it.z })
    }
}
