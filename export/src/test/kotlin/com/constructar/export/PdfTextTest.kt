/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export

import com.constructar.export.pdf.PdfText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two operations that stand between the operator's typing and a fixed-width A4 page.
 *
 * The measurement function is faked here (every character is 10 units wide) so the arithmetic can
 * be checked without an Android `Paint`: what is being tested is the decision - keep, clamp, or
 * flatten - not the font metrics.
 */
class PdfTextTest {

    private val measureTenPerCharacter: (String) -> Float = { it.length * 10f }

    @Test
    fun `a value that fits is returned untouched`() {
        val room = "Wohnzimmer"
        assertEquals(room, PdfText.ellipsize(room, 100f, measureTenPerCharacter))
    }

    @Test
    fun `a value that does not fit is cut and ends with an ellipsis`() {
        val long = "Wohnzimmer Erdgeschoss links neben der Kellertuere"
        val clamped = PdfText.ellipsize(long, 120f, measureTenPerCharacter)

        assertTrue("did not fit: ${clamped.length}", measureTenPerCharacter(clamped) <= 120f)
        assertTrue("no ellipsis: $clamped", clamped.endsWith("…"))
        assertTrue("nothing was cut: $clamped", clamped.length < long.length)
        assertTrue("mis-cut: $clamped", long.startsWith(clamped.dropLast(1)))
    }

    @Test
    fun `an absurdly narrow column keeps one character and the ellipsis`() {
        // Contract at the limit: the loop never cuts below a single character, so the result is
        // "B…" even when that is still wider than the space offered. A column that narrow does
        // not exist in the protocol (the narrowest is 60 pt), and cutting to "" would be worse.
        assertEquals("B…", PdfText.ellipsize("Bad", 5f, measureTenPerCharacter))
    }

    @Test
    fun `an empty value stays empty`() {
        assertEquals("", PdfText.ellipsize("", 100f, measureTenPerCharacter))
    }

    @Test
    fun `line breaks and tabs become spaces on a single-line draw`() {
        assertEquals(
            "Bad OG hinter der Heizung",
            PdfText.singleLine("Bad\nOG\thinter der Heizung"),
        )
        assertEquals("Kueche", PdfText.singleLine("  Kueche  "))
        assertEquals("ein two", PdfText.singleLine("ein\r\ntwo"))
    }

    @Test
    fun `flattening happens before the width is measured`() {
        // The writer flattens first, so a two-line name is one drawable line - and then clamped.
        val flattened = PdfText.singleLine("Bad\nOG\thinter der Heizung")
        val clamped = PdfText.ellipsize(flattened, 100f, measureTenPerCharacter)

        assertTrue("still has a line break: $clamped", !clamped.contains('\n'))
        assertTrue("did not fit: $clamped", measureTenPerCharacter(clamped) <= 100f)
    }
}
