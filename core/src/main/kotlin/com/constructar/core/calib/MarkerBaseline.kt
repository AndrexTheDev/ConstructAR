/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import com.constructar.core.math.Pose
import com.constructar.core.math.Vec3

/**
 * One decoded sighting, together with the camera pose that produced it.
 *
 * [tagInCamera] is what the AprilTag decoder reports: the tag's centre in the camera's own frame,
 * in metres that come from the tag's *printed* size. [cameraPose] is where the phone was, in the
 * app's own world metres. The two are in different metres — that is the whole point, because their
 * disagreement is what a marker can measure.
 */
data class MarkerObservation(
    val id: Int,
    val tagInCamera: Vec3,
    val cameraPose: Pose,
    /**
     * Which ARCore session produced this. Default `0` for a single-session estimate.
     *
     * Two sessions have two world origins: the phone re-localised, or the operator closed the
     * screen and came back. A baseline between a camera position from one and a position from the
     * other is a subtraction of two different rooms - the same failure finding 217 found in the
     * route nodes, where it produced a floor height and a wall length that were pure geometry.
     */
    val sessionId: Long = 0L,
    /** The decoder's confidence in `[0, 1]`; carried, not judged here. */
    val decisionMargin: Float = 1f,
    /** Bits the decoder had to correct; carried, not judged here. */
    val hamming: Int = 0,
) {
    /** False for anything that is not a place: NaN, infinity, a tag behind the camera. */
    val isFinite: Boolean
        get() = tagInCamera.x.isFinite() && tagInCamera.y.isFinite() && tagInCamera.z.isFinite() &&
            tagInCamera.z > 0f &&
            cameraPose.translation.x.isFinite() && cameraPose.translation.y.isFinite() &&
            cameraPose.translation.z.isFinite() &&
            cameraPose.rotation.w.isFinite() && cameraPose.rotation.x.isFinite() &&
            cameraPose.rotation.y.isFinite() && cameraPose.rotation.z.isFinite()
}

/**
 * What two sightings of one marker say about the app's metre.
 *
 * `factor` has the same direction as every other factor in this package: it is what a *measured*
 * metre is multiplied by. A factor of 1.05 means ARCore's metre is 5 % short — the phone called a
 * real 1.05 m a metre — and every stored length has to grow.
 *
 * `accepted` is [ReferenceCalibration.accepted]'s answer and not a second window: the numbers that
 * decide "believable" live in exactly one place, and this class asks them.
 */
data class MarkerScaleProposal(
    val factor: Double,
    val accepted: Boolean,
    /** Pairs that agreed with the median ratio. */
    val pairs: Int,
    /** Pairs built and then rejected by the deviation filter. */
    val rejectedPairs: Int,
    /** Longest decoded baseline behind the factor: the evidence it actually rests on. */
    val baselineMetres: Float,
    /**
     * Longest **perpendicular** baseline behind the factor, in metres.
     *
     * The component of the camera-to-camera baseline at right angles to the sight ray. Walking
     * straight at a marker doubles its apparent size but moves the *measured* baseline almost
     * entirely along the ray, where depth noise dominates; walking sideways moves it across the
     * ray, where the geometry is strong. Two sightings a metre apart can therefore be almost no
     * evidence at all, and `baselineMetres` alone cannot tell the two walks apart (finding 242).
     */
    val perpendicularMetres: Float,
    /** Worst relative deviation among the accepted pairs. */
    val spread: Float,
)

