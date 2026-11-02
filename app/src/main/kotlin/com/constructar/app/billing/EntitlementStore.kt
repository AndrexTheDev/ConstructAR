/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.billing

import android.content.Context
import com.constructar.core.licence.ClockGuard
import com.constructar.core.licence.EntitlementRules
import com.constructar.core.licence.Entitlements
import com.constructar.core.licence.LicenceCheck
import com.constructar.core.licence.LicenceSeal
import com.constructar.core.licence.LicenceVerifier
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
 *
 * Three things make the local state hard to walk past:
 *
 *  * **The clock cannot go backwards.** A high-water mark of the latest calendar day is kept and
 *    sealed; expiry is judged against it (`ClockGuard`), so setting the phone a year back does
 *    not resurrect an expired subscription or restart the trial.
 *  * **The trial and the watermark are sealed** with a key derived from the installation id
 *    (`LicenceSeal`), so editing `SharedPreferences` produces a value that no longer checks out
 *    and the trial counts as used.
 *  * **The build is checked against itself** (`AppIntegrity`): a re-signed APK, or a release
 *    build with a debugger attached, gets no paid features — while the operator's own projects
 *    stay readable and exportable, because a licensing problem must never hold his measurements
 *    hostage. A *valid licence* re-establishes trust in a re-signed build (`activationGate`),
 *    which is what makes a legitimate key rotation survivable for the operator who paid.
 */
