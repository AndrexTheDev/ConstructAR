/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.billing

import com.constructar.app.BuildConfig

/**
 * The public half of the licence signing key.
 *
 * This is the *only* key material in the shipped app: it can verify a licence but cannot
 * create one, so a reader of this repository gains nothing. The private half lives on the
 * developer's machine and is used by `tools/mint_licence.py` when a crypto payment confirms.
 *
 * **There is no key in this file any more** - only a build field (finding 240). Until then the
 * key was a constant here with a demo value and a comment asking a human to swap it before the
 * first sale; nothing enforced that, and a release cut from a checkout that never ran
 * `python3 tools/mint_licence.py --init` verified licences nobody could have signed while looking
 * exactly like a healthy build - the paywall simply never opened. The value now comes from the
 * build: `CONSTRUCTAR_PROD_PUBLIC_KEY` (or `-Pconstructar.prodPublicKey=`) through
 * `app/build.gradle.kts`, and `gradle :app:assembleRelease` refuses the demo key unless the build
 * is explicitly a size check. The replacement therefore happens *before* the first customer, not
 * after, because an APK signed with a different key rejects every licence minted with this one —
 * see docs/BILLING.md.
 *
 * Format: base64url (unpadded) of the DER SubjectPublicKeyInfo of an ECDSA P-256 key.
 */
object LicencePublicKey {

    /** The key this build ships, straight out of `BuildConfig.LICENCE_PUBLIC_KEY`. */
    val PUBLIC_KEY_BASE64: String = BuildConfig.LICENCE_PUBLIC_KEY

    /**
     * True while the build carries the demo key.
     *
     * Derived in Gradle from the same expression that produced [PUBLIC_KEY_BASE64], so it cannot
     * disagree with what is actually in the APK. The Help screen shows the developer note while
     * this is true, and it is always true in a build from this repository.
     */
    val isDemoKey: Boolean = BuildConfig.LICENCE_KEY_IS_DEMO
}
