/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * How the measuring screen divides a screen that is too small for everything it wants to show
 * (finding 257).
 *
 * The first run on a real phone showed the screen as one pile of overlapping panels. Two causes,
 * both arithmetic:
 *
 * * the action stack at the bottom (five spec chips, three button rows, add point, calibrate, loop
 *   check, marker check) was laid out at its natural height, which is taller than a phone. Because
 *   it is aligned to the bottom, it simply grew *upwards*, over the status bar - and over the
 *   export and close icons that live in it, which is why they stopped responding;
 * * a row of three buttons on a 360 dp screen with a large system font gives each button ~103 dp of
 *   width, of which the icon, the paddings and the gaps take most - "Close loop" then wrapped to
 *   one character per line.
 *
 * So the screen is given a budget instead of a wish: the status bar may take at most
 * [statusMaxHeightDp] and the action sheet at most [sheetMaxHeightDp], both of them scroll inside
 * that, and a row of buttons becomes a stack when [columns] says the buttons would not be readable
 * side by side. Nothing here is geometry or measurement - it is arithmetic on the two numbers the
 * layout already has (screen height, font scale), which is what makes it testable without a phone.
 *
 * The invariant the tests pin down: the two budgets together never claim more than
 * [MAX_TOTAL_FRACTION] of the screen, so there is always camera left between them.
 */
internal object HudLayout {

    /** The screen's own horizontal padding, both sides, as used by the action sheet. */
    const val SCREEN_PADDING_DP: Float = 16f

    /** The gap between two buttons in one row. */
    const val GAP_DP: Float = 10f

    /**
     * The narrowest button whose label still fits on a line.
     *
     * At the shipped label size "Close loop" needs ~92 dp including icon and paddings; below this
     * it wraps per character, which is what the device showed.
     */
    const val MIN_BUTTON_WIDTH_DP: Float = 96f

    /** Share of the screen the action sheet asks for at normal font size. */
    const val SHEET_FRACTION: Float = 0.46f

    /** ...and the most it may ever take, however large the system font is. */
    const val SHEET_MAX_FRACTION: Float = 0.62f

    /** Share of the screen the status bar asks for at normal font size. */
    const val STATUS_FRACTION: Float = 0.30f

    /** The status bar and the sheet together never claim more than this. */
    const val MAX_TOTAL_FRACTION: Float = 0.92f

    /** A status bar below this is not worth composing; the camera is worth more. */
    const val MIN_STATUS_DP: Float = 64f

    /** An action sheet below this cannot show even the primary button, so it keeps this floor. */
    const val MIN_SHEET_DP: Float = 180f

    /** The system's font scale is clamped here: 2.0 is the largest Android offers. */
    private const val MAX_FONT_SCALE: Float = 2f

    /**
     * How many of [buttonCount] buttons may sit side by side in [widthDp].
     *
     * Only `1` or `buttonCount`: a row of two and a row of one would look like a mistake, and the
     * stacked layout is the one that keeps every label readable.
     */
    fun columns(widthDp: Float, buttonCount: Int, fontScale: Float): Int {
        if (buttonCount <= 1) return 1
        val gaps = GAP_DP * (buttonCount - 1)
        val available = widthDp - SCREEN_PADDING_DP * 2f - gaps
        if (available <= 0f) return 1
        val needed = MIN_BUTTON_WIDTH_DP * fontScale.coerceIn(1f, MAX_FONT_SCALE)
        return if (available / buttonCount >= needed) buttonCount else 1
    }

    /**
     * The height the action sheet may take.
     *
     * A larger system font grows the sheet a little - the buttons have to fit - but never past
     * [SHEET_MAX_FRACTION], because the camera is the point of the screen.
     */
    fun sheetMaxHeightDp(screenHeightDp: Float, fontScale: Float): Float {
        val growth = fontScale.coerceIn(1f, MAX_FONT_SCALE) - 1f
        val wanted = screenHeightDp * (SHEET_FRACTION + growth * 0.06f)
        val floor = minOf(MIN_SHEET_DP, screenHeightDp * 0.5f)
        val ceiling = maxOf(floor, screenHeightDp * SHEET_MAX_FRACTION)
        return wanted.coerceIn(floor, ceiling)
    }

    /**
     * The height the status bar may take.
     *
     * Derived from what the sheet leaves: the header row (length, offline badge, export, close) is
     * pinned and *not* part of this budget, so the two icons stay reachable whatever else grows.
     */
    fun statusMaxHeightDp(screenHeightDp: Float, fontScale: Float): Float {
        val growth = fontScale.coerceIn(1f, MAX_FONT_SCALE) - 1f
        val wanted = screenHeightDp * (STATUS_FRACTION + growth * 0.05f)
        val ceiling = screenHeightDp * MAX_TOTAL_FRACTION -
            sheetMaxHeightDp(screenHeightDp, fontScale)
        val floor = minOf(MIN_STATUS_DP, screenHeightDp * 0.2f)
        return wanted.coerceIn(floor, maxOf(floor, ceiling))
    }
}
