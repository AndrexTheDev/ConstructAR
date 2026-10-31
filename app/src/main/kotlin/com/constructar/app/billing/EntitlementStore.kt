/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.billing

import android.content.Context
import com.constructar.core.licence.Entitlements
import com.constructar.core.licence.LicenceCheck
import com.constructar.core.licence.LicenceVerifier
import com.constructar.core.licence.Plan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Where "what has this operator paid for" actually lives.
 *
 * Two facts are stored, both in app-private `SharedPreferences`:
 *
 *  * the **licence string** — verified offline on every start, so it cannot be edited into
 *    something valid without the developer's private key;
 *  * the **trial start day** — a one-shot 7-day Pro trial, no payment details asked for.
 *
 * Everything else is derived. [refresh] is cheap and idempotent, so the app calls it on resume:
 * that is how a licence that expires at midnight stops working at midnight without a timer,
 * a service or a background job.
 *
 * The installation id is a random UUID generated on first start and never leaves the device.
 * Licences bind to a *hash prefix* of it, so the developer can tie a purchase to a phone without
 * either side ever handling a hardware identifier.
 */
class EntitlementStore(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val verifier = LicenceVerifier(LicencePublicKey.PUBLIC_KEY_BASE64)

    private val mutableEntitlements = MutableStateFlow(Entitlements.FREE)
    val entitlements: StateFlow<Entitlements> = mutableEntitlements.asStateFlow()

    private val mutableLicenceText = MutableStateFlow("")
    val licenceText: StateFlow<String> = mutableLicenceText.asStateFlow()

    /** The code the operator sends along with the transaction id when paying. */
    val deviceTag: String = LicenceVerifier.deviceTag(installationId())

    init {
        refresh()
    }

    /** Re-derives the entitlements from the stored licence, the trial and today's date. */
    fun refresh() {
        val today = LicenceVerifier.epochDay(clock())
        val stored = preferences.getString(KEY_LICENCE, null).orEmpty().trim()
        mutableLicenceText.value = stored
        val check = if (stored.isEmpty()) null else verifier.verify(stored, deviceTag, today)
        mutableEntitlements.value = when (check) {
            is LicenceCheck.Valid -> check.entitlements
            else -> trialEntitlements(today)
        }
    }

    /**
     * Verifies [raw] and, if it is valid, stores it.
     *
     * Returns the check either way: the caller renders the specific failure (a typo, a key for
     * another phone, an expired subscription) instead of a generic "invalid".
     */
    fun activate(raw: String): LicenceCheck {
        val check = verifier.verify(raw, deviceTag, LicenceVerifier.epochDay(clock()))
        if (check is LicenceCheck.Valid) {
            preferences.edit().putString(KEY_LICENCE, raw.trim()).apply()
            refresh()
        }
        return check
    }

    /** Starts the one-shot trial. Returns false if it was already used. */
    fun startTrial(): Boolean {
        if (preferences.contains(KEY_TRIAL_START)) return false
        preferences.edit().putLong(KEY_TRIAL_START, LicenceVerifier.epochDay(clock())).apply()
        refresh()
        return true
    }

    fun trialUsed(): Boolean = preferences.contains(KEY_TRIAL_START)

    /** Days of trial left, `0` when there is no trial. Used by the paywall copy. */
    fun trialDaysLeft(): Long {
        val start = preferences.getLong(KEY_TRIAL_START, -1L)
        if (start < 0L) return 0L
        val today = LicenceVerifier.epochDay(clock())
        return (start + Entitlements.TRIAL_DAYS - today).coerceAtLeast(0L)
    }

    fun clearLicence() {
        preferences.edit().remove(KEY_LICENCE).apply()
        refresh()
    }

    private fun trialEntitlements(today: Long): Entitlements {
        val start = preferences.getLong(KEY_TRIAL_START, -1L)
        if (start < 0L) return Entitlements.FREE
        val lastDay = start + Entitlements.TRIAL_DAYS
        if (today > lastDay) return Entitlements.FREE
        return Entitlements.of(Plan.TRIAL, expiresEpochDay = lastDay, todayEpochDay = today)
    }

    private fun installationId(): String {
        val existing = preferences.getString(KEY_INSTALL_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTALL_ID, generated).apply()
        return generated
    }

    private companion object {
        const val PREFERENCES_NAME = "constructar.licence"
        const val KEY_LICENCE = "licence"
        const val KEY_TRIAL_START = "trial_start_epoch_day"
        const val KEY_INSTALL_ID = "install_id"
    }
}
