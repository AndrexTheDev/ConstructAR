/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When a paid plan stops being paid.
 *
 * There is no server to ask, so the answer is a function of three things the phone holds: the
 * licence, the day it was first seen (the sealed watermark) and the current day. `refresh()` is
 * that function, and it is cheap and idempotent on purpose - which is only worth anything if
 * somebody calls it. The app's own documentation promised a call on resume, and nothing made it
 * (finding 132), so a seven-day trial on a phone whose app is never closed ran for as long as the
 * process did. These tests pin the *behaviour* the resume call exists to deliver.
 *
 * The clock is a lambda, so a day passes in a line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntitlementStoreTest {

    private lateinit var context: Context
    private var now: Long = 0L

    private fun day(epochDay: Long): Long = epochDay * MILLIS_PER_DAY + NOON

    // Day 100, 12:00 — far enough from any epoch to make a rolled-back clock obvious.
    private val startDay = 100L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        now = day(startDay)
    }

    private fun store() = EntitlementStore(context, clock = { now })

    @Test
    fun `a fresh install starts free`() {
        val store = store()

        assertFalse(store.entitlements.value.isPro)
        assertTrue("a trial nobody has started is still on offer", store.diagnostics.value.trialAvailable)
        assertFalse(store.trialUsed())
    }

    @Test
    fun `the trial is pro, and its last day still says one day left`() {
        val store = store()
        assertTrue(store.startTrial())
        assertTrue(store.entitlements.value.isPro)

        // Seven days is start..start+6 (finding: `<=` used to grant eight).
        now = day(startDay + Entitlements.TRIAL_DAYS - 1L)
        store.refresh()

        assertTrue(store.entitlements.value.isPro)
        assertTrue(store.entitlements.value.isTrial)
        assertEquals(1L, store.trialDaysLeft())
    }

    @Test
    fun `the day after the trial it is free again, without a restart`() {
        val store = store()
        assertTrue(store.startTrial())

        now = day(startDay + Entitlements.TRIAL_DAYS)
        store.refresh()

        assertFalse(store.entitlements.value.isPro)
        assertEquals(0L, store.trialDaysLeft())
        assertFalse("a used trial is not offered twice", store.diagnostics.value.trialAvailable)
        assertFalse(store.startTrial())
    }

    @Test
    fun `moving the clock back does not restart a trial that has ended`() {
        val store = store()
        assertTrue(store.startTrial())
        now = day(startDay + Entitlements.TRIAL_DAYS + 3L)
        store.refresh()
        assertFalse(store.entitlements.value.isPro)

        // The watermark remembers day 100 + 10; a phone set back to day 90 is not a new trial.
        now = day(startDay - 10L)
        store.refresh()

        assertFalse(store.entitlements.value.isPro)
        assertTrue("the operator is told why", store.diagnostics.value.clockRolledBack)
    }

    @Test
    fun `a sealed value edited by hand does not buy anything`() {
        val store = store()
        assertTrue(store.startTrial())
        // The trial start is sealed with a key derived from the installation id: writing a new
        // day next to the old seal is the obvious way to try a second trial.
        context.getSharedPreferences("constructar.licence", Context.MODE_PRIVATE)
            .edit()
            .putString("trial_start_epoch_day", (startDay + 20L).toString())
            .apply()

        now = day(startDay + 21L)
        store.refresh()

        assertFalse(store.entitlements.value.isPro)
        assertTrue(store.diagnostics.value.localStateTampered)
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
        const val NOON = 43_200_000L
    }
}
