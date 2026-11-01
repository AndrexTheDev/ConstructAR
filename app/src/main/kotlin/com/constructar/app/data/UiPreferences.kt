/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import android.content.Context

/**
 * The smallest possible store of "has the operator seen this screen already".
 *
 * One fact lives here, and it is about the UI rather than the data: whether the three-step
 * introduction has been shown. (This note used to promise a second fact - a "help screen has
 * been opened" flag that would raise the "?" affordance until it had - but no such flag was ever
 * read by anyone; the help button is simply always there, which is the better answer.) Everything
 * else has its own store, so nothing about the operator's work depends on this file, and losing
 * it costs the operator one introduction.
 */
class UiPreferences(context: Context) {

    private val preferences = context.getSharedPreferences("constructar.ui", Context.MODE_PRIVATE)

    fun onboardingDone(): Boolean = preferences.getBoolean(KEY_ONBOARDING, false)

    fun setOnboardingDone() {
        preferences.edit().putBoolean(KEY_ONBOARDING, true).apply()
    }

    /** Shown once: the introduction can be re-opened from the help screen at any time. */
    fun resetOnboarding() {
        preferences.edit().putBoolean(KEY_ONBOARDING, false).apply()
    }

    /**
     * The room the operator was in when the process died.
     *
     * Not the operator's data - the room itself is on disk - but the fact that he was standing in
     * it. Android kills a backgrounded measuring tool without warning while he answers the phone,
     * and coming back to a list of rooms when you were half-way through one is the kind of thing
     * that makes a tool feel broken. Cleared when he closes the room, so a cold start goes home
     * rather than back into last week's basement.
     */
    fun lastProjectId(): String? = preferences.getString(KEY_LAST_PROJECT, null)?.takeIf { it.isNotBlank() }

    fun setLastProjectId(id: String?) {
        preferences.edit().apply {
            if (id.isNullOrBlank()) remove(KEY_LAST_PROJECT) else putString(KEY_LAST_PROJECT, id)
        }.apply()
    }

    private companion object {
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_LAST_PROJECT = "last_project_id"
    }
}
