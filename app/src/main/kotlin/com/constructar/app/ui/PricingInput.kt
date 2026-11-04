/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.price.Pricing

/**
 * What a typed amount means: a number, or nothing at all - and when it is neither.
 *
 * Finding 43: both cost fields read `Pricing.parseCents(text) ?: 0` (or `?.takeIf { it > 0 }`) and
 * carried on, so a mistyped amount was not an error but a *value*. A gloved finger adding one
 * character to `250,00` turned the material lump sum into `0` with the total falling by 250 € on
 * the line above the button that saves it, and `48,` in the firm-rate field - the state of every
 * keypress halfway through typing a rate - reset the firm rate to the default.
 *
 * The rule here: blank is a deliberate "not set" and stays silent; anything that does not parse is
 * flagged, keeps the previous value, and blocks the save. Nothing is guessed into a number.
 */
object PricingInput {

    /**
     * An hourly rate field.
     *
     * [cents] is `null` for "use the firm rate" - which is what a blank field means. [invalid] is
     * true when the operator typed something that is not a positive amount, so the field can say
     * so instead of quietly billing at a rate he did not choose.
     */
    data class Rate(val cents: Int?, val invalid: Boolean)

    /** A material lump sum: blank is `0`, and unparsable input is flagged, never read as zero. */
    data class Amount(val cents: Long, val invalid: Boolean)

    fun rate(text: String): Rate {
        if (text.isBlank()) return Rate(cents = null, invalid = false)
        val cents = Pricing.parseCents(text)
        // A rate of zero cannot be billed; the field's hint says what a blank field does instead.
        if (cents == null || cents <= 0L || cents > Int.MAX_VALUE) return Rate(cents = null, invalid = true)
        return Rate(cents = cents.toInt(), invalid = false)
    }

    fun amount(text: String): Amount {
        if (text.isBlank()) return Amount(cents = 0L, invalid = false)
        val cents = Pricing.parseCents(text)
        return if (cents == null || cents < 0L) Amount(cents = 0L, invalid = true) else Amount(cents, invalid = false)
    }
}
