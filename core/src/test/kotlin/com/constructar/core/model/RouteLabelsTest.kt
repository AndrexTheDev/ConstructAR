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
        assertEquals("CABLE Ø 16 mm #1", RouteLabels.next(ConduitSpec.cable(16f, "Cable Ø16"), emptyList()))
    }

    @Test
    fun `deleting a run does not let the next one take its number`() {
        // Finding 45: #1 and #3 are what is left after run #2 was removed; before the fix the
        // next run was called #3 again, and the protocol carried two of them.
        val spec = ConduitSpec.cable(16f, "Cable Ø16")
        val left = listOf("CABLE Ø 16 mm #1", "CABLE Ø 16 mm #3")
        assertEquals("CABLE Ø 16 mm #4", RouteLabels.next(spec, left))
    }

    @Test
    fun `numbers are never handed out twice, even after the last run was deleted`() {
        val spec = ConduitSpec.cable(16f, "Cable Ø16")
        assertEquals(
            "CABLE Ø 16 mm #3",
            RouteLabels.next(spec, listOf("CABLE Ø 16 mm #1", "CABLE Ø 16 mm #2")),
        )
        // A number that exists without its neighbours is still taken: imported or hand-edited
        // project files can carry one.
        assertEquals("CABLE Ø 16 mm #8", RouteLabels.next(spec, listOf("CABLE Ø 16 mm #7")))
    }

    @Test
    fun `runs of another kind do not consume this kind's numbers`() {
        val cable = ConduitSpec.cable(16f, "Cable Ø16")
        val pipe = ConduitSpec.pipe(20f, "Pipe 20")
        val labels = listOf(RouteLabels.next(pipe, emptyList()), RouteLabels.next(pipe, listOf("PIPE Ø 20 mm #1")))
        assertEquals(listOf("PIPE Ø 20 mm #1", "PIPE Ø 20 mm #2"), labels)
        assertEquals("CABLE Ø 16 mm #1", RouteLabels.next(cable, labels))
    }

    @Test
    fun `the label does not change with the phone's language`() {
        val spec = ConduitSpec.cable(16f, "Cable Ø16")
        val existing = (1..4).map { "CABLE Ø 16 mm #$it" }
        Locale.setDefault(Locale.GERMANY)
        val german = RouteLabels.next(spec, existing)
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        val polish = RouteLabels.next(spec, existing)

        assertEquals(german, polish)
        assertEquals("CABLE Ø 16 mm #5", german)
    }
}
