/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.constructar.arengine.hit.SnapFeatureKind
import com.constructar.app.ui.snapMovedRes
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The snap line, in the six languages the app ships.
 *
 * One whole sentence per feature and language rather than one sentence with the feature as an
 * argument: "An der Ecke ausgerichtet" and "An der Bodenkante ausgerichtet" take different cases,
 * and a placeholder would make every translator bend the sentence around a noun they cannot
 * decline. What a test can check is that each sentence exists, that it still carries the distance,
 * and that no two features share a string - a translator who copies one line would otherwise ship
 * a HUD that says "corner" for a floor edge in one language only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapCopyTest {

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
    fun `every locale states each feature and the distance in its own way`() {
        locales.forEach { locale ->
            val context = context(locale)
            val tag = locale.toLanguageTag()
            val sentences = SnapFeatureKind.entries.map { kind ->
                context.getString(kind.snapMovedRes(), "4.2")
            }

            sentences.forEach { sentence ->
                assertTrue("$tag: a snap line is blank", sentence.isNotBlank())
                assertTrue("$tag: a snap line lost its distance - $sentence", sentence.contains("4.2"))
                // The unit belongs to the resource, so a translator can drop "cm" and a test can
                // see it.
                assertTrue("$tag: a snap line names no centimetre - $sentence", sentence.contains("cm"))
            }
            assertTrue(
                "$tag: two kinds of feature share one sentence",
                sentences.toSet().size == SnapFeatureKind.entries.size,
            )
        }
    }
}
