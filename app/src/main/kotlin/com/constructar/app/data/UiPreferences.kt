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
 * Only two facts live here, and both are about the UI rather than the data: whether the
 * three-step introduction has been shown, and whether the help screen has ever been opened
 * (used to raise the "?" affordance until it has been). Everything else — projects, licence,
 * trial — has its own store, so nothing about the operator's work depends on this file.
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

    private companion object {
        const val KEY_ONBOARDING = "onboarding_done"
    }
}
