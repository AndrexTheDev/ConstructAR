/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.core.model.HitConfidence
import kotlin.math.roundToInt

/**
 * How honest a run is about its weakest point.
 *
 * `RouteSession` already publishes `worstNodeConfidence` - the weakest of every point in the run,
 * which is the number that matters, because a chain of nine perfect points and one guessed one is
 * a chain with a guessed point in it. Until now nothing showed it: the HUD drew the *last* point's
 * confidence and the run's worst value was computed for nobody.
 *
 * The threshold is the hit cascade's own tiers: a plane hit is 1.0, a depth hit 0.85, a raycast
 * against a feature point 0.7. Anything at or above 0.85 came from a surface; below it, at least
 * one point came from something the app trusts less - which is exactly when the operator can still
 * walk back and tap it again, and exactly the moment he needs to be told.
 */
internal object RunConfidence {

    /**
     * Below this, at least one point is weaker than a depth hit: worth saying out loud.
     *
     * Shared with the order list through [HitConfidence.RUN_TRUSTED] - the same run must not be
     * weak on the phone and fine on the order list (finding 47).
     */
    const val WEAK_BELOW: Float = HitConfidence.RUN_TRUSTED

    /**
     * The warning worth showing, as a percentage, or `null` when there is nothing to say.
     *
     * `null` also for a run that has not started, and for one where the engine reported no
     * confidence at all (`0`) - an absent number is not a weak one.
     */
    fun weakPointPercent(nodeCount: Int, worstNodeConfidence: Float): Int? {
        if (nodeCount < 2) return null
        if (worstNodeConfidence <= 0f || worstNodeConfidence >= WEAK_BELOW) return null
        return (worstNodeConfidence * 100f).roundToInt().coerceIn(1, 99)
    }
}
