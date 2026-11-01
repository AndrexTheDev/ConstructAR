/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The theme, measured instead of eyeballed.
 *
 * This app is used with gloves, in sunlight, on a building site. A colour pair that looks fine
 * on a designer's monitor at night is unreadable on a phone held at arm's length over a ladder,
 * and "it looked okay" is not a review anybody can repeat. So the contrast ratios are computed
 * here with the WCAG 2.1 formula and asserted: 4.5:1 for every pair the UI uses for text, 3:1
 * for the accent pairs that carry large numbers only.
 *
 * The palette is deliberately *not* asserted to be pretty, and the test does not compare against
 * stored screenshots — it asserts the one property a screenshot cannot show.
 */
class ContrastTest {

    /** WCAG 2.1 relative luminance. */
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

    private fun assertAa(name: String, foreground: Color, background: Color) {
        val measured = ratio(foreground, background)
        assertTrue(measured >= 4.5, "$name is $measured:1, below the 4.5:1 WCAG AA floor")
    }

    @Test
    fun `body and heading text is AA on every surface it is drawn on`() {
        assertAa("Chalk on Ink", Jamaica.Chalk, Jamaica.Ink)
        assertAa("Chalk on InkElevated", Jamaica.Chalk, Jamaica.InkElevated)
        assertAa("Chalk on SurfaceHigh", Jamaica.Chalk, Jamaica.SurfaceHigh)
        assertAa("Ash (secondary) on Ink", Jamaica.Ash, Jamaica.Ink)
        assertAa("Ash (secondary) on InkElevated", Jamaica.Ash, Jamaica.InkElevated)
        assertAa("Ash (secondary) on SurfaceHigh", Jamaica.Ash, Jamaica.SurfaceHigh)
    }

    @Test
    fun `the accent colours are readable as text, not just as decoration`() {
        // The measured length, the price and the warning are all accent-coloured numbers the
        // operator has to read from a metre away.
        assertAa("Lime on Ink", Jamaica.Lime, Jamaica.Ink)
        assertAa("Lime on SurfaceHigh", Jamaica.Lime, Jamaica.SurfaceHigh)
        assertAa("Gold on Ink", Jamaica.Gold, Jamaica.Ink)
        assertAa("Gold on InkElevated", Jamaica.Gold, Jamaica.InkElevated)
        assertAa("Green on Ink", Jamaica.Green, Jamaica.Ink)
        assertAa("Warning on Ink", Jamaica.Warning, Jamaica.Ink)
        assertAa("Danger on Ink", Jamaica.Danger, Jamaica.Ink)
    }

    @Test
    fun `filled buttons keep their label readable`() {
        // The dark ink that sits on a lime or gold fill is the same trick the site uses.
        assertAa("Ink on Lime", Jamaica.Ink, Jamaica.Lime)
        assertAa("Ink on Gold", Jamaica.Ink, Jamaica.Gold)
        assertAa("Chalk on GreenDim", Jamaica.Chalk, Jamaica.GreenDim)
    }

    @Test
    fun `unused container tokens are still at least large-text contrast`() {
        // `secondaryContainer` and `tertiaryContainer` are not used by a screen today; they are
        // kept because a Material component may reach for them. 3:1 is the floor for large text
        // and for a container that is identified by its own shape, and saying so here stops a
        // future component from quietly putting body copy on one of them.
        val goldContainer = ratio(Jamaica.Chalk, Jamaica.GoldDim)
        assertTrue(goldContainer >= 3.0, "Chalk on GoldDim is $goldContainer:1, below 3:1")
        val greenContainer = ratio(Jamaica.Chalk, Jamaica.GreenDim)
        assertTrue(greenContainer >= 4.5, "Chalk on GreenDim is $greenContainer:1, below 4.5:1")
    }

    @Test
    fun `the three conduit colours are distinguishable from each other`() {
        // A routed cable, pipe and duct share the screen; if two of them are the same brightness
        // the legend is decoration. Compare luminance, not hue: colour-blind operators exist.
        val cable = luminance(SiteColors.cable)
        val pipe = luminance(SiteColors.pipe)
        val duct = luminance(SiteColors.duct)
        val pairs = listOf(
            "cable/pipe" to ratio(SiteColors.cable, SiteColors.pipe),
            "cable/duct" to ratio(SiteColors.cable, SiteColors.duct),
            "pipe/duct" to ratio(SiteColors.pipe, SiteColors.duct),
        )
        for ((name, value) in pairs) {
            assertTrue(value >= 1.25, "$name are too close in luminance ($value:1)")
        }
        assertTrue(setOf(cable, pipe, duct).size == 3)
    }
}
