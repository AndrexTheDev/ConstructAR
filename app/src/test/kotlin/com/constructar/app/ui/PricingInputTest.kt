/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the two money fields do with what a gloved finger typed.
 *
 * Finding 43: both fields read an unparsable entry as a value - `?: 0` for the material, `?.takeIf
 * { it > 0 }` for the rate - so adding one character to `250,00` silently zeroed the material sum
 * and an unfinished `48,` reset the firm's rate. The rule these tests pin down: blank is silent
 * and means "not set", a readable amount is taken as written, and anything else is flagged so the
 * screen can say so and refuse to save - nothing is guessed into a number.
 */
class PricingInputTest {

    @Test
    fun `a blank field is silent and means not set`() {
        assertEquals(PricingInput.Rate(cents = null, invalid = false), PricingInput.rate(""))
        assertEquals(PricingInput.Rate(cents = null, invalid = false), PricingInput.rate("   "))
        assertEquals(PricingInput.Amount(cents = 0L, invalid = false), PricingInput.amount(""))
    }

    @Test
    fun `both separators are read as the operator meant them`() {
        assertEquals(4_850, PricingInput.rate("48,50").cents)
        assertEquals(4_850, PricingInput.rate("48.50").cents)
        assertEquals(4_800, PricingInput.rate("48").cents)
        assertEquals(250_000L, PricingInput.amount("2.500,00").cents)
        assertEquals(250_000L, PricingInput.amount("2,500.00").cents)
        assertFalse(PricingInput.rate("48,50").invalid)
        assertFalse(PricingInput.amount("2.500,00").invalid)
    }

    @Test
    fun `an unfinished entry is an error, not a value`() {
        // The state of every keystroke on the way to "48,50" - and the one that reset the rate.
        assertTrue(PricingInput.rate("48,").invalid)
        assertNull(PricingInput.rate("48,").cents)
        assertTrue(PricingInput.rate("48.").invalid)
        assertTrue(PricingInput.amount("250,").invalid)
        assertEquals(0L, PricingInput.amount("250,").cents)
    }

    @Test
    fun `text that is not an amount never becomes one`() {
        listOf("abc", "48,50x", "-48", "4,5,6", "x250").forEach { typed ->
            assertTrue(PricingInput.amount(typed).invalid, "amount '$typed'")
            assertEquals(0L, PricingInput.amount(typed).cents, "amount '$typed'")
            assertTrue(PricingInput.rate(typed).invalid, "rate '$typed'")
            assertNull(PricingInput.rate(typed).cents, "rate '$typed'")
        }
    }

    @Test
    fun `a rate of zero is not a rate`() {
        // Zero cannot be billed, and the hint on the field says what a blank one does instead.
        assertTrue(PricingInput.rate("0").invalid)
        assertTrue(PricingInput.rate("0,00").invalid)
        // A material lump sum of zero is a legitimate answer: nothing was supplied.
        assertEquals(PricingInput.Amount(cents = 0L, invalid = false), PricingInput.amount("0"))
    }

    @Test
    fun `an amount too large for an hourly rate is refused rather than wrapped`() {
        // 99 999 999,00 € per hour overflows an Int of cents; taking it as-is would bill a
        // negative or a nonsense rate on the protocol.
        assertTrue(PricingInput.rate("99.999.999").invalid)
        assertNull(PricingInput.rate("99.999.999").cents)
    }
}
