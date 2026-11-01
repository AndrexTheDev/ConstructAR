/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.price

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Money is the one number on the protocol a customer will check with a calculator, so every rule
 * of [Pricing] is pinned here: the rate, the rounding, and the two separators a European phone
 * keyboard can produce.
 */
class PricingTest {

    private val rate = Pricing.DEFAULT_HOURLY_RATE_CENTS

    // ── Labour ──────────────────────────────────────────────────────────────────────

    @Test
    fun `one hour bills exactly the rate`() {
        assertEquals(rate.toLong(), Pricing.labourCents(3_600_000L, rate))
    }

    @Test
    fun `half an hour bills half the rate`() {
        assertEquals((rate / 2).toLong(), Pricing.labourCents(1_800_000L, rate))
    }

    @Test
    fun `forty five minutes bill three quarters`() {
        assertEquals(3_600L, Pricing.labourCents(2_700_000L, rate))
    }

    @Test
    fun `a single minute rounds to the nearest cent and never drifts`() {
        // 5000 cents an hour is 83,333... cents a minute; one minute must not become 84 and then
        // sixty of them 5040.
        assertEquals(83L, Pricing.labourCents(60_000L, 5_000))
        assertEquals(5_000L, Pricing.labourCents(3_600_000L, 5_000))
    }

    @Test
    fun `no time or no rate means no labour`() {
        assertEquals(0L, Pricing.labourCents(0L, rate))
        assertEquals(0L, Pricing.labourCents(-5_000L, rate))
        assertEquals(0L, Pricing.labourCents(3_600_000L, 0))
        assertEquals(0L, Pricing.labourCents(3_600_000L, -100))
    }

    @Test
    fun `a whole eight hour day at the default rate`() {
        assertEquals(38_400L, Pricing.labourCents(8 * 3_600_000L, rate))
    }

    // ── Total ───────────────────────────────────────────────────────────────────────

    @Test
    fun `total is labour plus material`() {
        assertEquals(12_500L, Pricing.totalCents(10_000L, 2_500L))
    }

    @Test
    fun `a negative material entry cannot discount the job below zero`() {
        assertEquals(10_000L, Pricing.totalCents(10_000L, -2_500L))
        assertEquals(0L, Pricing.totalCents(-1L, -1L))
    }

    // ── Parsing ────────────────────────────────────────────────────────────────────

    @Test
    fun `a bare number is whole euros`() {
        assertEquals(4_800L, Pricing.parseCents("48"))
    }

    @Test
    fun `both decimal separators mean the same thing`() {
        assertEquals(4_850L, Pricing.parseCents("48,50"))
        assertEquals(4_850L, Pricing.parseCents("48.50"))
        assertEquals(1_250L, Pricing.parseCents("12,5"))
    }

    @Test
    fun `a thousands dot is not a decimal point`() {
        assertEquals(125_000L, Pricing.parseCents("1.250,00"))
        assertEquals(125_000L, Pricing.parseCents("1.250"))
        assertEquals(1_250_000L, Pricing.parseCents("12.500,00"))
    }

    @Test
    fun `the euro sign and stray spaces are ignored`() {
        assertEquals(4_800L, Pricing.parseCents(" 48 € "))
        assertEquals(4_800L, Pricing.parseCents("48\u00A0€"))
    }

    @Test
    fun `nonsense is rejected instead of guessed`() {
        assertNull(Pricing.parseCents(""))
        assertNull(Pricing.parseCents("   "))
        assertNull(Pricing.parseCents("abc"))
        assertNull(Pricing.parseCents(","))
        assertNull(Pricing.parseCents("12,3,4"))
    }

    // ── Formatting ─────────────────────────────────────────────────────────────────

    @Test
    fun `the input form uses a comma and two decimals`() {
        assertEquals("48,50", Pricing.formatForInput(4_850L))
        assertEquals("48,00", Pricing.formatForInput(4_800L))
        assertEquals("0,05", Pricing.formatForInput(5L))
    }

    @Test
    fun `format and parse round-trip for every value the app can produce`() {
        listOf(0L, 5L, 99L, 4_800L, 4_850L, 125_000L, 999_900L).forEach { cents ->
            assertEquals(cents, Pricing.parseCents(Pricing.formatForInput(cents)))
        }
    }

    @Test
    fun `money is always written in euro, even on a phone set to another currency`() {
        val german = MoneyFormatter.format(125_000L, Locale.GERMANY)
        assertTrue(german, german.contains("1.250,00"))
        assertTrue(german, german.contains("€"))

        // A Polish phone groups with a space and writes the euro after the amount; what must
        // never appear is the local currency the phone would otherwise have chosen.
        val polish = MoneyFormatter.format(125_000L, Locale.forLanguageTag("pl-PL"))
        assertTrue(polish, polish.contains("€") || polish.contains("EUR"))
        assertTrue(polish, !polish.contains("zł"))
    }
}
