/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.units

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The decimal mark a **screen** writes, and the digits around it.
 *
 * Two surfaces, two rules, and they must not be the same object. A document (PDF, CSV, JSON) is a
 * protocol: its decimal mark is `.` in every language because a spreadsheet in Warsaw and one in
 * Madrid have to open the same file, and because a comma there is a column separator. A *screen* is
 * prose: a German fitter reading `1.40 m` on his own phone knows exactly what it says, but he also
 * knows that nobody in his trade writes it that way, and the mismatch is the kind of small wrongness
 * that makes a careful user trust the numbers less than they deserve.
 *
 * `LengthFormatter` stays the protocol's formatter (`Locale.ROOT` everywhere). This type is the one
 * place a locale's mark is allowed to exist, and `:app` passes it in - `:core` never asks the phone
 * what language it is in (finding 222).
 */
object NumberText {

    /**
     * The mark [locale] writes between a whole number and its fraction: `,` in de/fr/it/es/pl,
     * `.` in en.
     *
     * Guarded rather than trusted: a locale whose separator is a digit or a letter (or whose
     * symbols cannot be read at all) would turn `1.40` into `1140` on the screen, so anything that
     * is not punctuation falls back to the point this app has printed for fifteen rounds.
     */
    fun decimalMark(locale: Locale = Locale.getDefault()): Char {
        val mark = runCatching { DecimalFormatSymbols.getInstance(locale).decimalSeparator }
            .getOrNull() ?: return DEFAULT_MARK
        return if (mark.isDigit() || mark.isLetter() || mark.isWhitespace()) DEFAULT_MARK else mark
    }

    /**
     * [value] to [decimals] decimal places, rounded, with [mark] as the separator.
     *
     * Rounding stays `String.format` on the binary value that is stored, exactly like
     * [LengthFormatter.number]: the boundary `0.994f` → `0.99` / `0.995f` → `1.00` is tested there
     * and there is no second definition of "rounded" here - only the character that stands between
     * the whole part and the fraction changes.
     */
    fun digits(value: Float, decimals: Int, mark: Char = DEFAULT_MARK): String {
        if (!value.isFinite()) return "—"
        val pattern = "%." + decimals.coerceIn(0, 3) + "f"
        val text = String.format(Locale.ROOT, pattern, value)
        return if (mark == DEFAULT_MARK) text else text.replace(DEFAULT_MARK, mark)
    }

    /** The point this app prints when nobody told it a language - and what the documents carry. */
    const val DEFAULT_MARK: Char = '.'
}
