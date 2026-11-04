/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.units

import kotlin.math.roundToInt
import java.util.Locale

/**
 * The one rule for turning a 0..1 ratio into the whole percent the operator reads.
 *
 * It existed in five versions before this: a truncating `toInt` on `ratio * 100` on the material
 * screen, on the scan gauge and in both protocol columns, `roundToInt` for the AR confidence
 * warning, `Math.round` in the protocol's accuracy note, and `%.0f` in
 * [LengthFormatter.formatWasteFactor] - a formatter no surface called. Truncation is not cosmetic:
 *
 *  * a scan whose detail fraction is 0.999 read "Detail 99 %" while the mesh was complete;
 *  * a run whose weakest point is 0.849 read **85 %** on the phone (rounded) and **84 %** in the
 *    protocol the customer signs - and that document's own sentence says anything below 85 % needs
 *    a second look, so the same run was warned about in the paperwork and not on site;
 *  * a firm waste factor of 10.5 % read "10 %" on three surfaces at once.
 *
 * Rounding is the rule because a percent is a quantity the operator compares against thresholds -
 * the point of a number is that both ends of the comparison round it the same way.
 */
object PercentText {

    /**
     * [ratio] as a whole percentage point, rounded half-up and clamped to 0..100.
     *
     * Clamped because the ratio is measured: float noise around a complete scan (0.9999, or 1.0004
     * from a fused estimate) must not print "100 %" one moment and "101 %" the next. A `NaN`
     * cannot be presented at all, so it becomes 0 - the same "no number" a fresh scan shows.
     */
    fun of(ratio: Float): Int {
        if (ratio.isNaN()) return 0
        return (ratio * 100f).roundToInt().coerceIn(0, 100)
    }

    /**
     * [template] with the percent filled in, e.g. `"Order length 10 %"`.
     *
     * For the protocol, whose percent sentences are resource templates in six languages and take
     * `%1$d`; formatting with [Locale.ROOT] keeps `%` a percent and never a Turkish-`Locale`
     * surprise in a document that is signed.
     */
    fun format(template: String, ratio: Float): String =
        String.format(Locale.ROOT, template, of(ratio))
}
