/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import com.constructar.core.math.PolygonMath
import com.constructar.core.math.Vec3
import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.SketchPoint
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
     * The same room as a [FloorplanSketch] - the form the project file stores and the card draws.
     *
     * Same loop, same corners, same order as [outline]: this is not a second choice about which
     * loop is the room, it is the same choice written in the model the JSON carries.
     */
    fun sketch(payload: FloorplanPayload): FloorplanSketch? {
        val best = largestClosedLoop(payload.loops) ?: return null
        if (best.points.size < 3 || best.points.size > FloorplanSketch.MAX_POINTS) return null
        val points = best.points.map { SketchPoint(it.xMeters, it.zMeters) }
        // An open ring is kept, marked as open: it is not a picture and not a size, but it is the
        // only record of where the walls were, and the only thing the operator can close by hand
        // later (finding 203). Nothing is invented here - `closed` is the vectoriser's own word.
        val closure = if (best.closed) FloorplanSketch.Closure.Measured else FloorplanSketch.Closure.Open
        return FloorplanSketch(points, closure)
    }

    /**
     * The SVG for a stored outline - the same ring the card draws, for the world outside the phone.
     *
     * Takes the sketch rather than the payload because a ring the operator closed by hand exists as
     * a sketch and has no payload any more: the volume is gone by then. One ring, two artefacts.
     */
    fun svg(sketch: FloorplanSketch, options: FloorplanSvg.Options = FloorplanSvg.Options()): String? {
        if (!sketch.isDrawable) return null
        val corners = sketch.points.map { FloorplanSvg.Corner(it.xMeters, it.zMeters) }
        return when (val rendered = FloorplanSvg.render(corners, options)) {
            is FloorplanSvg.Output.Drawn -> rendered.drawing.svg
            is FloorplanSvg.Output.Refused -> null
        }
    }

    /**
     * The outline projected into a square box, in pixels: one scale for both axes, centred, world
     * `z` running **down** the screen.
     *
     * The same projection `FloorplanSvg` uses, and it has to be: the card and the exported picture
     * are the same room, and a second convention would draw one of them mirrored. Kept here rather
     * than in the composable so it can be asserted without a screen - the numbers in the test are
     * the numbers `FloorplanSvgTest` pins (a 4 x 4 m room in a 320 px box with 16 px of padding is
     * 16 and 304).
     *
     * Returns an empty list when there is nothing drawable, so a caller can decide what a card
     * without a picture looks like instead of drawing a dot.
     */
    fun pathPoints(
        sketch: FloorplanSketch,
        sizePx: Float,
        paddingPx: Float,
    ): List<Pair<Float, Float>> {
        if (!sketch.isDrawable) return emptyList()
        val inner = sizePx - 2f * paddingPx
        if (!(inner > 0f)) return emptyList()

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (point in sketch.points) {
            if (!point.xMeters.isFinite() || !point.zMeters.isFinite()) return emptyList()
            if (point.xMeters < minX) minX = point.xMeters
            if (point.xMeters > maxX) maxX = point.xMeters
            if (point.zMeters < minZ) minZ = point.zMeters
            if (point.zMeters > maxZ) maxZ = point.zMeters
        }
        val width = maxX - minX
        val depth = maxZ - minZ
        val longest = if (width > depth) width else depth
        if (!(longest > 0f)) return emptyList()

        val scale = inner / longest
        val offsetX = (sizePx - width * scale) / 2f
        val offsetY = (sizePx - depth * scale) / 2f
        return sketch.points.map { point ->
            val x = offsetX + (point.xMeters - minX) * scale
            val y = offsetY + (point.zMeters - minZ) * scale
            x to y
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
