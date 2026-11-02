/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

import com.constructar.core.model.HitConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The line under the summary that says how exact the protocol is.
 *
 * The shop page told every visitor, in six languages, that "the protocol states the expected
 * error too" - and the document said nothing of the kind: it named no tolerance, printed a
 * "Confidence" column with no explanation, and left the customer signing a percentage nobody had
 * defined. Two things are tested here: that the sentence carries the published figures, and that
 * the confidence bar comes from [HitConfidence.RUN_TRUSTED] instead of being typed into the six
 * translations, where it would drift the moment the app's own bar moved.
 */
class AccuracyNoteTest {

    private val labels = PdfLabels()

    @Test
    fun `the protocol states the accuracy the shop page promises`() {
        val note = accuracyNote(labels.accuracyNote)

        assertTrue("the flat-wall range must be named: $note", note.contains("1-2 cm"))
        assertTrue("the depth-only range must be named: $note", note.contains("3-6 cm"))
        assertTrue("the diagonal figure must be named: $note", note.contains("1 % of the length"))
        assertFalse("a literal percent sign must not leak through as %%: $note", note.contains("%%"))
    }

    @Test
    fun `the confidence bar is computed, not written into the text`() {
        val bar = (HitConfidence.RUN_TRUSTED * 100).roundToInt()

        assertTrue(
            "the default must print the bar the app actually uses ($bar %): " +
                accuracyNote(labels.accuracyNote),
            accuracyNote(labels.accuracyNote).contains("below $bar %"),
        )
        assertTrue(
            "a stricter bar must show through, so nothing is baked into the text",
            accuracyNote(labels.accuracyNote, trusted = 0.5f).contains("below 50 %"),
        )
    }

    @Test
    fun `a translation without the placeholder still prints a sentence`() {
        val plain = "Expected accuracy: 1-2 cm on a flat wall."

        assertEquals(
            "a template with no %1\$d must not throw while a customer waits for his protocol",
            plain,
            accuracyNote(plain),
        )
    }

    @Test
    fun `the default text is the one the writer formats`() {
        // Guards the literal that any non-Android caller (a test, a script, a future CLI) falls
        // back to: it must be formattable, i.e. its percents escaped and its placeholder present.
        val rendered = String.format(Locale.ROOT, labels.accuracyNote, 85)

        assertTrue(rendered.contains("below 85 %"))
        assertFalse(rendered.contains("%%"))
        assertFalse("the placeholder must have been consumed: $rendered", rendered.contains("%1"))
    }
}
