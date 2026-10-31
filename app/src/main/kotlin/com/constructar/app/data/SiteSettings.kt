/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import android.content.Context

/**
 * Firm-level defaults for the procurement numbers.
 *
 * The waste factor is the one number in the bill of materials that is genuinely firm-specific:
 * a careful shop plans 5 %, a shop that pulls cable through old conduit plans 15 %, and the
 * catalogue default sits in between. An app that insists on its own number is an app whose metre
 * figure the foreman mentally corrects — so the operator can set it, it is stored with the app
 * (not the project), and every screen and export follows the same value.
 *
 * `null` means "use each conduit family's default", which is what a new install starts with.
 */
class SiteSettings(context: Context) {

    private val preferences = context.getSharedPreferences("constructar.settings", Context.MODE_PRIVATE)

    fun wasteFactorOverride(): Float? {
        val stored = preferences.getFloat(KEY_WASTE, UNSET)
        return if (stored == UNSET) null else stored
    }

    fun setWasteFactorOverride(value: Float?) {
        preferences.edit().apply {
            if (value == null) putFloat(KEY_WASTE, UNSET) else putFloat(KEY_WASTE, value)
        }.apply()
    }

    private companion object {
        const val KEY_WASTE = "waste_factor_override"

        /** `-1` is outside every plausible factor, so it can mean "not set". */
        const val UNSET = -1f
    }
}
