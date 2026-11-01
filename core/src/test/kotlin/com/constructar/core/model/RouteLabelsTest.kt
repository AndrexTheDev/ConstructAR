/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The label a run is stored under.
 *
 * It is persisted into the project file and then repeated by the CSV, the PDF, the bill of
 * materials and the 3D extras. Two phones must therefore produce the same label for the same run,
 * or a project moved to another device reads as a different one.
 */
class RouteLabelsTest {

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
    fun `the first run of a project is number one`() {
        assertEquals("CABLE Ø 16 mm #1", RouteLabels.next(ConduitSpec.cable(16f, "Cable Ø16"), 0))
    }

    @Test
    fun `the label does not change with the phone's language`() {
        val spec = ConduitSpec.cable(16f, "Cable Ø16")
        Locale.setDefault(Locale.GERMANY)
        val german = RouteLabels.next(spec, 4)
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        val polish = RouteLabels.next(spec, 4)

        assertEquals(german, polish)
        assertEquals("CABLE Ø 16 mm #5", german)
    }
}
