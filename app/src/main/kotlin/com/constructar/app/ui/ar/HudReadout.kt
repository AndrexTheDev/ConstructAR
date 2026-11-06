/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * Which number is the big one on the glass (finding 272).
 *
 * The HUD had its hierarchy fixed once already (finding 265: a total, the live segment under it) and
 * it is right - from the second point on. With **one** point it was upside down: the run's total is
 * `0.00 m` by definition, printed in the largest type on the screen in the colour of a measured
 * value, while the number the operator was actually deciding - how far the reticle is from the point
 * he just set - grew in `bodyMedium` underneath it. He walks, taps when it reads the length he
 * needs, and the only number he was watching was the small one.
 *
 * The rule is one sentence: **the headline is the number being decided**. That is the live segment
 * exactly while the run has no length yet (one point), and the run's own length from the second
 * point on. A number that is always zero is not a headline, it is a placeholder.
 *
 * The ink follows the same distinction the rest of the app uses: an uncommitted segment is
 * *provisional* (the accent lime, like the step strip's "now" and the tap result), a committed run
 * is a *measured* value (the gold the HUD has always used). A segment drawn in the gold of a
 * measurement would claim something the operator has not tapped yet.
 *
 * No strings and no colours - the caller maps the two enums - so the rule is testable here.
 */
internal object HudReadout {

    /**
     * Which number gets `displaySmall`.
     *
     * @param nodeCount points in the current run.
     * @param hasLiveSegment whether the reticle has anything to measure to. Without it (no surface
     *   under the crosshair) there is no segment, and `SEGMENT` would print an empty headline -
     *   which is why the flag is an input and not a detail of the caller.
     */
    fun headline(nodeCount: Int, hasLiveSegment: Boolean): HudHeadline = when {
        nodeCount == 1 && hasLiveSegment -> HudHeadline.SEGMENT
        else -> HudHeadline.TOTAL
    }

    /**
     * Whether the floating label at the last point has anything to say.
     *
     * A run of one point has a total of `0.00 m` by definition, and that is exactly what the label
     * was printing - in the middle of the camera image, next to the point the operator had just
     * set, in the colour of a measured length. A number that cannot be anything but zero is not a
     * label, it is a placeholder floating in the room (finding 272).
     */
    fun labelsLastPoint(nodeCount: Int): Boolean = nodeCount >= 2

    /** The colour role the headline carries. */
    fun ink(headline: HudHeadline): HudInk = when (headline) {
        HudHeadline.TOTAL -> HudInk.MEASURED
        HudHeadline.SEGMENT -> HudInk.PROVISIONAL
    }
}

/** The two numbers the HUD can lead with. */
internal enum class HudHeadline {
    /** The run's own length. Gold, and the headline from the second point on. */
    TOTAL,

    /** The segment from the last point to the reticle. Lime, and the headline while it is the only thing there is. */
    SEGMENT,
}

/** What the headline's colour claims. */
internal enum class HudInk {
    /** A length that has been committed by a tap. */
    MEASURED,

    /** A length that is being aimed at, and is not part of the run yet. */
    PROVISIONAL,
}
