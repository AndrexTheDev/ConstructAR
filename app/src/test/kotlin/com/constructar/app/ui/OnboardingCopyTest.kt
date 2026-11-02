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
import kotlin.test.assertTrue

/**
 * The last onboarding slide names the plan that unlocks the 3D model.
 *
 * The first thirty seconds of the app promised "PDF, CSV and a 3D model" in all six languages, and
 * the GLB and the PLY are locked behind Pro - the same app says so on the export screen
 * ("GLB and PLY are part of Pro") and on the paywall. A free installer was told the thing he cannot
 * have, before he had measured anything; the sentence names the plan now.
 *
 * The guard is structural, because a translation is not something a test can read: the slide must
 * carry a placeholder for the plan name, so the copy cannot promise the 3D export without naming
 * what unlocks it. `verify_sources.py` additionally refuses a locale that drops the placeholder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingCopyTest {

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
    fun `the slide that promises a 3D model names the plan that unlocks it`() {
        // The free plan cannot write a mesh; the sentence has to say so, in every language.
        assertTrue(
            "the free plan is expected to be the one without the mesh formats",
            !Entitlements.FREE.isPro,
        )
        locales.forEach { locale ->
            val slide = context(locale).getString(R.string.onboard_3_body, "PROBE")
            assertTrue(
                slide.contains("PROBE"),
                "${locale.toLanguageTag()}: the slide does not name the plan - $slide",
            )
        }
    }
}