class EntitlementStore(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Kept only for [AppIntegrity], which needs the package manager. */
    private val appContext: Context = context.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val verifier = LicenceVerifier(LicencePublicKey.PUBLIC_KEY_BASE64)

    private val mutableEntitlements = MutableStateFlow(Entitlements.FREE)
    val entitlements: StateFlow<Entitlements> = mutableEntitlements.asStateFlow()

    private val mutableLicenceText = MutableStateFlow("")
    val licenceText: StateFlow<String> = mutableLicenceText.asStateFlow()

    /** Why the paid plan looks the way it does - for the licence screen, not for gating. */
    data class Diagnostics(
        val clockRolledBack: Boolean = false,
        val integrityFailed: Boolean = false,
        val localStateTampered: Boolean = false,
        val trialAvailable: Boolean = true,
    )

    private val mutableDiagnostics = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = mutableDiagnostics.asStateFlow()

    /**
     * True once a stored value has failed its seal - read by [refresh], cleared by it.
     *
     * Declared *before* `init` on purpose: property initialisers run in declaration order, so a
     * flag declared below would be reset to `false` after the first refresh had set it.
     */
    private var localStateTampered = false

    /**
     * The code the operator sends along with the transaction id when paying.
     *
     * Lazy on purpose: deriving it reads (or writes, on first start) the installation id in
     * `SharedPreferences`, and the result is a pure function of that id. Computing it on first
     * use keeps the licence constructor's ordering irrelevant, and every later caller - the
     * paywall, activation, verification - reads the same cached value.
     */
    val deviceTag: String by lazy { LicenceVerifier.deviceTag(installationId()) }

    init {
        refresh()
    }

    /** Re-derives the entitlements from the stored licence, the trial, the watermark and today. */
    fun refresh() {
        val installation = installationId()
        val now = LicenceVerifier.epochDay(clock())
        val watermark = sealedLong(KEY_WATERMARK, KEY_WATERMARK_SEAL, installation, fallback = 0L)
        val trialStart = sealedLong(KEY_TRIAL_START, KEY_TRIAL_SEAL, installation, fallback = EntitlementRules.NO_TRIAL)

        // The day every expiry decision is made against: the device clock, unless it went back.
        val verdict = ClockGuard.evaluate(now, watermark)

        val stored = preferences.getString(KEY_LICENCE, null).orEmpty().trim()
        mutableLicenceText.value = stored
        val check = if (stored.isEmpty()) null else verifier.verify(stored, deviceTag, verdict.effectiveEpochDay)
        val licence = (check as? LicenceCheck.Valid)?.entitlements

        val integrity = AppIntegrity.check(
            context = appContext,
            knownSignature = preferences.getString(KEY_SIGNATURE, null),
            remember = { signature -> rememberSignature(signature) },
        )

        val decision = EntitlementRules.decide(
            EntitlementRules.Input(
                licence = licence,
                trialStartEpochDay = trialStart,
                watermarkEpochDay = watermark,
                nowEpochDay = now,
                integrityOk = !integrity.tampered,
            ),
        )
        // The watermark only ever rises; writing it back on every refresh is what makes it a
        // watermark rather than a timestamp.
        persistSealed(KEY_WATERMARK, KEY_WATERMARK_SEAL, installation, decision.watermarkEpochDay.toString())

        mutableEntitlements.value = decision.entitlements
        mutableDiagnostics.value = Diagnostics(
            clockRolledBack = decision.clockRolledBack,
            integrityFailed = decision.integrityFailed,
            localStateTampered = localStateTampered,
            trialAvailable = decision.trialAvailable,
        )
        localStateTampered = false
    }

    /**
     * Verifies [raw] and, if it is valid, stores it.
     *
     * Returns the check either way: the caller renders the specific failure (a typo, a key for
     * another phone, an expired subscription) instead of a generic "invalid".
     */
    fun activate(raw: String): LicenceCheck {
        // The build's identity is checked *alongside* the key rather than before it: see
        // `activationGate` for why a valid licence has to be able to adopt a rotated certificate
        // (it is the documented way back in) and why a debugger never is.
        val integrity = AppIntegrity.check(
            context = appContext,
            knownSignature = preferences.getString(KEY_SIGNATURE, null),
            remember = { signature -> rememberSignature(signature) },
        )

        val installation = installationId()
        val watermark = sealedLong(KEY_WATERMARK, KEY_WATERMARK_SEAL, installation, fallback = 0L)
        val verdict = ClockGuard.evaluate(LicenceVerifier.epochDay(clock()), watermark)
        val check = verifier.verify(raw, deviceTag, verdict.effectiveEpochDay)

        when (activationGate(integrity, licenceValid = check is LicenceCheck.Valid)) {
            ActivationGate.REFUSE -> return LicenceCheck.UntrustedBuild
            ActivationGate.ADOPT_SIGNATURE -> integrity.signature?.let { rememberSignature(it) }
            ActivationGate.ACCEPT -> Unit
        }

        if (check is LicenceCheck.Valid) {
            preferences.edit().putString(KEY_LICENCE, raw.trim()).apply()
            // Re-derives everything, including the integrity report, which now matches.
            refresh()
        }
        return check
    }

    /** Writes the certificate digest the paid features are conditioned on. */
    private fun rememberSignature(signature: String) {
        preferences.edit().putString(KEY_SIGNATURE, signature).apply()
    }

    /**
     * Starts the one-shot trial. Returns false if it was already used, if the clock has been
     * moved back, or if the build cannot be trusted.
     */
    fun startTrial(): Boolean {
        refresh()
        if (!mutableDiagnostics.value.trialAvailable) return false
        val installation = installationId()
        val verdict = ClockGuard.evaluate(LicenceVerifier.epochDay(clock()), sealedLong(KEY_WATERMARK, KEY_WATERMARK_SEAL, installation, 0L))
        persistSealed(KEY_WATERMARK, KEY_WATERMARK_SEAL, installation, verdict.watermarkEpochDay.toString())
        persistSealed(KEY_TRIAL_START, KEY_TRIAL_SEAL, installation, verdict.effectiveEpochDay.toString())
        refresh()
        return true
    }

    fun trialUsed(): Boolean = preferences.contains(KEY_TRIAL_START)

    /** Days of trial left, `0` when there is no trial. Used by the paywall copy. */
    fun trialDaysLeft(): Long {
        val installation = installationId()
        val start = sealedLong(KEY_TRIAL_START, KEY_TRIAL_SEAL, installation, EntitlementRules.NO_TRIAL)
        if (start < 0L) return 0L
        val watermark = sealedLong(KEY_WATERMARK, KEY_WATERMARK_SEAL, installation, 0L)
        val day = ClockGuard.evaluate(LicenceVerifier.epochDay(clock()), watermark).effectiveEpochDay
        return (start + Entitlements.TRIAL_DAYS - day).coerceAtLeast(0L)
    }

    fun clearLicence() {
        preferences.edit().remove(KEY_LICENCE).apply()
        refresh()
    }

    private fun installationId(): String {
        val existing = preferences.getString(KEY_INSTALL_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTALL_ID, generated).apply()
        return generated
    }

    // ── Sealed local state ───────────────────────────────────────────────────────────────

    /**
     * Reads a sealed number, or [fallback] when it is absent.
     *
     * A value whose seal does not match is *not* trusted: for the trial that means "already
     * used" and for the watermark it means "no protection", which can only ever make the app
     * stricter, never more generous.
     */
    private fun sealedLong(key: String, sealKey: String, installation: String, fallback: Long): Long {
        val raw = preferences.getString(key, null) ?: return fallback
        if (!LicenceSeal.matches(installation, raw, preferences.getString(sealKey, null))) {
            localStateTampered = true
            return if (key == KEY_TRIAL_START) 0L else fallback
        }
        return raw.toLongOrNull() ?: fallback
    }

    private fun persistSealed(key: String, sealKey: String, installation: String, value: String) {
        preferences.edit()
            .putString(key, value)
            .putString(sealKey, LicenceSeal.seal(installation, value))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "constructar.licence"
        const val KEY_LICENCE = "licence"
        const val KEY_TRIAL_START = "trial_start_epoch_day"
        const val KEY_TRIAL_SEAL = "trial_start_seal"
        const val KEY_WATERMARK = "clock_watermark_epoch_day"
        const val KEY_WATERMARK_SEAL = "clock_watermark_seal"
        const val KEY_SIGNATURE = "signing_certificate_sha256"
        const val KEY_INSTALL_ID = "install_id"
    }
}
