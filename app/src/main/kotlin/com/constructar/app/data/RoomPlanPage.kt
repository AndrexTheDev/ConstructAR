/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import com.constructar.core.model.FloorplanSketch
import com.constructar.export.pdf.FloorplanSection
import com.constructar.export.svg.FloorplanSvg

/**
 * The protocol's floorplan page, built from the outline the project already stores.
 *
 * One ring, three artefacts: the card draws it, `thumbnail.svg` is made from it, and this turns it
 * into the page a customer files. The corners are taken as they are stored - no resampling, no
 * snapping, no second interpretation of which loop is the room - so the plan on paper and the
 * picture on the phone cannot describe different rooms.
 *
 * The words are parameters. `:export` has no string table and must never compose a sentence
 * (finding 100's rule), so the title, the caption and the "drawn by hand" note arrive already
 * localised; this function only assembles them, and only where they belong:
 *
 *  * no sketch, or one that is not drawable, produces no page at all rather than an empty frame;
 *  * the note is printed only for a wall the operator drew - a measured ring has nothing to explain;
 *  * a caption without both numbers is dropped, because "12.0 m² · " reads like a broken page.
 */
object RoomPlanPage {

    fun of(
        sketch: FloorplanSketch?,
        title: String,
        areaText: String,
        perimeterText: String,
        byHandNote: String,
    ): FloorplanSection? {
        if (sketch == null || !sketch.isDrawable) return null
        if (areaText.isBlank() || perimeterText.isBlank()) return null
        return FloorplanSection(
            corners = sketch.points.map { point -> FloorplanSvg.Corner(point.xMeters, point.zMeters) },
            title = title,
            caption = "$areaText · $perimeterText",
            note = if (sketch.closure == FloorplanSketch.Closure.DrawnByHand) byHandNote else "",
        )
    }
}
