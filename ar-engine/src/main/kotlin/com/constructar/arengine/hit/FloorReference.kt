/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

/**
 * The floor a session is standing on, in the session's own coordinates - the entry point the hit
 * source calls.
 *
 * This type is now a thin name over [FloorEvaluator]: the question "which plane is the floor" is
 * answered by three weighted criteria and a confidence, not by "the highest upward plane wins"
 * (finding 218). What stays here is the single call the planes' owner makes, so a caller that only
 * needs the number is not forced to know about the scoring.
 */
object FloorReference {

    /** Retained for the callers that only need a number; see [FloorEvaluator.decide] for the why. */
    const val MAX_DROP_M: Float = FloorEvaluator.MAX_DROP_M

    /** Retained for the classifier test: a plane this close to horizontal is floor or ceiling. */
    const val VERTICAL_TOLERANCE: Float = FloorEvaluator.VERTICAL_TOLERANCE

    /**
     * The height of the floor under [viewerHeightM], or `null` when no plane can be it.
     *
     * A caller that has to *state* a mounting height from it must use [decide] and check
     * `usable`: this function answers with the best guess even when the guess is
     * [FloorReferenceStatus.UNCERTAIN].
     */
    fun heightFrom(
        planes: List<PlaneCandidate>,
        viewerHeightM: Float,
        maxDropM: Float = MAX_DROP_M,
    ): Float? = FloorEvaluator.decide(planes, viewerHeightM, maxDropM).heightM

    /** The full decision, which is what a screen needs to be honest about a height. */
    fun decide(
        planes: List<PlaneCandidate>,
        viewerHeightM: Float,
        maxDropM: Float = MAX_DROP_M,
    ): FloorDecision = FloorEvaluator.decide(planes, viewerHeightM, maxDropM)
}
