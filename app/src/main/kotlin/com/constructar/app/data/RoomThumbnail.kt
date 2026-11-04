/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import com.constructar.core.math.PolygonMath
import com.constructar.core.math.Vec3
import com.constructar.export.svg.FloorplanSvg
import com.constructar.nativebridge.FloorplanPayload
import com.constructar.nativebridge.PlanLoop
import kotlin.math.abs

/**
 * The room's card picture: one closed loop of the floorplan, drawn by [FloorplanSvg].
 *
 * Two decisions, both of which are about *not* drawing something:
 *
 *  * **Only a closed loop is a room.** A doorway leaves the waist-height ring open, and the native
 *    vectoriser reports that honestly (no area, no segments). Joining such a loop's ends here would
 *    draw a wall across the doorway and put a picture of a smaller room on the card - the shape a
 *    customer would then ask about. So a plan with no closed loop produces no picture at all, and
 *    the card falls back to its own placeholder.
 *  * **The largest closed loop wins.** A room with a cupboard or a niche scanned into it can trace
 *    more than one closed loop; the biggest one is the room, and the same rule produces the area
 *    the protocol prints - so the picture and the number describe the same shape.
 *
 * Everything here is pure: a payload in, an SVG string out. The file write and the moment to take
 * the plan belong to the repository and the AR screen (finding 100's rule about who owns what).
 */
object RoomThumbnail {

    /** The name the card will look for, next to the project's JSON. */
    const val FILE_NAME: String = "thumbnail.svg"

    /**
     * The room's outline as floor-plan corners, or null when no loop closed.
     *
     * The loop is oriented as the vectoriser walked it; [FloorplanSvg] does not care about winding,
     * because it fills the shape the points trace rather than a signed area.
     */
    fun outline(payload: FloorplanPayload): List<FloorplanSvg.Corner>? {
        val best = largestClosedLoop(payload.loops) ?: return null
        if (best.points.size < 3) return null
        return best.points.map { FloorplanSvg.Corner(it.xMeters, it.zMeters) }
    }

    /** The SVG for a plan, or null when there is no closed room to draw. */
    fun svg(payload: FloorplanPayload, options: FloorplanSvg.Options = FloorplanSvg.Options()): String? {
        val corners = outline(payload) ?: return null
        return when (val rendered = FloorplanSvg.render(corners, options)) {
            is FloorplanSvg.Output.Drawn -> rendered.drawing.svg
            // A loop that closed but is too small or too large for the drawing is still not a card
            // picture; the refusal is the renderer's rule (10 cm .. 200 m) and is not second-guessed
            // here.
            is FloorplanSvg.Output.Refused -> null
        }
    }

    /**
     * Floor area of a loop, square metres, from the same `PolygonMath.area` the rest of the app
     * measures with - so "largest loop" and the printed area cannot disagree about which shape they
     * mean.
     */
    fun areaSquareMeters(loop: PlanLoop): Float =
        PolygonMath.area(loop.points.map { Vec3(it.xMeters, 0f, it.zMeters) })

    private fun largestClosedLoop(loops: List<PlanLoop>): PlanLoop? {
        var best: PlanLoop? = null
        var bestArea = 0f
        for (loop in loops) {
            if (!loop.closed) continue
            if (loop.points.size < 3) continue
            val area = abs(areaSquareMeters(loop))
            if (area > bestArea) {
                bestArea = area
                best = loop
            }
        }
        return best
    }
}
