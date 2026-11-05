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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The sentences a screen reader hears on the two surfaces that had none.
 *
 * A signature pad is a blank canvas with two pointer inputs and the AR screen's measuring surface
 * is one enormous unlabelled `Box`: both were usable by everyone who can see and by nobody who
 * cannot. What they announce now is a name, a state and - for the pad - an action, and those four
 * to six sentences live in six languages like every other word in this app.
 *
 * A translation cannot be tested for meaning; what can be tested is that it exists, that it is not
 * blank, and that two sentences which must not be confused in the ear are not the same string in
 * any language - the state of a defect against the action of its button, "signed" against "not
 * signed yet". Those are the mistakes a translator makes once and nobody notices until the phone is
 * in a customer's hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class A11yLabelsTest {

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
    fun `every locale names the signature pad, its two states and its clear action`() {
        locales.forEach { locale ->
            val context = context(locale)
            val tag = locale.toLanguageTag()
            val pad = context.getString(R.string.a11y_signature_pad)
            val signed = context.getString(R.string.a11y_signature_signed)
            val empty = context.getString(R.string.a11y_signature_empty)
            val clear = context.getString(R.string.a11y_signature_clear)

            assertTrue("$tag: the pad has no name", pad.isNotBlank())
            assertTrue("$tag: the signed state is blank", signed.isNotBlank())
            assertTrue("$tag: the empty state is blank", empty.isNotBlank())
            assertTrue("$tag: the clear action has no label", clear.isNotBlank())
            // One word cannot be both states, or the state description says nothing at all.
            assertNotEquals("$tag: signed and not-signed read the same", signed, empty)
            assertNotEquals("$tag: the action repeats the field's name", pad, clear)
        }
    }

    @Test
    fun `every locale names the camera view and the action it offers`() {
        locales.forEach { locale ->
            val context = context(locale)
            val tag = locale.toLanguageTag()
            val view = context.getString(R.string.a11y_ar_view)
            val action = context.getString(R.string.a11y_ar_place_point)

            assertTrue("$tag: the AR surface has no name", view.isNotBlank())
            assertTrue("$tag: the AR action has no label", action.isNotBlank())
            // The action must say more than the surface, or a double-tap sounds like nothing.
            assertNotEquals("$tag: the action repeats the surface's name", view, action)
        }
    }

    @Test
    fun `a defect's state and its action are two sentences in every locale`() {
        locales.forEach { locale ->
            val context = context(locale)
            val tag = locale.toLanguageTag()
            val openState = context.getString(DefectToggle.stateRes(resolved = false))
            val resolvedState = context.getString(DefectToggle.stateRes(resolved = true))
            val resolveAction = context.getString(DefectToggle.labelRes(DefectToggle.Action.RESOLVE))
            val reopenAction = context.getString(DefectToggle.labelRes(DefectToggle.Action.REOPEN))

            assertTrue("$tag: the open state is blank", openState.isNotBlank())
            assertNotEquals("$tag: open and resolved read the same", openState, resolvedState)
            assertNotEquals("$tag: the resolve action repeats the open state", resolveAction, openState)
            assertNotEquals("$tag: the reopen action repeats the resolved state", reopenAction, resolvedState)
        }
    }
}
