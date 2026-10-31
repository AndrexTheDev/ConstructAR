/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.billing

/**
 * The public half of the licence signing key.
 *
 * This is the *only* key material in the shipped app: it can verify a licence but cannot
 * create one, so a reader of this repository gains nothing. The private half lives on the
 * developer's machine and is used by `tools/mint_licence.py` when a crypto payment confirms.
 *
 * The key committed here is a **demo key whose private half was discarded**. That is
 * deliberate: it means no build of this repository — and no fork of it — accepts a licence
 * that anybody but the owner of the private key can produce. Before the first sale, run
 *
 * ```
 * python3 tools/mint_licence.py --init
 * ```
 *
 * and paste the printed constant over the value below, then ship that build. An APK signed
 * with a different key rejects every licence minted with this one, so the replacement has to
 * happen *before* the first customer, not after — see docs/BILLING.md.
 *
 * Format: base64url (unpadded) of the DER SubjectPublicKeyInfo of an ECDSA P-256 key.
 */
object LicencePublicKey {
    const val PUBLIC_KEY_BASE64: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEX9y2jKeTryNpA0TJz9C-Ghm8Lxd8ysDaV5EwL3tsc1vv" +
            "G3Xf3sAdjHzWZ7ezf0hOafsBNLYikpXmIhSQFZXusg"

    /** True while the demo key above is still in the build — the paywall warns about it in debug. */
    val isDemoKey: Boolean
        get() = PUBLIC_KEY_BASE64 == DEMO_KEY_BASE64

    private const val DEMO_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEX9y2jKeTryNpA0TJz9C-Ghm8Lxd8ysDaV5EwL3tsc1vv" +
            "G3Xf3sAdjHzWZ7ezf0hOafsBNLYikpXmIhSQFZXusg"
}
