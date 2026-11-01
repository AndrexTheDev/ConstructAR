/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.price

import java.util.Currency
import java.util.Locale
import java.text.NumberFormat

/**
 * What the job is worth: hours × rate, plus whatever the operator adds for material.
 *
 * Deliberately not a quotation engine. A tradesman does not want a second ERP on site - he wants
 * the two numbers he would otherwise type into a spreadsheet at ten in the evening: how long was
 * I there, and what does that come to. Everything here is integer minor units (euro cents), so
 * the arithmetic cannot drift by a cent between screen, PDF and CSV.
 */
object Pricing {

    /** 48,00 € an hour, the sort of figure a German handwerk shop bills a journeyman at. */
    const val DEFAULT_HOURLY_RATE_CENTS: Int = 4_800

    private const val MILLIS_PER_HOUR = 3_600_000.0

    /**
     * Labour for [workMillis] at [hourlyRateCents], rounded to the nearest cent.
     *
     * Rounded once, at the end: accumulating rounded minutes is how timesheets end up two euros
     * off the invoice.
     */
    fun labourCents(workMillis: Long, hourlyRateCents: Int): Long {
        if (workMillis <= 0L || hourlyRateCents <= 0) return 0L
        return Math.round(workMillis / MILLIS_PER_HOUR * hourlyRateCents)
    }

    /** Net total: labour plus the material lump sum. No VAT, no discount, no surprises. */
    fun totalCents(labourCents: Long, materialCents: Long): Long =
        labourCents.coerceAtLeast(0L) + materialCents.coerceAtLeast(0L)

    /**
     * Parses operator input into cents: `48`, `48,50`, `48.50`, `1.250,00`, ` 48 € `.
     *
     * Both separators are accepted because a German phone's keyboard offers a comma and an
     * English one a dot, and nobody should have to think about which one this field wants. A
     * string with two of the same separator is read as a thousands grouping.
     */
    fun parseCents(input: String): Long? {
        val cleaned = input.trim()
            .replace("€", "")
            .replace(" ", "")
            .replace("\u00A0", "")
        if (cleaned.isEmpty()) return null

        // The last separator is the decimal one if it is followed by one or two digits;
        // otherwise every separator is thousands grouping. "1.250" therefore means one
        // thousand two hundred fifty, which is what a German keyboard-intuitive field should do.
        val separatorIndex = maxOf(cleaned.lastIndexOf(','), cleaned.lastIndexOf('.'))
        val tail = if (separatorIndex < 0) "" else cleaned.substring(separatorIndex + 1)
        val tailIsFraction = tail.length in 1..2 && tail.all { it.isDigit() }
        val wholeText = if (tailIsFraction) cleaned.substring(0, separatorIndex) else cleaned
        val fraction = if (tailIsFraction) tail else ""

        val wholeDigits = stripGrouping(wholeText) ?: return null
        val cents = fraction.padEnd(2, '0').ifEmpty { "00" }
        return runCatching { wholeDigits.toLong() * 100L + cents.toLong() }.getOrNull()
    }

    /**
     * Removes thousands separators, or returns `null` when the grouping is impossible.
     *
     * `"1.250"` is fine, `"12,3,4"` is not: a group after a separator must be exactly three
     * digits. Rejecting is the whole point - guessing would turn a typo into a rate.
     */
    private fun stripGrouping(text: String): String? {
        if (text.isEmpty()) return null
        val groups = text.split(',', '.')
        if (groups.any { group -> group.isEmpty() || !group.all { it.isDigit() } }) return null
        if (groups.size == 1) return groups[0]
        if (groups.first().length !in 1..3) return null
        if (groups.drop(1).any { it.length != 3 }) return null
        return groups.joinToString("")
    }

    /** `48,50` → `4850` for a text field; the inverse of [parseCents] with a comma. */
    fun formatForInput(cents: Long): String =
        String.format(Locale.ROOT, "%d,%02d", cents / 100L, cents % 100L)
}

/**
 * Money as the operator's phone writes it - always in euro, never in the local currency.
 *
 * `NumberFormat` gives the right grouping and decimal mark for the locale (`1.250,00 €` in
 * German, `1 250,00 €` in French), but the currency has to be forced: on a Polish phone the
 * default would be złoty, and this app prices in euro.
 */
object MoneyFormatter {

    private val euro: Currency = Currency.getInstance("EUR")

    fun format(cents: Long, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        format.currency = euro
        return format.format(cents / 100.0)
    }
}
