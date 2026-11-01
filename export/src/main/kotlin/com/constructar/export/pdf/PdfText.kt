/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

/**
 * The two things that have to happen to user text before it goes onto a fixed-width page.
 *
 * `Canvas.drawText` does neither: it draws past the right margin and it draws a line break as a
 * missing glyph. Both inputs here are the operator's own words - a room called "Wohnzimmer
 * Erdgeschoss links neben der Kellertür, hinter der Heizung" or a name pasted from a note with a
 * line break in it - and the protocol is the document that gets handed to a customer, so a name
 * cut in half at the page edge (or a row of boxes where a line break was) is not a detail.
 *
 * Kept free of Android types so the arithmetic can be tested on the JVM: the caller passes the
 * measurement function of the paint it is about to draw with.
 */
object PdfText {

    /**
     * [value] shortened with an ellipsis until [measure] says it fits [maxWidth].
     *
     * Never returns something wider than asked for; returns the value untouched when it already
     * fits, which is the common case and must not be rewritten (a name that fits should not be
     * flattened or trimmed).
     */
    fun ellipsize(value: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && measure(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end) + "…"
    }

    /**
     * [value] with every line break and tab replaced by a space, for single-line drawing.
     *
     * `drawText` has no idea what `\n` means: the character is drawn as the font's missing glyph
     * (a box) and the text after it is laid out as if it had never been there.
     */
    fun singleLine(value: String): String =
        value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
}
