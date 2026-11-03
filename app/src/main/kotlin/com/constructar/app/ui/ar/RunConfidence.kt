/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.core.model.HitConfidence
import com.constructar.core.units.PercentText

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
     * `null` for a run that has not started. It used to be `null` for a confidence of `0` as well,
     * read as "the engine reported no number" - but `0` is a legal confidence (`RouteNodeRecord`
     * documents 0..1, the hit cascade never produces it, and a project file restored from another
     * phone or written by an older build can carry it) and it is the *worst* legal one. The order
     * list has always counted it (`confidence < HitConfidence.RUN_TRUSTED`), so the same run was
     * questioned in the paperwork and passed in silence on the phone - the exact disagreement
     * [WEAK_BELOW] exists to prevent (findings 47, 158). The "no nodes yet" case this guard was
     * meant for is already answered by [nodeCount].
     */
    fun weakPointPercent(nodeCount: Int, worstNodeConfidence: Float): Int? {
        if (nodeCount < 2) return null
        if (worstNodeConfidence >= WEAK_BELOW) return null
        return PercentText.of(worstNodeConfidence).coerceIn(1, 99)
    }
}
