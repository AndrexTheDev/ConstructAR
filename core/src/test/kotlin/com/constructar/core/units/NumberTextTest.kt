/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.units

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A screen writes the reader's decimal mark; a document writes the protocol's (finding 222).
 *
 * The defect this pins down is not "the wrong character": `LengthFormatter` has printed `1.40 m` in
 * six languages since it was written, and a German fitter reads it fine. It is that the same app
 * prints `1,40 m` in three strings it takes from the resources (prices, percentages) and `1.40 m`
 * in every length - one screen, two conventions, and the digits are the part a fitter checks.
 *
 * What must not move is the documents: CSV is parsed by a spreadsheet whose separator is `,` in
 * half of Europe, and a JSON written on a phone in Warsaw has to be the same file as one written in
 * Madrid. Both go through `LengthFormatter`'s default, which stays `.`.
 */
class NumberTextTest {

    @Test
    fun `the mark is the locale's own`() {
        assertEquals(',', NumberText.decimalMark(Locale.GERMANY))
        assertEquals(',', NumberText.decimalMark(Locale.FRANCE))
        assertEquals(',', NumberText.decimalMark(Locale.ITALY))
        assertEquals(',', NumberText.decimalMark(Locale("pl", "PL")))
        assertEquals(',', NumberText.decimalMark(Locale("es", "ES")))
        assertEquals('.', NumberText.decimalMark(Locale.US))
        assertEquals('.', NumberText.decimalMark(Locale.UK))
    }

    @Test
    fun `the mark is never something that would turn a number into a word`() {
        // A locale whose separator came back as a digit or a letter would render `1.40` as `1140`
        // on screen - the one failure mode of this whole idea that changes the *value*.
        for (locale in listOf(Locale.GERMANY, Locale.US, Locale.FRANCE, Locale.JAPAN, Locale.ROOT)) {
            val mark = NumberText.decimalMark(locale)
            assertFalse("$locale gave '$mark'", mark.isDigit() || mark.isLetter())
        }
    }

    @Test
    fun `digits carry the mark and nothing else changes`() {
        assertEquals("1,40", NumberText.digits(1.40f, 2, ','))
        assertEquals("1.40", NumberText.digits(1.40f, 2, '.'))
        assertEquals("4,2", NumberText.digits(4.2f, 1, ','))
        assertEquals("0,00", NumberText.digits(0f, 2, ','))
    }

    @Test
    fun `rounding is the formatter's, at the same boundary`() {
        // The half-centimetre boundary the formatter is tested on: 0.995f is 0.9949999… as a
        // double, and `String.format` prints it as 1.00. The mark must not change which way.
        assertEquals("0,99", NumberText.digits(0.994f, 2, ','))
        assertEquals("1,00", NumberText.digits(0.995f, 2, ','))
        assertEquals("0.99", NumberText.digits(0.994f, 2, '.'))
        assertEquals("1.00", NumberText.digits(0.995f, 2, '.'))
    }

    @Test
    fun `an unknown number stays unknown`() {
        assertEquals("—", NumberText.digits(Float.NaN, 2, ','))
        assertEquals("—", NumberText.digits(Float.POSITIVE_INFINITY, 2, '.'))
    }

    @Test
    fun `decimals are clamped to something a length can carry`() {
        assertEquals("1,4", NumberText.digits(1.4f, -3, ','))
        assertEquals("1,400", NumberText.digits(1.4f, 9, ','))
    }

    @Test
    fun `the protocol's mark is what the formatter already prints`() {
        // The whole safety of this change: a caller that says nothing gets the old string.
        assertEquals(
            LengthFormatter.number(14.8234f),
            NumberText.digits(14.8234f, 2, NumberText.DEFAULT_MARK),
        )
        assertEquals("14,82 m", LengthFormatter.format(14.8234f, mark = ','))
        assertEquals("14.82 m", LengthFormatter.format(14.8234f))
        assertEquals("18,40 m²", LengthFormatter.formatArea(18.4f, ','))
        assertEquals("18.40 m²", LengthFormatter.formatArea(18.4f))
    }
}