/**
 * The marker tier's missing arithmetic: two views of one marker measure the app's metre.
 *
 * [estimateScaleFromTagSightings] (native) compares the spacing of *several* tags against a printed
 * layout — it needs a sheet of markers and a plan of where they sit. A single marker, printed once
 * and stuck to a wall, has no such layout; what it has is geometry: seen from two places, the
 * triangle camera–camera–tag gives a baseline twice over, once in the app's metres (between the two
 * camera positions ARCore reports) and once in the marker's metres (between the two camera-to-tag
 * vectors, rotated into world axes and scaled by the tag's printed size). Their ratio is the
 * correction.
 *
 * ```
 *   cam_a - cam_b = (R_b * c_b - R_a * c_a) / factor      (in ARCore metres)
 *   factor        = |R_b * c_b - R_a * c_a| / |cam_a - cam_b|
 * ```
 *
 * The rotation is not decoration: turn the phone 90 degrees between the two sightings and an
 * unrotated subtraction would compare a camera-frame vector with a world-frame one, which is how a
 * correct measurement gets a factor of 0.7 and the operator is told to recalibrate a phone that
 * was right.
 *
 * **Trust is the caller's.** Which margin and which hamming count as a read is decided by
 * `TagScaleOptions` in the native layer (the layout path's knobs), and none of those numbers appear
 * here: this function measures, it does not grade. What it does refuse on its own account is a
 * pair that cannot carry a ratio at all — a baseline shorter than the caller's floor, a decoded
 * baseline that is degenerate, an observation that is not a place.
 *
 * **It proposes, it never stores.** The decision belongs to `:core` — [ReferenceCalibration.accepted]
 * — and the write belongs to the project, exactly as with the bank card.
 */
object MarkerBaseline {

    /** Below this a decoded baseline is arithmetic noise rather than a measurement, in metres. */
    const val MIN_DECODED_BASELINE_METRES = 0.01f

    /**
     * Estimates the factor from every pair of sightings of the same tag.
     *
     * `minBaselineMetres` and `maxRelativeDeviation` are the caller's knobs, in the same shape the
     * native layout path uses: a pair shorter than the floor is not evidence, and a pair further
     * from the median than the deviation is an outlier (a mis-read id, a marker that moved).
     * `minPairs` is how much agreement there has to be before anything is published at all, and
     * `minPerpendicularBaselineMetres` is how much of the baseline has to point *across* the sight
     * ray - `0` means "do not ask", which is what a two-sighting estimate wants; the marker sweep
     * sets it, because its status line has to tell the operator to walk sideways rather than into
     * the wall (finding 242).
     *
     * Returns `null` when fewer than `minPairs` pairs survive — "not enough evidence" is not a
     * factor of 1.0, and a caller that cannot tell those apart would silently certify an
     * uncalibrated phone.
     */
    fun estimate(
        observations: List<MarkerObservation>,
        minBaselineMetres: Float,
        maxRelativeDeviation: Float,
        minPairs: Int = 1,
        minPerpendicularBaselineMetres: Float = 0f,
    ): MarkerScaleProposal? {
        if (observations.size < 2) return null
        if (!(minBaselineMetres > 0f) || !minBaselineMetres.isFinite()) return null
        if (!(maxRelativeDeviation > 0f) || !maxRelativeDeviation.isFinite()) return null
        if (minPairs < 1) return null
        if (!(minPerpendicularBaselineMetres >= 0f) || !minPerpendicularBaselineMetres.isFinite()) {
            return null
        }

        val ratios = ArrayList<PairRatio>()
        var rejected = 0
        for (first in observations.indices) {
            for (second in first + 1 until observations.size) {
                val a = observations[first]
                val b = observations[second]
                if (a.id != b.id) {
                    // Different markers are not a pair in this shape: their geometry is in the
                    // layout path (`estimateScaleFromTagSightings`), not here.
                    continue
                }
                if (a.sessionId != b.sessionId) {
                    // Two sessions, two world origins. Not an outlier - a different room.
                    rejected++
                    continue
                }
                if (!a.isFinite || !b.isFinite) {
                    rejected++
                    continue
                }
                val measured = a.cameraPose.translation.distanceTo(b.cameraPose.translation)
                if (measured < minBaselineMetres) {
                    rejected++
                    continue
                }
                // Across the sight ray or along it? A walk into the wall has a metre of baseline
                // and almost none of it is evidence, so a caller that cares says so and the pair
                // is refused here - as a *rejection*, because the operator has to be told to move
                // sideways rather than be handed a factor built on depth noise.
                val perpendicular = perpendicularBaselineMetres(a, b)
                if (perpendicular < minPerpendicularBaselineMetres) {
                    rejected++
                    continue
                }
                val decodedVector = b.cameraPose.transformDirection(b.tagInCamera) -
                    a.cameraPose.transformDirection(a.tagInCamera)
                val decoded = decodedVector.length
                if (decoded < MIN_DECODED_BASELINE_METRES) {
                    rejected++
                    continue
                }
                ratios += PairRatio(
                    ratio = decoded / measured,
                    baseline = measured,
                    perpendicular = perpendicular,
                )
            }
        }
        if (ratios.size < minPairs) return null

        val median = medianOf(ratios.map { it.ratio })
        val accepted = ArrayList<PairRatio>(ratios.size)
        var worst = 0f
        for (pair in ratios) {
            val deviation = kotlin.math.abs(pair.ratio - median) / median
            if (deviation > maxRelativeDeviation) {
                rejected++
                continue
            }
            accepted += pair
            if (deviation > worst) worst = deviation
        }
        if (accepted.size < minPairs) return null

        // Baseline-weighted, because a ratio's error falls with its baseline: a 2 m pair is worth
        // ten 20 cm pairs, and counting them equally would let a wave of the hand outvote the one
        // measurement that had room to be accurate.
        var weighted = 0.0
        var weight = 0.0
        var longest = 0f
        var longestPerpendicular = 0f
        for (pair in accepted) {
            if (pair.perpendicular > longestPerpendicular) longestPerpendicular = pair.perpendicular
            weighted += pair.ratio.toDouble() * pair.baseline
            weight += pair.baseline.toDouble()
            if (pair.baseline > longest) longest = pair.baseline
        }
        if (!(weight > 0.0)) return null
        val factor = weighted / weight
        if (!factor.isFinite() || factor <= 0.0) return null
        return MarkerScaleProposal(
            factor = factor,
            accepted = ReferenceCalibration.accepted(factor),
            pairs = accepted.size,
            rejectedPairs = rejected,
            baselineMetres = longest,
            perpendicularMetres = longestPerpendicular,
            spread = worst,
        )
    }

