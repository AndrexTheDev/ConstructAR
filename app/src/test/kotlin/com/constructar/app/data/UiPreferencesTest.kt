/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The memory of "which room was I in" that quick resume stands on.
 *
 * Android kills a backgrounded measuring tool whenever it wants the memory - the operator answers
 * a phone call mid-wall and comes back to a fresh process. Coming back to the room he was in is
 * the whole feature; coming back to a room he closed last week is the failure mode, which is why
 * every close and delete clears the id instead of leaving it for the next cold start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiPreferencesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a fresh install remembers no room`() {
        assertNull(UiPreferences(context).lastProjectId())
    }

    @Test
    fun `the last room survives a new store instance`() {
        UiPreferences(context).setLastProjectId("8f14e45f-ceea-467f-a1ca-4b4d0d1c2b3a")

        // A new instance is what a cold start looks like: the file, not the object, is the memory.
        assertEquals("8f14e45f-ceea-467f-a1ca-4b4d0d1c2b3a", UiPreferences(context).lastProjectId())
    }

    @Test
    fun `closing the room forgets it, and a blank id is never stored`() {
        val preferences = UiPreferences(context)
        preferences.setLastProjectId("room-1")
        preferences.setLastProjectId(null)
        assertNull(preferences.lastProjectId())

        preferences.setLastProjectId("   ")
        assertNull(preferences.lastProjectId())
    }
}
