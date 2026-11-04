/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

/**
 * The size of a conduit, as it is written into project files, order lists and protocols.
 *
 * `sizeLabel` is not a caption: it is part of the persisted run label, of the bill-of-materials
 * key and of the CSV. Finding 46: its half-millimetre branch used `"%.1f".format()`, which takes
 * the decimal mark from the phone, so a 16,5 mm cable on a German handset was stored as
 * `Ø 16,5 mm` - a second order line next to the English phone's `Ø 16.5 mm`, each rounded up.
 */
class ConduitSpecTest {

    private lateinit var original: Locale

    @Before
    fun rememberLocale() {
        original = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `a half millimetre is written with a dot on every phone`() {
        val spec = ConduitSpec.cable(16.5f, "Cable Ø16,5")
        assertEquals("Ø 16.5 mm", spec.sizeLabel)

        Locale.setDefault(Locale.GERMANY)
        assertEquals("Ø 16.5 mm", spec.sizeLabel)
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        assertEquals("Ø 16.5 mm", ConduitSpec.pipe(16.5f, "Pipe").sizeLabel)
    }

    @Test
    fun `whole millimetres stay whole`() {
        assertEquals("Ø 16 mm", ConduitSpec.cable(16f, "Cable Ø16").sizeLabel)
        // 16.004 is a 16 mm conduit; the half-millimetre branch must not fire on float noise.
        assertEquals("Ø 16 mm", ConduitSpec.cable(16.004f, "Cable Ø16").sizeLabel)
        assertEquals("50 × 30 mm", ConduitSpec.duct(50f, 30f, "Duct 50×30").sizeLabel)
    }

    @Test
    fun `the same conduit has one bill-of-materials key in every language`() {
        // Two order lines for one cable is how a site ends up buying twice the metres.
        val german = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val inGerman = ConduitSpec.cable(16.5f, "Cable Ø16,5").bomKey
            Locale.setDefault(Locale.ENGLISH)
            val inEnglish = ConduitSpec.cable(16.5f, "Cable Ø16,5").bomKey
            assertEquals(inEnglish, inGerman)
            assertEquals("CABLE|LSZH cable Ø16 mm|Ø 16.5 mm|0", inGerman)
        } finally {
            Locale.setDefault(german)
        }
    }

    @Test
    fun `a run of a half-millimetre cable is named the same everywhere`() {
        val spec = ConduitSpec.cable(16.5f, "Cable Ø16,5")
        Locale.setDefault(Locale.GERMANY)
        val german = RouteLabels.next(spec, emptyList())
        Locale.setDefault(Locale.ENGLISH)
        val english = RouteLabels.next(spec, emptyList())

        assertEquals("CABLE Ø 16.5 mm #1", german)
        assertEquals(english, german)
    }
}
