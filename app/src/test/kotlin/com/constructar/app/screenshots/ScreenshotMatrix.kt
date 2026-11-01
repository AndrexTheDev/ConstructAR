/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.constructar.app.data.ProjectRepository
import com.constructar.app.ui.ExportScreen
import com.constructar.app.ui.FieldBookScreen
import com.constructar.app.ui.HelpScreen
import com.constructar.app.ui.HomeScreen
import com.constructar.app.ui.MaterialsScreen
import com.constructar.app.ui.PaywallScreen
import com.constructar.app.ui.ProjectScreen
import com.constructar.app.ui.theme.ConstructArTheme
import com.constructar.core.licence.Entitlements
import com.constructar.core.licence.LicenceCheck
import com.constructar.core.licence.Plan
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The temporary screenshot matrix: every screen, in every shipped language, rendered on the JVM.
 *
 * Two jobs in one pass:
 *
 *  1. **Pictures.** Roborazzi draws the real Compose tree to a PNG, so a layout that only breaks
 *     in Polish ("Länge gesamt" against "Total length") is visible without an emulator, a device
 *     farm or anyone's phone.
 *  2. **Text.** Every screen must render *some* text, and no rendered string may be a raw
 *     resource key — that is what a missing translation looks like when it is not caught by the
 *     parity gate in `scripts/verify_sources.py`.
 *
 * The matrix is opt-in (`-Pconstructar.screenshots=true`) and writes into
 * `app/build/screenshot-matrix/`, i.e. into build output that is never committed: it is a review
 * aid, not a golden-image gate. Nothing here runs in the release gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
abstract class LocaleScreenshotMatrix(private val localeTag: String) {

    @get:Rule
    val compose = createComposeRule()

    // ---------------------------------------------------------------- screens

    @Test
    fun home() = shot("home") {
        HomeScreen(
            projects = ScreenshotFixtures.summaries(),
            loading = false,
            entitlements = Entitlements.FREE,
            trialDaysLeft = 7,
            onOpen = {},
            onExport = {},
            onCreate = {},
            onUpgrade = {},
            onHelp = {},
        )
    }

    @Test
    fun project() = shot("project") {
        ProjectScreen(
            project = ScreenshotFixtures.project(),
            materialList = ScreenshotFixtures.materialList(),
            onBack = {},
            onMeasure = {},
            onExport = {},
            onRename = {},
            onSaveNotes = {},
            onSaveTechnician = {},
            onRemoveRoute = {},
            onMaterials = {},
            onFieldBook = {},
            onSaveCustomer = { _, _, _ -> },
            hourlyRateCents = 4_800,
            onSavePricing = { _, _ -> },
            photo = null,
            onPhoto = {},
            onDelete = {},
        )
    }

    @Test
    fun fieldBook() = shot("fieldbook") {
        FieldBookScreen(
            project = ScreenshotFixtures.project(),
            onBack = {},
            onStartWork = {},
            onStopWork = {},
            onAddDiary = {},
            onDeleteDiary = {},
            onAddDefect = { _, _, _ -> },
            onToggleDefect = {},
            onDeleteDefect = {},
            photoFor = { null },
            onAttachPhoto = { _, _ -> },
        )
    }

    @Test
    fun materials() = shot("materials") {
        MaterialsScreen(
            projectName = ScreenshotFixtures.project().name,
            materialList = ScreenshotFixtures.materialList(),
            wasteOverride = null,
            onWasteChange = {},
            firmHourlyRateCents = 4_800,
            onHourlyRateChange = {},
            onBack = {},
        )
    }

    @Test
    fun export() = shot("export") {
        ExportScreen(
            project = ScreenshotFixtures.project(),
            materialList = ScreenshotFixtures.materialList(),
            appVersion = "1.0.0 (1)",
            operatorName = "AndrexTheDev",
            entitlements = Entitlements.FREE,
            watermark = "ConstructAR · demo licence",
            hourlyRateCents = 4_800,
            onBack = {},
            onUpgrade = {},
        )
    }

    @Test
    fun paywall() = shot("paywall") {
        PaywallScreen(
            entitlements = Entitlements.FREE,
            deviceTag = "CAR-4821-9F3C",
            trialUsed = false,
            trialDaysLeft = Entitlements.TRIAL_DAYS,
            onBack = {},
            onActivate = { LicenceCheck.Malformed },
            onStartTrial = {},
        )
    }

    @Test
    fun help() = shot("help") {
        HelpScreen(
            entitlements = Entitlements.of(Plan.MONTHLY, expiresEpochDay = 20_604L, todayEpochDay = 20_574L),
            trialDaysLeft = 0,
            deviceTag = "CAR-4821-9F3C",
            repository = ProjectRepository(ApplicationProvider.getApplicationContext()),
            onBack = {},
            onUpgrade = {},
            onRestore = { _, _, _ -> },
        )
    }

    // ---------------------------------------------------------------- plumbing

    private fun shot(screen: String, content: @Composable () -> Unit) {
        compose.setContent {
            ConstructArTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    content()
                }
            }
        }
        compose.waitForIdle()

        val texts = renderedTexts()
        assertTrue("$localeTag/$screen rendered no text at all", texts.isNotEmpty())
        val keys = texts.filter { RESOURCE_KEY.matches(it) }
        assertTrue("$localeTag/$screen shows resource keys instead of translations: $keys", keys.isEmpty())

        val dir = File("build/screenshot-matrix/$localeTag").apply { mkdirs() }
        dir.resolve("$screen.txt").writeText(texts.joinToString("\n"))
        compose.onRoot().captureRoboImage(dir.resolve("$screen.png").absolutePath)
    }

    /** Every string the composition currently shows, in tree order. */
    private fun renderedTexts(): List<String> {
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        return compose.onAllNodes(matcher).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }.filter { it.isNotBlank() }
    }

    private companion object {
        /** `route_warning_low_confidence` and friends: a string the app forgot to translate. */
        val RESOURCE_KEY = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+){2,}$")
    }
}

/** German. */
@Config(qualifiers = "de-rDE-ldltr-sw411dp-w411dp-h891dp-port-xxhdpi")
class ScreenshotMatrixDeTest : LocaleScreenshotMatrix("de")

/** English (the default resource set). */
@Config(qualifiers = "en-rUS-ldltr-sw411dp-w411dp-h891dp-port-xxhdpi")
class ScreenshotMatrixEnTest : LocaleScreenshotMatrix("en")

/** Spanish. */
@Config(qualifiers = "es-rES-ldltr-sw411dp-w411dp-h891dp-port-xxhdpi")
class ScreenshotMatrixEsTest : LocaleScreenshotMatrix("es")

/** French. */
@Config(qualifiers = "fr-rFR-ldltr-sw411dp-w411dp-h891dp-port-xxhdpi")
class ScreenshotMatrixFrTest : LocaleScreenshotMatrix("fr")

/** Italian. */
@Config(qualifiers = "it-rIT-ldltr-sw411dp-w411dp-h891dp-port-xxhdpi")
class ScreenshotMatrixItTest : LocaleScreenshotMatrix("it")

/** Polish. */
@Config(qualifiers = "pl-rPL-ldltr-sw411dp-w411dp-h891dp-port-xxhdpi")
class ScreenshotMatrixPlTest : LocaleScreenshotMatrix("pl")
