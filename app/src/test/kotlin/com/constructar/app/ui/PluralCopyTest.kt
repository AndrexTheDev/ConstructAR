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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A count of one is not "1 projects".
 *
 * Three sentences the operator reads after the two moments that matter most - the export that
 * produced his protocol and the restore that brought his rooms back - carried their nouns as a
 * plain string: `"Saved %1$d files"`, `"%1$d projects restored"`, `"%1$d projects and %2$d photos
 * copied"`. Android has `plurals` for exactly this and every other counted string in the app
 * already used it, so the one sentence with a *single* file in it read "Saved 1 files" - in
 * English, and as "1 Projekte", "1 proyectos", "1 projets", "1 progetti", "1 projektów" in the rest.
 *
 * The test asks the resource table itself, in all six languages: a `plurals` whose "one" form is
 * missing falls back to "other", so `quantity 1` and `quantity 5` would print the same shape and
 * the difference is what is asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluralCopyTest {

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
    fun `a single file, project or photo has its own word`() {
        locales.forEach { locale ->
            val resources = context(locale).resources
            val tag = locale.toLanguageTag()

            val oneFile = resources.getQuantityString(R.plurals.export_success, 1, 1, "SD")
            val fiveFiles = resources.getQuantityString(R.plurals.export_success, 5, 5, "SD")
            assertNotEquals(fiveFiles, oneFile, "$tag: the export sentence has no singular")

            val oneRoom = resources.getQuantityString(R.plurals.backup_imported, 1, 1)
            val fiveRooms = resources.getQuantityString(R.plurals.backup_imported, 5, 5)
            assertNotEquals(fiveRooms, oneRoom, "$tag: the restore sentence has no singular")

            val oneCopied = resources.getQuantityString(R.plurals.backup_saved_projects, 1, 1)
            val fiveCopied = resources.getQuantityString(R.plurals.backup_saved_projects, 5, 5)
            assertNotEquals(fiveCopied, oneCopied, "$tag: the backup sentence has no singular")

            val onePhoto = resources.getQuantityString(R.plurals.backup_saved_photos, 1, 1)
            val fivePhotos = resources.getQuantityString(R.plurals.backup_saved_photos, 5, 5)
            assertNotEquals(fivePhotos, onePhoto, "$tag: the photo sentence has no singular")
        }
    }

    @Test
    fun `the trial's last day is a day and not one days`() {
        locales.forEach { locale ->
            val resources = context(locale).resources
            val tag = locale.toLanguageTag()

            val oneChip = resources.getQuantityString(R.plurals.plan_trial_days, 1, 1)
            val sevenChips = resources.getQuantityString(R.plurals.plan_trial_days, 7, 7)
            assertNotEquals(
                sevenChips,
                oneChip,
                "$tag: the plan chip says \"1 days\" on the trial's last day",
            )

            val oneLeft = resources.getQuantityString(R.plurals.paywall_trial_active, 1, 1)
            val sixLeft = resources.getQuantityString(R.plurals.paywall_trial_active, 6, 6)
            assertNotEquals(
                sixLeft,
                oneLeft,
                "$tag: the paywall says \"1 days left\" on the last day of the trial",
            )
        }
    }

    @Test
    fun `the trial plurals still print the number they are given`() {
        locales.forEach { locale ->
            val resources = context(locale).resources
            val tag = locale.toLanguageTag()

            assertTrue(resources.getQuantityString(R.plurals.plan_trial_days, 1, 1).contains("1"), tag)
            assertTrue(resources.getQuantityString(R.plurals.plan_trial_days, 7, 7).contains("7"), tag)
            assertTrue(resources.getQuantityString(R.plurals.paywall_trial_active, 2, 2).contains("2"), tag)
        }
    }

    @Test
    fun `the count comes from the argument, never from the copy`() {
        locales.forEach { locale ->
            val resources = context(locale).resources
            val three = resources.getQuantityString(R.plurals.export_success, 3, 3, "SD")
            val eight = resources.getQuantityString(R.plurals.export_success, 8, 8, "SD")

            assertTrue(three.contains("3"), locale.toLanguageTag())
            assertTrue(eight.contains("8"), locale.toLanguageTag())
            assertTrue(
                three.contains("SD"),
                "the folder must survive the plural: $three",
            )
        }
    }
}
