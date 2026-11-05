/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import com.constructar.core.model.FloorplanSketch
import com.constructar.core.units.LengthFormatter
import com.constructar.core.units.LengthUnit

/**
 * The two numbers the home card states about a room: its area and its perimeter, as digits.
 *
 * The units live in the resources (`card_area` is `%1$s m²`), not here: `:app` owns every word and
 * every unit a screen prints, and a unit typed into Kotlin would ship in one language only. What
 * this returns is what `LengthFormatter` already produces everywhere else - `Locale.ROOT` digits -
 * so the card's area, its perimeter and the metres in the length column of the same row cannot
 * disagree about the decimal mark.
 *
 * Both numbers come from the outline stored in the project (`FloorplanSketch`), which is the same
 * ring the card draws and the same one the exported SVG is made from. There is deliberately no
 * second source: a card that took its area from the routes' lengths or from a stored summary would
 * be a second answer to "how big is this room", and the two would drift the first time a run is
 * deleted.
 */
data class RoomSizeText(val area: String, val perimeter: String) {

    companion object {

        /**
         * Area to a tenth of a square metre: a scan is good to a centimetre on a wall, so the
         * second decimal of an area would be a precision the measurement does not have.
         */
        const val AREA_DECIMALS: Int = 1

        /**
         * The room's numbers, or `null` when there is no room to state.
         *
         * `null` is the card's icon state, and it is deliberately the same condition the picture
         * uses ([FloorplanSketch.isDrawable]): a row that drew a shape but printed no numbers, or
         * printed numbers beside an icon, would be two answers to "was this room measured".
         * A zero area or a zero perimeter is refused for the same reason - a degenerate outline
         * (every corner on one line) is a plan that did not close around anything, and "0.0 m²"
         * would be a claim about the room rather than about the scan.
         */
        fun of(sketch: FloorplanSketch?): RoomSizeText? {
            if (sketch == null || !sketch.isDrawable) return null
            val area = sketch.areaSquareMeters
            val perimeter = sketch.perimeterMeters
            if (!area.isFinite() || !perimeter.isFinite()) return null
            if (area <= 0f || perimeter <= 0f) return null
            return RoomSizeText(
                area = LengthFormatter.number(area, LengthUnit.METER, AREA_DECIMALS),
                perimeter = LengthFormatter.number(perimeter, LengthUnit.METER),
            )
        }
    }
}
