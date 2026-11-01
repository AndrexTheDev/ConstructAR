/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import androidx.compose.ui.graphics.Color
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.model.ConduitPresets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The AR overlay draws over a **camera image**, so its legibility is not a property of the theme -
 * it depends on what the phone is pointed at, and the theme's `ContrastTest` only compares opaque
 * palette pairs. That left the HUD with no coverage at all.
 *
 * It had drifted exactly as far as unguarded text does: the secondary lines sat at 3.47:1 and the
 * hit diagnostics at 2.81:1 against a white wall (the ordinary case: plastered wall, sunlight), and
 * the selected conduit chip was white text on its own spec colour - **1.4:1** on orange, gold or
 * teal, which means the one chip the worker had just tapped was the one he could not read.
 *
 * These tests composite the real alphas over a white and a black camera and assert the same floor
 * the rest of the app uses: 4.5:1 for text, and no dependence on the camera at all for the chips.
 */
class HudContrastTest {

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** `top` at [alpha] painted over an opaque [bottom] - the same thing the GPU does. */
    private fun over(top: Color, alpha: Float, bottom: Color): Color {
        val a = alpha.toDouble()
        return Color(
            red = (a * top.red + (1 - a) * bottom.red).toFloat(),
            green = (a * top.green + (1 - a) * bottom.green).toFloat(),
            blue = (a * top.blue + (1 - a) * bottom.blue).toFloat(),
            alpha = 1f,
        )
    }

    /** A white camera frame is the worst case: sunlight on plaster, a painted ceiling, a white van. */
    private val whiteWall = Color.White

    /** The other extreme, a dark basement, only to prove the fix does not trade one for the other. */
    private val darkWall = Color(0xFF191919)

    private fun panelOver(camera: Color) = over(Color.Black, HudStyle.PANEL_ALPHA, camera)

    private fun textOver(camera: Color) = over(Color.White, HudStyle.TEXT_ALPHA, panelOver(camera))

    private fun hintOver(camera: Color) = over(Color.White, HudStyle.HINT_ALPHA, panelOver(camera))

    /** The panel + running total, the lines a worker reads without taking the phone off the wall. */
    @Test
    fun hudTextIsReadableOverABrightWall() {
        val panel = panelOver(whiteWall)
        assertTrue(ratio(textOver(whiteWall), panel) >= 4.5, "HUD text on white wall: ${ratio(textOver(whiteWall), panel)}")
        assertTrue(ratio(hintOver(whiteWall), panel) >= 4.5, "hit hint on white wall: ${ratio(hintOver(whiteWall), panel)}")
    }

    @Test
    fun hudTextIsReadableOverADarkWall() {
        val panel = panelOver(darkWall)
        assertTrue(ratio(textOver(darkWall), panel) >= 4.5, "HUD text on dark wall: ${ratio(textOver(darkWall), panel)}")
        assertTrue(ratio(hintOver(darkWall), panel) >= 4.5, "hit hint on dark wall: ${ratio(hintOver(darkWall), panel)}")
    }

    /** The gold length label and the lime segment counter sit on the same panel. */
    @Test
    fun accentNumbersAreReadableOverABrightWall() {
        val panel = panelOver(whiteWall)
        assertTrue(ratio(SiteColors.cable, panel) >= 4.5, "gold length label: ${ratio(SiteColors.cable, panel)}")
        assertTrue(ratio(Jamaica.Lime, panel) >= 4.5, "lime segment counter: ${ratio(Jamaica.Lime, panel)}")
    }

    /**
     * Every preset chip must be readable in both states, over any camera, and by construction rather
     * than by luck: the selected fill is opaque, so its contrast cannot move with the scene.
     */
    @Test
    fun everyConduitChipCarriesInk() {
        for (spec in ConduitPresets.all) {
            val fill = Color(spec.colorArgb)
            assertTrue(fill.alpha == 1f, "${spec.label}: chip fill must be opaque")
            val r = ratio(Jamaica.Ink, fill)
            assertTrue(r >= 4.5, "${spec.label}: Ink on chip measures $r")
        }
    }

    /** The unselected chip is a translucent panel over the camera, so measure that composite too. */
    @Test
    fun unselectedConduitChipIsReadableOverABrightWall() {
        val panel = panelOver(whiteWall)
        assertTrue(ratio(textOver(whiteWall), panel) >= 4.5)
        // and the panel itself must be distinguishable from a white wall as a shape (3:1 non-text)
        assertTrue(ratio(panel, whiteWall) >= 3.0, "chip outline on white wall: ${ratio(panel, whiteWall)}")
    }
}
