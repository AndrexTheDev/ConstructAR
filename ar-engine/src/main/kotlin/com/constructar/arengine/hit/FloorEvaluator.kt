/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

/**
 * How much the app is willing to bet that a plane is the floor.
 *
 * Three outcomes, because a boolean was the wrong shape for this question. "Found a floor" and
 * "found *a* plane that might be the floor" lead to different behaviour: the first assigns DIN
 * 18015 mounting heights, the second must not, and the third must say what to do about it.
 */
enum class FloorReferenceStatus {
    /** One plane is the floor by a clear margin and sits where a floor sits. Use it. */
    TRACKED,

    /**
     * The best candidate is not distinguishable from the runner-up, or is not at a plausible
     * distance below the camera - a table top, a shelf, half a room seen through a doorway.
     *
     * The height is still reported (it is the best guess), but nothing may *state* a mounting
     * height from it without saying so: the HUD asks the operator to point the camera at the
     * floor, and a project recorded in this state is one walk away from being right.
     */
    UNCERTAIN,

    /** No horizontal upward-facing tracked plane is under the camera. Nothing to report. */
    MISSING,
}

/** One candidate's contribution, kept so a field report can be argued about with numbers. */
data class FloorCandidateScore(
    val planeId: String,
    /** The plane's height in session coordinates. */
    val heightM: Float,
    /** How far below the camera it is, metres. */
    val dropM: Float,
    val areaM2: Float,
    /** 0..1, the weighted score the winner is chosen by. */
    val score: Float,
)

/** What the evaluator concluded, and why. */
data class FloorDecision(
    /** The best candidate's height, or `null` when there was none. */
    val heightM: Float?,
    val status: FloorReferenceStatus,
    /** The winner's score, 0..1. */
    val confidence: Float,
    /** How far ahead the winner was, 0..1. Below [FloorEvaluator.UNCERTAIN_GAP] the call is a guess. */
    val gap: Float,
    val candidateCount: Int,
    val candidates: List<FloorCandidateScore> = emptyList(),
) {
    /** True only when the caller may build DIN 18015 heights from [heightM]. */
    val usable: Boolean get() = status == FloorReferenceStatus.TRACKED && heightM != null

    companion object {
        val MISSING = FloorDecision(
            heightM = null,
            status = FloorReferenceStatus.MISSING,
            confidence = 0f,
            gap = 0f,
            candidateCount = 0,
        )
    }
}

/**
 * Which horizontal plane under the camera is the finished floor.
 *
 * Every mounting height in the trade is measured from it (DIN 18015-3: sockets at 30 cm, switches
 * at 105 cm, the ceiling outlet 15 cm under the ceiling), and ARCore will not tell us: a plane
 * carries a normal and a classification, not a promise. The first version of this logic took *the
 * highest upward-facing plane below the viewer* and was therefore wrong in the one place it
 * matters - a table is upward-facing, below the viewer, and higher than the floor (finding 218).
 * Every socket on that job would have been printed 65 cm too high, with the standard's authority
 * behind it.
 *
 * So the choice is scored on three things a floor has and a table does not:
 *
 * | criterion | weight | why |
 * |---|---|---|
 * | **area** | 0.4 | a floor is the largest thing in the room; a table top is 1–2 m², a floor is 10–40 m² |
 * | **expected drop** | 0.4 | a phone is held at about 1.40 m, so the floor is about 1.40 m below it - the one piece of the operator's posture that is predictable |
 * | **lowest plane** | 0.2 | among plausible candidates the floor is the deepest, because everything standing on it is above it |
 *
 * The scores are normalised **within the candidate set** (min-max), so two planes are always
 * compared against each other rather than against an absolute scale nobody can calibrate offline.
 * A single candidate scores 1.0 by construction, which is why the separate distance check exists:
 * one upward plane 75 cm below the phone (a table, with the floor never tracked) is [UNCERTAIN],
 * not a confident floor.
 *
 * Two further guards keep the answer honest:
 *
 *  - `drop < MIN_DROP_M` is discarded outright: a plane 30 cm below the camera is a workbench, and
 *    a "floor" there would put every socket at 2 m;
 *  - `gap < UNCERTAIN_GAP` is [UNCERTAIN]: two tracked fragments of the same floor, or a floor and
 *    a pallet, are not a decision worth printing.
 */
object FloorEvaluator {

    /** Weight of the polygon area. */
    const val WEIGHT_AREA: Float = 0.4f

    /** Weight of the distance from the expected 1.40 m drop. */
    const val WEIGHT_HEIGHT: Float = 0.4f

    /** Weight of "the lowest plausible plane is the floor". */
    const val WEIGHT_LOWEST: Float = 0.2f

    /** A phone is held at roughly chest height, so the floor is roughly this far below it. */
    const val EXPECTED_DROP_M: Float = 1.40f

    /** Below this margin between winner and runner-up the choice is not a decision. */
    const val UNCERTAIN_GAP: Float = 0.15f

