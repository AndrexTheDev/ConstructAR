/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.svg

import com.constructar.core.math.PolygonMath
import com.constructar.core.math.Vec3
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Vector thumbnail of a room outline, as a standalone SVG file.
 *
 * The home screen shows one card per room, and a card needs a picture of *that* room - a JPEG of
 * the camera frame is the wrong answer twice over: it carries the customer's kitchen into a file
 * list, and it is 40 kB of pixels to say "4 x 3 m". An SVG is a few hundred bytes of polygons, it
 * stays sharp when the card scales up on a tablet, and it is drawn from the same numbers the
 * protocol prints.
 *
 * ## The plan projection
 *
 * World `x` runs to the right of the image and world `z` runs **down**, which is what looking down
 * at a floor from above gives you on a right-handed `y`-up frame: an operator who walked the wall
 * on the left of the corridor sees it on the left of the card. The two axes are scaled by one
 * factor, never two - a room drawn with a stretch is a room whose opposite walls are not parallel -
 * and the drawing is centred in the square so a long thin room does not sit in one corner.
 *
 * Every number is written with `Locale.ROOT`. `String.format("%.2f", …)` under a German default
 * locale writes `0,50`, and a comma inside an SVG `points` list is a *separator*: the picture
 * silently becomes a different polygon, with no error anywhere, and the only reader who notices is
 * the customer looking at a room that is not theirs.
 *
 * ## What this file does not do
 *
 * No text, and therefore no `role`/`title` either. `:export` receives numbers and identifiers;
 * every word a person reads is a string resource owned by `:app` (finding 100's rule). A
 * `role="img"` without a `<title>` is announced by a screen reader as a nameless image, which is
 * worse than silence for the operator navigating the card list - the card prints the room's name
 * next to this drawing, so the card is the accessible element and the file stays decoration. That
 * is also why a refusal is an enum and not a sentence: the caller decides what to tell the
 * operator, in the operator's language.
 */
object FloorplanSvg {

    /**
     * One corner of the room in the world's floor plane, in metres.
     *
     * `x` and `z` are the world axes of the AR session, not an image's axes; the projection above
     * is what turns them into pixels.
     */
    data class Corner(val x: Float, val z: Float)

    data class Options(
        /** Square canvas, in pixels — the card's thumbnail slot. */
        val sizePx: Int = 320,
        /** Blank border, so the wall line is never clipped by the viewport edge. */
        val paddingPx: Float = 16f,
        /**
         * Wall thickness in **metres**, drawn to scale.
         *
         * A 10 cm line is what a fitter recognises as a wall, and it is a physical number rather
         * than a pixel count on purpose: the same room at 320 px and at 960 px must look the same,
         * and a 20 m corridor must not have walls as thick as a 2 m bathroom's.
         */
        val wallThicknessMeters: Float = 0.10f,
        /** Hairline floor so the card reads as a room, not an outline floating in the dark. */
        val floorColor: String = "#F2EEE5",
        val wallColor: String = "#1B1B1B",
    )

    /** Why a set of corners could not be drawn. The caller owns the wording. */
    enum class Rejection {
        /** Fewer than three corners: there is no room. */
        TOO_FEW_CORNERS,

        /** A NaN or an infinity arrived — a broken session, not a small room. */
        NOT_FINITE,

        /** A room whose longer side is under [MIN_SIDE_METERS] is a point, not a plan. */
        DEGENERATE,

        /** Beyond [MAX_SIDE_METERS] the number is a bug (an uninitialised pose), not a building. */
        UNREASONABLE_SCALE,

        /** The padding leaves no canvas: `sizePx <= 2 * paddingPx`. */
        CANVAS_TOO_SMALL,
    }

    /** A drawn thumbnail and the figures the card's badges print. */
    data class Drawing(
        val svg: String,
        /** Extent along world x, metres. */
        val widthMeters: Float,
        /** Extent along world z, metres. */
        val depthMeters: Float,
        /** Floor area from the outline, square metres (`PolygonMath.area`). */
        val areaSquareMeters: Float,
        /** Outline length, metres — the skirting board, not the wall's two faces. */
        val perimeterMeters: Float,
        /** The single scale factor the drawing used. */
        val pixelsPerMeter: Float,
    )

    /**
     * What [render] produced. Not called `Result` on purpose: `kotlin.Result` is imported into
     * every file by the compiler, and a nested type with that name is a name clash waiting for the
     * one caller who writes a bare `Result`.
     */
    sealed interface Output {
        data class Drawn(val drawing: Drawing) : Output
        data class Refused(val reason: Rejection) : Output
    }

    /** Shorter than this, the outline is a point: no plan is drawn. */
    const val MIN_SIDE_METERS: Float = 0.10f

    /**
     * Longer than this, the coordinates are wrong rather than large.
     *
     * The longest building this app is sold for is a few hundred metres; a pose that failed to
     * initialise reports values in the thousands, and a canvas scaled to that draws every real
     * room as one dark pixel.
     */
    const val MAX_SIDE_METERS: Float = 200f

    /** A wall never disappears, however large the room: 1 px of ink on the smallest canvases. */
    private const val MIN_WALL_PX = 1f

    fun render(corners: List<Corner>, options: Options = Options()): Output {
        if (corners.size < 3) return Output.Refused(Rejection.TOO_FEW_CORNERS)
        for (corner in corners) {
            if (!corner.x.isFinite() || !corner.z.isFinite()) {
                return Output.Refused(Rejection.NOT_FINITE)
            }
        }

        // A closed run usually repeats its first corner; drawn as-is that is a zero-length edge,
        // and a miter joint on it produces a spike out of the wall.
        val outline = if (sameCorner(corners.first(), corners.last())) corners.dropLast(1) else corners
        if (outline.size < 3) return Output.Refused(Rejection.TOO_FEW_CORNERS)

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (corner in outline) {
            minX = min(minX, corner.x)
            maxX = max(maxX, corner.x)
            minZ = min(minZ, corner.z)
            maxZ = max(maxZ, corner.z)
        }
        val widthMeters = maxX - minX
        val depthMeters = maxZ - minZ
        val longest = max(widthMeters, depthMeters)
        if (longest < MIN_SIDE_METERS) return Output.Refused(Rejection.DEGENERATE)
        if (longest > MAX_SIDE_METERS) return Output.Refused(Rejection.UNREASONABLE_SCALE)

        val inner = options.sizePx - 2f * options.paddingPx
        if (inner <= 0f) return Output.Refused(Rejection.CANVAS_TOO_SMALL)

        val pixelsPerMeter = inner / longest
        // One factor for both axes, then centre whatever is left over.
        val offsetX = (options.sizePx - widthMeters * pixelsPerMeter) / 2f
        val offsetY = (options.sizePx - depthMeters * pixelsPerMeter) / 2f

        val points = StringBuilder()
        for (corner in outline) {
            if (points.isNotEmpty()) points.append(' ')
            points.append(format(offsetX + (corner.x - minX) * pixelsPerMeter))
            points.append(' ')
            points.append(format(offsetY + (corner.z - minZ) * pixelsPerMeter))
        }

        val wallPx = max(options.wallThicknessMeters * pixelsPerMeter, MIN_WALL_PX)
        val size = options.sizePx
        val svg = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" ")
            append("width=\"").append(size).append("\" height=\"").append(size).append("\" ")
            append("viewBox=\"0 0 ").append(size).append(' ').append(size).append("\">\n")
            append("<polygon points=\"").append(points).append("\" ")
            append("fill=\"").append(options.floorColor).append("\" ")
            append("stroke=\"").append(options.wallColor).append("\" ")
            append("stroke-width=\"").append(format(wallPx)).append("\" ")
            // Miter joints keep a right-angled room reading as a right-angled room; a round join
            // rounds every corner of every room into the same soft blob.
            append("stroke-linejoin=\"miter\" stroke-miterlimit=\"4\"/>\n")
            append("</svg>\n")
        }

        val outline3d = outline.map { Vec3(it.x, 0f, it.z) }
        return Output.Drawn(
            Drawing(
                svg = svg,
                widthMeters = widthMeters,
                depthMeters = depthMeters,
                areaSquareMeters = PolygonMath.area(outline3d),
                perimeterMeters = perimeter(outline),
                pixelsPerMeter = pixelsPerMeter,
            ),
        )
    }

    private fun perimeter(outline: List<Corner>): Float {
        var total = 0f
        for (index in outline.indices) {
            val a = outline[index]
            val b = outline[(index + 1) % outline.size]
            total += sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z))
        }
        return total
    }

    private fun sameCorner(a: Corner, b: Corner): Boolean =
        abs(a.x - b.x) < 1e-4f && abs(a.z - b.z) < 1e-4f

    /**
     * Two decimals in the C locale, never `-0.00`.
     *
     * `-0.00` is a valid float that a reader mistakes for a bug in the file, and it appears
     * whenever a coordinate sits a hair below the origin.
     */
    private fun format(value: Float): String {
        val text = String.format(Locale.ROOT, "%.2f", value)
        return if (text == "-0.00") "0.00" else text
    }
}