    /**
     * The part of the camera-to-camera baseline that runs *across* the sight ray.
     *
     * `b_perp = || b - (b . v) v ||` with `b` the camera-to-camera vector and `v` the unit sight
     * ray, taken as the direction from the midpoint of the two cameras to the midpoint of the two
     * marker estimates. Two views of one tag give that direction directly, which is why this is
     * computed from the same two rotations the factor uses rather than from a stored normal.
     *
     * `0` for a pair that is not a measurement at all (a zero baseline, a tag behind the camera):
     * the caller's floor then refuses it, which is the same answer as "no parallax".
     */
    fun perpendicularMetresBetween(a: MarkerObservation, b: MarkerObservation): Float =
        perpendicularBaselineMetres(a, b)

    private fun perpendicularBaselineMetres(a: MarkerObservation, b: MarkerObservation): Float {
        val cameraA = a.cameraPose.translation
        val cameraB = b.cameraPose.translation
        val tagA = a.cameraPose.transform(a.tagInCamera)
        val tagB = b.cameraPose.transform(b.tagInCamera)
        val baseline = cameraB - cameraA
        val ray = (tagA + tagB) * 0.5f - (cameraA + cameraB) * 0.5f
        val length = ray.length
        if (!(length > 1e-4f) || !length.isFinite()) return 0f
        val unit = ray * (1f / length)
        val along = baseline dot unit
        val perpendicular = baseline - unit * along
        val result = perpendicular.length
        return if (result.isFinite()) result else 0f
    }

    private data class PairRatio(val ratio: Float, val baseline: Float, val perpendicular: Float)

    private fun medianOf(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }
}
