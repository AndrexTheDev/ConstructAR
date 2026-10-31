/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.licence

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies a licence string against the developer's ECDSA P-256 public key.
 *
 * Only the *public* key ships in the APK (see `LicencePublicKey.kt` in `:app`); the private key
 * stays with the developer and is used by `tools/mint_licence.py` when a payment confirms.
 * Anyone can therefore read the APK and still be unable to mint a valid licence — which is the
 * best an offline app can do, and it needs no server, no Google Play billing and no account.
 *
 * Verification is edge-case-explicit: [LicenceCheck] distinguishes a typo, a forgery, a
 * licence bound to another phone and an expired one, because the operator sees a different,
 * actionable message for each.
 */
class LicenceVerifier(private val publicKeyBase64: String) {

    /** Result of [verify] plus the failure detail, for diagnostics and the UI. */
    fun verify(raw: String, deviceTag: String, todayEpochDay: Long): LicenceCheck {
        val segments = LicenceFormat.split(raw) ?: return LicenceCheck.Malformed
        val licence = LicenceFormat.decode(segments.payloadJson) ?: return LicenceCheck.Malformed
        val plan = Plan.parse(licence.plan) ?: return LicenceCheck.Malformed
        if (!plan.isPurchasable) {
            // "FREE" and "TRIAL" are never minted: they are states the app derives itself.
            return LicenceCheck.Malformed
        }
        if (!signatureIsValid(segments)) return LicenceCheck.BadSignature
        if (!deviceMatches(licence.device, deviceTag)) return LicenceCheck.DeviceMismatch
        if (licence.expiresEpochDay > 0L && todayEpochDay > licence.expiresEpochDay) {
            return LicenceCheck.Expired
        }
        return LicenceCheck.Valid(
            licence = licence,
            entitlements = Entitlements.of(
                plan = plan,
                expiresEpochDay = licence.expiresEpochDay,
                todayEpochDay = todayEpochDay,
            ),
        )
    }

    private fun signatureIsValid(segments: LicenceFormat.Segments): Boolean = runCatching {
        val keyBytes = LicenceFormat.base64UrlDecode(publicKeyBase64.replace(" ", "").replace("\n", ""))
            ?: return false
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(publicKey)
        signature.update(LicenceFormat.signedBytes(segments.payloadBase64))
        signature.verify(segments.signature)
    }.getOrDefault(false)

    companion object {
        /** Epoch day (UTC) of [epochMillis] — licences never depend on the local time zone. */
        fun epochDay(epochMillis: Long): Long = Math.floorDiv(epochMillis, MILLIS_PER_DAY)

        private const val MILLIS_PER_DAY = 86_400_000L

        /**
         * The tag a licence is bound to.
         *
         * A truncated SHA-256 of the random installation id the app generates on first start.
         * Random, not hardware-derived: no IMEI, no ANDROID_ID, nothing that identifies the
         * person or the phone. The first 12 hex characters are plenty to make accidental
         * collisions irrelevant and still short enough to type into an order note.
         */
        fun deviceTag(installationId: String): String = runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(installationId.trim().toByteArray(Charsets.UTF_8))
            buildString {
                digest.take(6).forEach { byte ->
                    val value = byte.toInt() and 0xFF
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0F])
                }
            }
        }.getOrDefault("").uppercase()

        private const val HEX = "0123456789ABCDEF"

        /** Case-insensitive, whitespace-insensitive comparison; an unbound licence matches all. */
        fun deviceMatches(bound: String, actual: String): Boolean {
            val expected = bound.trim().uppercase()
            return expected.isEmpty() || expected == actual.trim().uppercase()
        }
    }
}
