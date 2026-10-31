/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.licence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract between `tools/mint_licence.py` and the app, frozen as a test.
 *
 * The licence below was produced by the *published* tool with the repository's demo key (the
 * private half of which was deliberately destroyed), using `openssl` rather than any JVM code.
 * Verifying it here proves three things that a unit test signing its own key cannot:
 *
 *  1. the tool and the app agree on the wire format, byte for byte;
 *  2. openssl's DER ECDSA signature is accepted by `java.security` — the two ecosystems disagree
 *     about padding often enough to be worth pinning;
 *  3. the base64url implementation in this module round-trips real openssl output.
 *
 * A lifetime licence is used so the fixture never expires and the test cannot rot.
 */
class MintedLicenceTest {

    private val demoPublicKey =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEX9y2jKeTryNpA0TJz9C-Ghm8Lxd8ysDaV5EwL3tsc1vv" +
            "G3Xf3sAdjHzWZ7ezf0hOafsBNLYikpXmIhSQFZXusg"

    private val mintedLifetimeLicence =
        "CAR1.eyJkZXZpY2UiOiIiLCJleHBpcmVzRXBvY2hEYXkiOjAsImlzc3VlZEVwb2NoRGF5IjoyMDQ1NCwicGxhbiI6IkxJRkVUSU1FIiwic2VyaWFsIjoiZGVtby1rZXktbm90LWZvci1zYWxlIn0.MEYCIQDpZNvfsAS6jsJg3HBXCtrmuEP88wrMlJEwkv5ctYBsRAIhAIL0xFDme62iNde0WDpLa6RExZIyoBcQXTfAdCI4zwVR"

    @Test
    fun `a licence minted by the release tool verifies in the app`() {
        val check = LicenceVerifier(demoPublicKey).verify(mintedLifetimeLicence, "ABCDEF123456", 20_000L)
        assertTrue("expected a valid licence, got $check", check is LicenceCheck.Valid)
        val valid = check as LicenceCheck.Valid
        assertEquals(Plan.LIFETIME, valid.entitlements.plan)
        assertTrue(valid.entitlements.isPro)
        assertEquals("demo-key-not-for-sale", valid.licence.serial)
    }

    @Test
    fun `the same licence is refused by a build with a different key`() {
        val otherKey = LicenceVerifier(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEEqQ4k9z1n3V0l0a5r9m2Jp8oQ1s6t4u7v0w3x5y7z9" +
                "B1C2D3E4F5G6H7I8J9K0L1M2N3O4P5Q6R7S8T9U0V1W2X3Y4Z5a6b7",
        )
        assertEquals(LicenceCheck.BadSignature, otherKey.verify(mintedLifetimeLicence, "ABCDEF123456", 20_000L))
    }
}
