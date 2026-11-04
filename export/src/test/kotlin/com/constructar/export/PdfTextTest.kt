/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export

import com.constructar.export.pdf.PdfText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Finding 177: the cut was made by UTF-16 unit, so it could land between the two halves of an
     * emoji and leave a lone surrogate in the string - which every renderer draws as the
     * replacement glyph, the box [PdfText] exists to keep off the protocol. `ExportService.slug`
     * has backed off one unit for a file name since finding 122; the page had no such guard.
     */
    @Test
    fun `an ellipsis never cuts a character in half`() {
        val room = "Wohnzimmer \uD83C\uDFD7 Erdgeschoss"
        // 13 units of 10 pt: the cut lands between the two halves of the emoji. Before the fix the
        // result ended with a high surrogate; now the whole character goes.
        val clamped = PdfText.ellipsize(room, 130f, measureTenPerCharacter)

        assertEquals("Wohnzimmer …", clamped)
        assertFalse(
            "a lone high surrogate is a replacement glyph in the document: $clamped",
            clamped.lastOrNull()?.isHighSurrogate() == true,
        )
    }

    @Test
    fun `no column width can leave half a character behind`() {
        val hammering = "\uD83D\uDEE0".repeat(12)
        for (width in 5..230) {
            val clamped = PdfText.ellipsize(hammering, width.toFloat(), measureTenPerCharacter)
            val tail = clamped.dropLast(1)
            assertFalse(
                "width $width produced a lone surrogate: $clamped",
                tail.lastOrNull()?.isHighSurrogate() == true,
            )
            assertTrue("width $width lost the ellipsis: $clamped", clamped.endsWith("…"))
        }
    }

    @Test
    fun `a long word with an emoji in it wraps without a broken character`() {
        // The paragraph path hands an over-wide word to `ellipsize`, so the guard has to hold there
        // too - a pasted link or part number is exactly the kind of text that arrives long.
        val pasted = "siehe \uD83D\uDCCE\uD83D\uDCCE\uD83D\uDCCE https://example.invalid/plan/erdgeschoss-links"
        val lines = PdfText.wrap(pasted, 120f, measureTenPerCharacter) +
            PdfText.wrap("\uD83D\uDCCE".repeat(20), 120f, measureTenPerCharacter)

        assertTrue("nothing was drawn: $lines", lines.isNotEmpty())
        for (line in lines) {
            val broken = line.lastOrNull()?.isHighSurrogate() == true || line.zipWithNext().any { (a, b) ->
                a.isHighSurrogate() && !b.isLowSurrogate()
            }
            assertFalse("a wrapped line holds half a character: $line", broken)
        }
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

    @Test
    fun `paragraphs wrap at the column width`() {
        val lines = PdfText.wrap("Bad OG hinter der Heizung", 100f, measureTenPerCharacter)

        assertEquals(listOf("Bad OG", "hinter der", "Heizung"), lines)
        for (line in lines) assertTrue("$line did not fit", measureTenPerCharacter(line) <= 100f)
    }

    @Test
    fun `a diary entry keeps its own line breaks`() {
        // The field is multi-line: what the operator typed as two lines must not become one line
        // with a missing-glyph box where the break was.
        val lines = PdfText.wrap("Kabel verlegt\nMaterial fehlt", 200f, measureTenPerCharacter)

        assertEquals(listOf("Kabel verlegt", "Material fehlt"), lines)
        assertTrue(lines.none { it.contains('\n') })
    }

    @Test
    fun `a word wider than the column is ellipsised instead of overflowing`() {
        val lines = PdfText.wrap("Donaudampfschifffahrtsgesellschaft", 100f, measureTenPerCharacter)

        assertEquals(1, lines.size)
        assertTrue("did not fit: ${lines[0]}", measureTenPerCharacter(lines[0]) <= 100f)
        assertTrue("no ellipsis: ${lines[0]}", lines[0].endsWith("…"))
    }

    @Test
    fun `an empty paragraph still produces one drawable line`() {
        assertEquals(listOf(""), PdfText.wrap("   ", 100f, measureTenPerCharacter))
    }
}
