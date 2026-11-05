/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.arengine.hit.SnapInfo
import com.constructar.core.units.LengthFormatter
import com.constructar.core.units.LengthUnit

/**
 * How far the reticle was moved by the architectural snap, as digits a HUD line can print.
 *
 * The snap pulls a hit onto a corner or an edge when it is within 10 cm of one, and until now it
 * did so **silently**: the point jumped, the length under the crosshair changed, and nothing said
 * whether that was the corner or a noisy frame. The operator's only recourse was to tap again and
 * hope - which is exactly what a measuring tool must never train him to do (finding 213).
 *
 * Centimetres with one decimal, because the whole tolerance is ten of them: "4.2 cm" is a number
 * an operator can judge, "0.04 m" is one he has to convert while holding a laser. The digits come
 * from [LengthFormatter] so the HUD's decimal mark is the protocol's, in all six languages.
 *
 * `null` is the honest answer for anything this cannot print - no snap, a distance that is not a
 * number, a negative one. A sentence with a placeholder in it is worse than no sentence: the marker
 * and the line both disappear, and the reticle simply does not claim to have moved.
 */
internal object SnapReading {

    /** One decimal of a centimetre. The tolerance is 10 cm, so 1 mm of resolution is plenty. */
    const val DECIMALS: Int = 1

    /** The digits for the moved distance, or `null` when there is nothing to state. */
    fun moved(snap: SnapInfo?): String? {
        val metres = snap?.distanceM ?: return null
        if (!metres.isFinite() || metres < 0f) return null
        return LengthFormatter.number(metres, LengthUnit.CENTIMETER, DECIMALS)
    }
}