    /**
     * How far the winner may sit from [EXPECTED_DROP_M] before it stops counting as a floor.
     *
     * 0.45 m is the width of the only band where the two explanations differ: a floor under a
     * crouching or a standing operator (0.95–1.85 m) versus a table top (0.6–0.8 m). A lone
     * plane outside it - the table with no floor ever tracked - is reported [UNCERTAIN].
     */
    const val HEIGHT_TOLERANCE_M: Float = 0.45f

    /** Nothing below 3.5 m under the phone is this room's floor. */
    const val MAX_DROP_M: Float = 3.5f

    /** Nothing shallower than 30 cm is a floor: that is a worktop. */
    const val MIN_DROP_M: Float = 0.30f

    /** A plane this close to horizontal counts as a floor or a ceiling. */
    const val VERTICAL_TOLERANCE: Float = 0.6f

    /**
     * Chooses the floor among [planes] as seen from [viewerHeightM].
     *
     * @param viewerHeightM the camera's height in session coordinates
     * @param maxDropM how far below the camera a plane may be and still belong to this room
     */
    fun decide(
        planes: List<PlaneCandidate>,
        viewerHeightM: Float,
        maxDropM: Float = MAX_DROP_M,
    ): FloorDecision {
        if (!viewerHeightM.isFinite()) return FloorDecision.MISSING
        if (!maxDropM.isFinite() || maxDropM <= 0f) return FloorDecision.MISSING

        val candidates = planes
            .asSequence()
            .filter { it.isTracking }
            .filter { it.subsumedBy == null }
            .filter { it.polygon.size >= 3 }
            .filter { it.isReal() }
            // Upward-facing only: a ceiling has the same |y| and the opposite sign, and picking it
            // would put the floor above the operator's head.
            .filter { it.normal.y >= VERTICAL_TOLERANCE }
            .mapNotNull { plane ->
                val drop = viewerHeightM - plane.offset
                if (!drop.isFinite()) return@mapNotNull null
                if (drop < MIN_DROP_M || drop > maxDropM) return@mapNotNull null
                Triple(plane, plane.offset, drop)
            }
            .toList()

        if (candidates.isEmpty()) return FloorDecision.MISSING

        val areas = candidates.map { it.first.areaSquareMeters.coerceAtLeast(0f) }
        val drops = candidates.map { it.third }
        val deviations = drops.map { kotlin.math.abs(it - EXPECTED_DROP_M) }
        val maxArea = areas.maxOrNull() ?: 0f
        val minArea = areas.minOrNull() ?: 0f
        val maxDeviation = deviations.maxOrNull() ?: 0f
        val deepest = drops.maxOrNull() ?: 0f
        val shallowest = drops.minOrNull() ?: 0f

        val scored = candidates.mapIndexed { index, (plane, height, drop) ->
            val areaScore = if (maxArea - minArea <= 1e-4f) 1f else (areas[index] - minArea) / (maxArea - minArea)
            val heightScore = if (maxDeviation <= 1e-4f) 1f else 1f - deviations[index] / maxDeviation
            val lowestScore = if (deepest - shallowest <= 1e-4f) {
                1f
            } else {
                (drop - shallowest) / (deepest - shallowest)
            }
            val score = WEIGHT_AREA * areaScore + WEIGHT_HEIGHT * heightScore + WEIGHT_LOWEST * lowestScore
            FloorCandidateScore(
                planeId = plane.id,
                heightM = height,
                dropM = drop,
                areaM2 = areas[index],
                score = score,
            )
        }.sortedWith(
            // Deterministic: score first, and on an exact tie the deeper plane wins - a floor is
            // under everything that stands on it, and "the first one ARCore listed" is not a rule.
            compareByDescending<FloorCandidateScore> { it.score }.thenByDescending { it.dropM },
        )

        val winner = scored.first()
        val runnerUp = scored.getOrNull(1)
        val gap = if (runnerUp == null) 1f else winner.score - runnerUp.score
        val plausible = kotlin.math.abs(winner.dropM - EXPECTED_DROP_M) <= HEIGHT_TOLERANCE_M
        val status = when {
            gap < UNCERTAIN_GAP -> FloorReferenceStatus.UNCERTAIN
            !plausible -> FloorReferenceStatus.UNCERTAIN
            else -> FloorReferenceStatus.TRACKED
        }
        return FloorDecision(
            heightM = winner.heightM,
            status = status,
            confidence = winner.score.coerceIn(0f, 1f),
            gap = gap.coerceIn(0f, 1f),
            candidateCount = scored.size,
            candidates = scored,
        )
    }

    /** A plane with no usable geometry contributes nothing to a height. */
    private fun PlaneCandidate.isReal(): Boolean =
        normal.x.isFinite() && normal.y.isFinite() && normal.z.isFinite() &&
            offset.isFinite() &&
            polygon.all { p -> p.x.isFinite() && p.y.isFinite() && p.z.isFinite() }
}
