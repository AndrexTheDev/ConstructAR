/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.units

import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every number a customer reads passes through here: the HUD, the PDF protocol and the CSV.
 *
 * The important case is not the happy path but the *locale* one. `String.format("%.2f", …)`
 * without an explicit locale uses the default locale of the phone, which on a German handset
 * produces `14,82` — in a dot-decimal CSV and in a protocol an accountant has to read. The
 * formatter therefore pins [Locale.ROOT]; this test fails if someone removes that pin.
 *
 * Dot decimals everywhere is a documented decision, not an oversight: the CSV is machine-read,
 * the PDF is compared with a laser measure, and one separator is easier to defend than six.
 */
class LengthFormatterTest {

    @Test
    fun `metres carry two decimals and the unit`() {
        assertEquals("14.82 m", LengthFormatter.format(14.8234f))
        assertEquals("0.00 m", LengthFormatter.format(0f))
    }

    @Test
    fun `values round, they are never truncated`() {
        // A truncating formatter would show a run 5 mm shorter than the tape measure says,
        // which is exactly the argument this app is supposed to end.
        assertEquals("1.00 m", LengthFormatter.format(0.996f))
        assertEquals("2.00 m", LengthFormatter.format(1.9999f))
        assertEquals("0.99 m", LengthFormatter.format(0.994f))
        // The halfway case: `0.995f` is 0.99499994… in binary, so it rounds down. Documented,
        // and half a millimetre below a claim of ±2 cm.
        assertEquals("0.99 m", LengthFormatter.format(0.995f))
    }

    @Test
    fun `nothing is claimed when the input is not a number`() {
        assertEquals("—", LengthFormatter.format(Float.NaN))
        assertEquals("—", LengthFormatter.format(Float.POSITIVE_INFINITY))
        assertEquals("—", LengthFormatter.formatDiameter(0f))
    }

    @Test
    fun `centimetres and millimetres convert, not divide`() {
        assertEquals("150.0 cm", LengthFormatter.format(1.5f, LengthUnit.CENTIMETER))
        assertEquals("500 mm", LengthFormatter.format(0.5f, LengthUnit.MILLIMETER))
        assertEquals("14.82 m (1482 cm)", LengthFormatter.formatDual(14.8234f))
    }

    @Test
    fun `a prefix is glued to the measurement with a colon and a space`() {
        assertEquals("Länge: 2.50 m", LengthFormatter.formatLabel(2.5f, prefix = "Länge"))
        assertEquals("Length: 2.50 m", LengthFormatter.formatLabel(2.5f))
    }

    @Test
    fun `area, volume, diameter, section and waste have one spelling each`() {
        assertEquals("18.40 m²", LengthFormatter.formatArea(18.4f))
        assertEquals("2.61 m³", LengthFormatter.formatVolume(2.61f))
        assertEquals("Ø 16 mm", LengthFormatter.formatDiameter(16f))
        assertEquals("60 × 40 mm", LengthFormatter.formatSection(60f, 40f))
        assertEquals("+7 %", LengthFormatter.formatWasteFactor(0.07f))
    }

    @Test
    fun `the default locale of the phone cannot change a measurement`() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.FRANCE, Locale("es", "ES"), Locale("pl", "PL"))) {
                Locale.setDefault(locale)
                assertEquals("14.82 m", LengthFormatter.format(14.8234f), "changed under $locale")
                assertEquals("18.40 m²", LengthFormatter.formatArea(18.4f), "changed under $locale")
                assertEquals("12.3 cm", LengthFormatter.format(0.1234f, LengthUnit.CENTIMETER), "changed under $locale")
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `timestamps are sortable and independent of the phone locale`() {
        assertEquals("1970-01-01 00:00", LengthFormatter.formatTimestamp(0L, TimeZone.getTimeZone("UTC")))
        assertEquals("2026-03-04 08:15", LengthFormatter.formatTimestamp(1_772_612_100_000L, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `a stored unit name parses back, and anything else means metres`() {
        assertEquals(LengthUnit.CENTIMETER, LengthUnit.fromNameOrDefault("centimeter"))
        assertEquals(LengthUnit.MILLIMETER, LengthUnit.fromNameOrDefault("MILLIMETER"))
        assertEquals(LengthUnit.METER, LengthUnit.fromNameOrDefault(null))
        assertEquals(LengthUnit.METER, LengthUnit.fromNameOrDefault("furlong"))
    }
}
