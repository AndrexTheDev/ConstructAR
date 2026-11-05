/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import com.constructar.core.units.LengthFormatter
import com.constructar.core.units.LengthUnit
import com.constructar.core.units.NumberText
import java.util.Locale

/**
 * The decimal mark the screens write - the reader's own, or the protocol's point when nobody said.
 *
 * `staticCompositionLocalOf` because it changes once per configuration change and never inside a
 * screen: a dynamic local would invalidate every reading of it on every recomposition of the AR
 * HUD, which is the one place in this app that cannot afford it.
 *
 * A protocol never reads this. PDF, CSV and the JSON on disk carry [NumberText.DEFAULT_MARK]
 * through `LengthFormatter`'s own default, so a file opened in another country is the same file
 * (finding 222).
 */
val LocalDecimalMark = staticCompositionLocalOf { NumberText.DEFAULT_MARK }

/**
 * The mark for this composition's locale, taken from the *configuration* rather than
 * `Locale.getDefault()`.
 *
 * The difference is per-app language: Android 13's per-app setting changes the configuration the
 * activity is created with, while `Locale.getDefault()` keeps returning the device's. A screen
 * that asked the default would stay on the phone's language while the rest of the app moved.
 */
@Composable
fun rememberDecimalMark(): Char {
    val configuration = LocalConfiguration.current
    val locales = configuration.locales
    val locale: Locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
    return remember(locale) { NumberText.decimalMark(locale) }
}

/** `LengthFormatter.number` with this screen's decimal mark. */
@Composable
fun shownLength(
    meters: Float,
    unit: LengthUnit = LengthUnit.METER,
    decimals: Int = unit.defaultDecimals,
): String = LengthFormatter.number(meters, unit, decimals, LocalDecimalMark.current)

/** `LengthFormatter.format` (digits and unit) with this screen's decimal mark. */
@Composable
fun shownLengthWithUnit(
    meters: Float,
    unit: LengthUnit = LengthUnit.METER,
    decimals: Int = unit.defaultDecimals,
): String = LengthFormatter.format(meters, unit, decimals, LocalDecimalMark.current)
