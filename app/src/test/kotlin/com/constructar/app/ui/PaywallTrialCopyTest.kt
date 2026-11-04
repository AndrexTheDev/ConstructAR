/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.constructar.app.R
import com.constructar.core.licence.Entitlements
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The number of trial days, written once.
 *
 * `Entitlements.TRIAL_DAYS` is what the licence check actually grants, but the paywall button
 * carried its own hand-written copy of that number in all six locales ("7 Tage Pro testen").
 * Changing the trial length in code would have left six buttons promising a week while the app
 * granted something else - the same class of defect as finding 39's two decimal marks and
 * finding 47's two confidence bars, where one fact had two owners.
 *
 * The button now takes the days as a format argument, so the copy cannot disagree with the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaywallTrialCopyTest {

    private val locales = listOf(
        Locale.ENGLISH,
        Locale.GERMAN,
        Locale.forLanguageTag("es-ES"),
        Locale.FRENCH,
        Locale.ITALIAN,
        Locale.forLanguageTag("pl-PL"),
    )

    private fun context(locale: Locale): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun `the trial button quotes the length the licence check grants`() {
        val days = Entitlements.TRIAL_DAYS.toInt()
        assertEquals(7, days, "the guard below assumes a one-digit trial length")
        locales.forEach { locale ->
            val button = context(locale).getString(R.string.paywall_trial_button, days)
            assertTrue(button.contains(days.toString()), "${locale.toLanguageTag()}: $button")
        }
    }

    @Test
    fun `no locale writes the trial length by hand`() {
        locales.forEach { locale ->
            val ctx = context(locale)
            val three = ctx.getString(R.string.paywall_trial_button, 3)
            val seven = ctx.getString(R.string.paywall_trial_button, 7)
            // A hard-coded number would make both calls print the same word.
            assertNotEquals(three, seven, locale.toLanguageTag())
            assertTrue(three.contains("3"), "${locale.toLanguageTag()}: $three")
        }
    }
}
