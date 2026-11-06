/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * The two jobs this screen does, kept apart on purpose (finding 267).
 *
 * The device report was: *"man sieht nicht, was gescannt ist"* — and, in the same breath, that a
 * screen which sets measuring points and a screen that captures a room are not the same thing and
 * the app never said which one it was doing. Both were true. There was one mode, and it was
 * ambiguous in every direction: the mesh overlay sat behind a small `Scan room` toggle inside a row
 * of tool buttons, drawn in cool grey at 40 % over a wall in daylight; tapping the screen always
 * dropped a point, whether the operator was walking the room or standing at a socket; and nothing
 * on the glass said whether the teal-grey surface he was looking at was new, stale, or his own
 * drawing.
 *
 * So there are exactly two modes, and the rule is that **each mode owns its own controls, its own
 * lines and its own colour**:
 *
 * * [MEASURE] — a point for a point, placed by hand. This is the mode the run is built in: two
 *   points are a distance, more are a polyline, and every point later carries a cable. No room
 *   overlay (it would sit on top of the thing being measured), no scan sentence, no scan gauge.
 * * [CAPTURE] — a walkthrough. The operator walks, the app reconstructs the room, and the only
 *   control that matters is his feet. Points are **not** set in this mode; the overlay is always
 *   on, in the colour the legend names, and the gauge is pinned rather than scrolling.
 *
 * The rules live here and in [MeasureGuide], not in the composable, because "the two modes never
 * show each other's instructions" is a property that has to be asserted — see `MeasureModeTest`.
 */
internal enum class MeasureMode {

    /** Point by point, by hand. Each point is a point on a cable run. */
    MEASURE,

    /** A walkthrough: the app reconstructs the room by itself while the operator walks. */
    CAPTURE,
    ;

    /** True for the mode that draws the reconstructed room. Exactly one mode does. */
    val showsRoomOverlay: Boolean get() = this == CAPTURE

    /** True for the mode that sets measuring points by hand. Exactly one mode does. */
    val setsPoints: Boolean get() = this == MEASURE
}

/**
 * The colour of the room reconstruction, in one place, for the one reason that matters.
 *
 * The legend on the glass says "violet = captured", and the surface it explains is drawn by
 * `SceneViewMaterialProvider.SCAN_OVERLAY_ARGB` in `:ar-engine`. Two constants in two modules that
 * are supposed to be the same colour is exactly the kind of promise that quietly stops being true,
 * so a test asserts they are equal and the legend cannot drift away from the mesh.
 *
 * Opaque here, translucent in the engine: the swatch in the legend shows the hue, the overlay is
 * composited over a live camera image and must not hide the wall behind it.
 */
internal object ModeStyle {

    /** The hue of the room scan: a violet that no service colour in the palette uses. */
    const val CAPTURE_ARGB: Int = 0xFFA25BFF.toInt()

    /** The room overlay as it is drawn over the camera: the same hue at half opacity. */
    const val CAPTURE_OVERLAY_ARGB: Int = 0x99A25BFF.toInt()
}
