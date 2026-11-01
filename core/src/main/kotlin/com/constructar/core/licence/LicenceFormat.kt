/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.licence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A licence payload — what the developer signs when a crypto payment has confirmed.
 *
 * The app is offline by contract, so a licence cannot be "checked against a server". Instead it
 * is a **self-contained statement** the phone can verify on its own: the payload below, signed
 * with an ECDSA P-256 key that never ships inside the APK. Verification is therefore a pure
 * function of (key text, device tag, today) — no network, no clock server, no account, and it
 * keeps working in a basement for as long as the licence lasts.
 *
 * The wire format is three dot-separated fields:
 *
 * ```
 * CAR1.<base64url(payload JSON)>.<base64url(ECDSA-P256-SHA256 signature)>
 * ```
 *
 * The signature covers the ASCII bytes of `CAR1.<payload>`, so the signed text and the
 * transmitted text are byte-identical and no JSON canonicalisation can break a valid key.
 */
@Serializable
data class Licence(
    /** One of [Plan] names: `MONTHLY`, `YEARLY`, `LIFETIME`. */
    val plan: String,
    /**
     * Device tag this licence is bound to, or `""` for "any device".
     *
     * Binding is what stops one purchase being shared across a site crew. It is deliberately a
     * hash prefix, not a device identifier: the app never sees or stores a hardware ID.
     */
    val device: String = "",
    /** Day the licence was issued (epoch day, UTC). Informational. */
    val issuedEpochDay: Long = 0L,
    /** Last valid day (epoch day, UTC); `0` for a lifetime licence. */
    val expiresEpochDay: Long = 0L,
    /** Human-readable order reference (crypto transaction id prefix, invoice number, …). */
    val serial: String = "",
)

/** Result of checking a licence string. Exhaustive on purpose: the UI must say *why* it failed. */
sealed interface LicenceCheck {
    data class Valid(val licence: Licence, val entitlements: Entitlements) : LicenceCheck

    /** Not three fields, wrong prefix, bad base64, bad JSON, or unknown plan. */
    data object Malformed : LicenceCheck

    /** Well-formed but not signed by the developer's key — i.e. forged or mistyped. */
    data object BadSignature : LicenceCheck

    /** The licence is bound to a different device. */
    data object DeviceMismatch : LicenceCheck

    /** Valid signature, but the licence expired (subscription not renewed). */
    data object Expired : LicenceCheck

    /**
     * The installation itself is not trusted: it was re-signed (so this is not the build that
     * was released) or a debugger is attached in a release build. Signature, device and expiry
     * may all be fine - it is the app around them that cannot be vouched for.
     */
    data object UntrustedBuild : LicenceCheck
}

/**
 * The text format itself: assembly, splitting and the URL-safe base64 the fields use.
 *
 * `java.util.Base64` is API 26 on Android and this app supports API 24, and pulling a base64
 * library in for two functions would be silly — so the alphabet is implemented here, and it is
 * covered by the unit tests in this package.
 */
object LicenceFormat {
    const val PREFIX: String = "CAR1"

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val REVERSE: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun encode(licence: Licence): String = json.encodeToString(Licence.serializer(), licence)

    fun decode(payloadJson: String): Licence? = runCatching {
        json.decodeFromString(Licence.serializer(), payloadJson)
    }.getOrNull()

    /** `CAR1.<payload>.<signature>` from already-encoded parts. */
    fun assemble(payloadBase64: String, signatureBase64: String): String =
        "$PREFIX.$payloadBase64.$signatureBase64"

    /** The exact bytes a signature must cover. */
    fun signedBytes(payloadBase64: String): ByteArray = "$PREFIX.$payloadBase64".toByteArray(Charsets.UTF_8)

    /** Parsed-but-unverified view of a licence string. */
    data class Segments(val payloadBase64: String, val payloadJson: String, val signature: ByteArray) {
        /** Constant-time-ish comparison helper for tests; the real check uses [java.security]. */
        fun signatureBase64(): String = base64UrlEncode(signature)
    }

    fun split(raw: String): Segments? {
        val text = raw.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        val parts = text.split('.')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val payloadBytes = base64UrlDecode(parts[1]) ?: return null
        val signature = base64UrlDecode(parts[2]) ?: return null
        val payloadJson = runCatching { payloadBytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        return Segments(payloadBase64 = parts[1], payloadJson = payloadJson, signature = signature)
    }

    fun base64UrlEncode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(ALPHABET[(triple ushr 18) and 0x3F])
            out.append(ALPHABET[(triple ushr 12) and 0x3F])
            if (index + 1 < bytes.size) out.append(ALPHABET[(triple ushr 6) and 0x3F])
            if (index + 2 < bytes.size) out.append(ALPHABET[triple and 0x3F])
            index += 3
        }
        return out.toString()
    }

    fun base64UrlDecode(text: String): ByteArray? {
        if (text.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (char in text) {
            if (char == '=') break
            val value = if (char.code < 128) REVERSE[char.code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
