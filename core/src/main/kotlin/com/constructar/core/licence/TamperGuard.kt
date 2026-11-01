/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.licence

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A calendar that cannot be walked backwards.
 *
 * An offline licence expires "on a day", so the only thing between an expired subscription and
 * a working one is the device clock. Setting the phone back a year must therefore not
 * resurrect anything - and it must not damage the local state of an honest operator whose clock
 * was simply wrong for an hour.
 *
 * The rule is a **high-water mark**: the app remembers the last calendar day it has ever seen
 * and never trusts a clock that is behind it. A tolerance of one day absorbs time zones,
 * daylight saving and a phone that synced its clock late; beyond that, going backwards is
 * treated as a rollback, and the run is then evaluated at the watermark instead of at the
 * device clock.
 */
object ClockGuard {

    /** How far the clock may go backwards without being called a rollback. */
    const val TOLERANCE_DAYS: Long = 1L

    data class Verdict(
        /** The day every expiry decision must be made against. */
        val effectiveEpochDay: Long,
        /** The watermark to persist (never smaller than what came in). */
        val watermarkEpochDay: Long,
        /** True when the device clock is behind the watermark by more than the tolerance. */
        val rolledBack: Boolean,
    )

    fun evaluate(
        nowEpochDay: Long,
        watermarkEpochDay: Long,
        toleranceDays: Long = TOLERANCE_DAYS,
    ): Verdict {
        val watermark = watermarkEpochDay.coerceAtLeast(0L)
        val next = maxOf(nowEpochDay, watermark)
        val rolledBack = watermark > 0L && nowEpochDay < watermark - toleranceDays
        return Verdict(
            effectiveEpochDay = if (rolledBack) next else nowEpochDay,
            watermarkEpochDay = next,
            rolledBack = rolledBack,
        )
    }
}

/**
 * A keyed checksum over the small bits of local state that decide what the operator has paid
 * for: the trial start and the clock watermark.
 *
 * The key is derived from the installation id, which never leaves the phone, so editing
 * `SharedPreferences` by hand - the first thing anyone tries on a rooted device - produces a
 * value that no longer checks out. This is deliberately *not* claimed as encryption: a
 * determined attacker can read the APK and re-derive the key. It raises the effort from
 * "change one number in a text file" to "reverse-engineer and re-sign the app", which is the
 * most an offline app without a server can honestly offer.
 */
object LicenceSeal {

    private const val ALGORITHM = "HmacSHA256"
    private const val SALT = "constructar.local-state.v1"
    private const val HEX = "0123456789ABCDEF"

    /** Stable, keyed digest of [value] for this installation. */
    fun seal(installationId: String, value: String): String = runCatching {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(keyFor(installationId), ALGORITHM))
        val digest = mac.doFinal(value.toByteArray(Charsets.UTF_8))
        buildString(digest.size * 2) {
            digest.forEach { byte ->
                val v = byte.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }.getOrDefault("")

    /** 32 bytes of key material, derived from the installation id and a fixed domain string. */
    private fun keyFor(installationId: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("$SALT|$installationId".toByteArray(Charsets.UTF_8))

    /** True when [seal] is exactly what [seal] would produce for this installation and value. */
    fun matches(installationId: String, value: String, seal: String?): Boolean {
        if (seal.isNullOrBlank()) return false
        return constantTimeEquals(seal(installationId, value), seal)
    }

    /**
     * Comparison that does not leak where two strings first differ.
     *
     * Not paranoia for its own sake: the sealed value is checked on every start, on a device
     * the attacker controls, so a short-circuiting `==` is the sort of thing a timing oracle
     * is built on.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].code xor b[index].code)
        return difference == 0
    }
}

/**
 * The single decision "what is this installation entitled to right now".
 *
 * Extracted from the Android store so it can be tested on the host JVM - every branch below is
 * something that used to be a scattered `if` in the app, and every branch is a way to get paid
 * features for free if it is wrong.
 */
object EntitlementRules {

    /** `-1` means the trial was never started. */
    const val NO_TRIAL: Long = -1L

    data class Input(
        /** A licence that already passed signature, device and expiry checks. */
        val licence: Entitlements? = null,
        val trialStartEpochDay: Long = NO_TRIAL,
        val watermarkEpochDay: Long = 0L,
        val nowEpochDay: Long,
        /** False when the installation looks repackaged or is being debugged. */
        val integrityOk: Boolean = true,
        val toleranceDays: Long = ClockGuard.TOLERANCE_DAYS,
    )

    data class Output(
        val entitlements: Entitlements,
        /** Persist this: it must never go down. */
        val watermarkEpochDay: Long,
        val clockRolledBack: Boolean,
        val integrityFailed: Boolean,
        /** Whether the one-shot trial may still be started. */
        val trialAvailable: Boolean,
        /** The day the decision was made against (the watermark after a rollback). */
        val effectiveEpochDay: Long,
    )

    fun decide(input: Input): Output {
        val verdict = ClockGuard.evaluate(input.nowEpochDay, input.watermarkEpochDay, input.toleranceDays)
        val day = verdict.effectiveEpochDay
        val rolledBack = verdict.rolledBack

        // A repackaged or debugged installation gets no paid features at all - and none of the
        // operator's own data is touched, so an honest user of a rebuilt APK can still read and
        // export what he measured.
        if (!input.integrityOk) {
            return Output(
                entitlements = Entitlements.FREE,
                watermarkEpochDay = verdict.watermarkEpochDay,
                clockRolledBack = rolledBack,
                integrityFailed = true,
                trialAvailable = false,
                effectiveEpochDay = day,
            )
        }

        // Defence in depth: the verifier already rejected an expired licence, but the expiry is
        // re-checked here against the *effective* day, and the days-left figure is recomputed -
        // so a store that was patched to hand over a stale licence still grants nothing.
        val licence = input.licence
        val licenceLive = licence != null && licence.isPro &&
            (licence.expiresEpochDay <= 0L || day <= licence.expiresEpochDay)
        if (licence != null && licenceLive) {
            return Output(
                entitlements = Entitlements.of(
                    plan = licence.plan,
                    expiresEpochDay = licence.expiresEpochDay,
                    todayEpochDay = day,
                ),
                watermarkEpochDay = verdict.watermarkEpochDay,
                clockRolledBack = rolledBack,
                integrityFailed = false,
                trialAvailable = false,
                effectiveEpochDay = day,
            )
        }

        // A rollback kills the trial, but never a paid licence: the licence has its own expiry
        // and is verified against the watermark day, so it simply stays expired.
        val trialStart = input.trialStartEpochDay
        val trialUsed = trialStart >= 0L
        val trialActive = trialUsed &&
            !rolledBack &&
            trialStart <= day &&
            day <= trialStart + Entitlements.TRIAL_DAYS
        if (trialActive) {
            return Output(
                entitlements = Entitlements.of(
                    plan = Plan.TRIAL,
                    expiresEpochDay = trialStart + Entitlements.TRIAL_DAYS,
                    todayEpochDay = day,
                ),
                watermarkEpochDay = verdict.watermarkEpochDay,
                clockRolledBack = false,
                integrityFailed = false,
                trialAvailable = false,
                effectiveEpochDay = day,
            )
        }

        return Output(
            entitlements = Entitlements.FREE,
            watermarkEpochDay = verdict.watermarkEpochDay,
            clockRolledBack = rolledBack,
            integrityFailed = false,
            trialAvailable = !trialUsed && !rolledBack,
            effectiveEpochDay = day,
        )
    }
}
