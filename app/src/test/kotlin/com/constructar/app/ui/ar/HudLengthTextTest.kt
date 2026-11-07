/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.constructar.app.R
import com.constructar.core.units.LengthFormatter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The HUD's decimal mark, on a phone that is not set to English.
 *
 * `hud_length` carried a `%1$.2f` format until finding 39, so the run length followed the device
 * (`12,50 m` in German) while the room total three lines below it came from `LengthFormatter`
 * (`28.80 m`). Two marks, one column, one room. The resource now takes the digits as a string
 * and the digits come from the same formatter the protocol and the CSV use - so what the phone
 * says and what the page says cannot part company, in any of the six locales.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HudLengthTextTest {

    private fun context(locale: Locale): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun `a German HUD writes the protocol's decimal mark`() {
        val german = context(Locale.GERMAN)
        assertEquals("12.50 m", german.getString(R.string.hud_length, LengthFormatter.number(12.5f)))
    }

    @Test
    fun `the run length and the room total agree`() {
        val german = context(Locale.GERMAN)
        val run = german.getString(R.string.hud_length, LengthFormatter.number(12.5f))
        val total = german.getString(
            R.string.export_runs_total,
            4,
            german.getString(R.string.hud_length, LengthFormatter.number(28.8f)),
        )
        assertEquals("4 Strecken · 28.80 m gesamt", total)
        assertFalse(run.contains(","), "run length: $run")
        assertFalse(total.contains(","), "room total: $total")
    }

    @Test
    fun `the count and the list title call a run by the same name`() {
        // The list is headed by `home_runs` and the total above it by `export_runs_total`, and on
        // one screen they said "Strecken" and "Trassen" (finding 277). Spanish had the same split
        // (trazado/recorrido) and Italian too (tracciato/percorso): a minority noun sitting in
        // exactly the two counting strings. Pinned here so no translation can reintroduce it
        // without a test turning red.
        val german = context(Locale.GERMAN)
        val counted = german.resources.getQuantityString(R.plurals.home_runs, 4, 4)
        val sentence = german.getString(R.string.export_runs_total, 4, "28.80 m")

        assertEquals("4 Strecken", counted)
        assertTrue(
            sentence.contains("Strecken"),
            "the total names runs the way the list does: $sentence",
        )
        assertFalse(sentence.contains("Trassen"), "a second noun for the same thing: $sentence")
    }

    @Test
    fun `every locale writes the same digits for the same measurement`() {
        val locales = listOf(
            Locale.ENGLISH,
            Locale.GERMAN,
            Locale.forLanguageTag("es-ES"),
            Locale.FRENCH,
            Locale.ITALIAN,
            Locale.forLanguageTag("pl-PL"),
        )
        locales.forEach { locale ->
            assertEquals(
                "1.00 m",
                context(locale).getString(R.string.hud_length, LengthFormatter.number(1f)),
                locale.toLanguageTag(),
            )
        }
    }
}
