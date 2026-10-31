/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ConstructAR theme — the Jamaica palette: flag black, yellow-green, gold and flag green.
 *
 * The palette is not decoration, it is a legibility decision. On a site the phone is read in
 * two impossible conditions: direct sun on a scaffold, and a basement lit by a work lamp. Both
 * need a near-black surface with a *saturated* accent that survives glare — hence a yellow-green
 * primary (the highest-luminance hue that still reads as "green" to a colour-blind eye) and gold
 * as the second accent, which is the classic site-signal pair: yellow-green = act now, gold =
 * measured value, green = confirmed/saved, red = stop.
 *
 * Everything is dark. There is no light theme on purpose: an AR viewfinder with a white chrome
 * halves the contrast of the camera image and burns battery on OLED, and the operator's eyes
 * are adapted to a dim room.
 *
 * Ergonomics are unchanged from the site-first layout: every interactive element is at least
 * 64 dp tall, numbers are the largest type on screen, and primary actions sit in the thumb zone.
 */

/** Raw palette. Only [SiteColors] and the scheme below should be referenced elsewhere. */
object Jamaica {
    /** Window background: black with the faintest green cast so pure black stays for content. */
    val Ink = Color(0xFF0A0C07)

    /** Cards and sheets. */
    val InkElevated = Color(0xFF14180E)

    /** Raised control surfaces (buttons, chips, list rows). */
    val SurfaceHigh = Color(0xFF1E2314)

    /** The accent: yellow-green. Buttons, the current measurement, active steps. */
    val Lime = Color(0xFFC8F04A)

    /** Pressed/disabled lime; also the 3:1-on-ink variant for large text. */
    val LimeDim = Color(0xFF8FB22C)

    /** Gold: the Jamaica flag's cross. Measured values, premium, the second accent. */
    val Gold = Color(0xFFFFD34D)
    val GoldDim = Color(0xFFB08812)

    /** Flag green: committed routes, saved state, "everything is fine". */
    val Green = Color(0xFF00A651)
    val GreenDim = Color(0xFF0A6B38)

    /** Text. Chalk on ink is ~17:1, comfortably above WCAG AAA even through a plastic case. */
    val Chalk = Color(0xFFF3F6EA)
    val Ash = Color(0xFFB4BE9F)

    /** Borders and dividers, tuned to be visible but never loud. */
    val Wire = Color(0xFF3A4430)

    val Danger = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFC24B)
}

private val DarkScheme = darkColorScheme(
    primary = Jamaica.Lime,
    onPrimary = Color(0xFF10140A),
    primaryContainer = Jamaica.LimeDim,
    onPrimaryContainer = Color(0xFF0B0E06),
    secondary = Jamaica.Gold,
    onSecondary = Color(0xFF1A1503),
    secondaryContainer = Jamaica.GoldDim,
    onSecondaryContainer = Jamaica.Chalk,
    tertiary = Jamaica.Green,
    onTertiary = Color(0xFF04170C),
    tertiaryContainer = Jamaica.GreenDim,
    onTertiaryContainer = Jamaica.Chalk,
    background = Jamaica.Ink,
    onBackground = Jamaica.Chalk,
    surface = Jamaica.InkElevated,
    onSurface = Jamaica.Chalk,
    surfaceVariant = Jamaica.SurfaceHigh,
    onSurfaceVariant = Jamaica.Ash,
    outline = Jamaica.Wire,
    error = Jamaica.Danger,
    onError = Color(0xFF1A0505),
)

/** Type scale: roughly 1.4× Material's default, which is what gloves and sunlight need. */
private val GloveTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.6.sp),
)

/** Extra semantic colours the Material scheme does not carry. */
object SiteColors {
    val warning: Color = Jamaica.Warning
    val danger: Color = Jamaica.Danger
    val success: Color = Jamaica.Green

    /** The accent, spelled out for places that predate the Material scheme. */
    val accent: Color = Jamaica.Lime
    val gold: Color = Jamaica.Gold
    val flagGreen: Color = Jamaica.Green

    /** Route identity. Kept distinguishable in a dim room *and* for the common red/green weakness. */
    val cable: Color = Jamaica.Gold
    val pipe: Color = Color(0xFF48B0FF)
    val duct: Color = Jamaica.Green

    /** Minimum touch target for a gloved finger. */
    val gloveTouchTarget = 64.dp
}

@Composable
fun ConstructArTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = GloveTypography,
        content = content,
    )
}
