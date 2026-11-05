/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.billing

import com.constructar.app.BuildConfig
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key is a build input now, so the tests check the *handover*, not the value (finding 240).
 *
 * A `buildConfigField` is a string spliced into generated Java, and the value it carries comes
 * from an environment variable a release machine sets by hand. Both ends can mangle it in ways
 * nothing else would notice: a stray quote or `$` breaks the generated source (a build error,
 * harmless), but a trailing newline, a doubled key or a `\n` from a shell heredoc produces a
 * string that compiles, ships and verifies nothing - which is the failure this file exists for.
 */
class LicencePublicKeyTest {

    @Test
    fun `the build field reaches the key object unchanged`() {
        assertEquals(BuildConfig.LICENCE_PUBLIC_KEY, LicencePublicKey.PUBLIC_KEY_BASE64)
    }

    @Test
    fun `the demo flag is the build's, not a second opinion`() {
        assertEquals(BuildConfig.LICENCE_KEY_IS_DEMO, LicencePublicKey.isDemoKey)
    }

    @Test
    fun `the shipped key is one clean base64url string`() {
        val key = LicencePublicKey.PUBLIC_KEY_BASE64
        assertTrue("the key is empty - the build field never reached the app", key.isNotEmpty())
        assertFalse("the key carries whitespace: ${key.take(8)}…", key.any { it.isWhitespace() })
        assertFalse("the key is quoted: the build script spliced twice", key.contains('"'))
        // Base64url of a P-256 SubjectPublicKeyInfo is 88 characters; anything wildly shorter is
        // a truncated paste, and the decoder rejects anything that is not base64 at all.
        assertTrue("the key is too short to be a P-256 SPKI: ${key.length} characters", key.length >= 60)
        val der = Base64.getUrlDecoder().decode(key)
        assertTrue("the DER blob is too short: ${der.size} bytes", der.size >= 60)
        // A P-256 SubjectPublicKeyInfo starts with SEQUENCE(0x30) and carries the id-ecPublicKey
        // OID; asserting the first byte is enough to catch a key pasted with its PEM header.
        assertEquals("the DER blob does not start with an ASN.1 SEQUENCE", 0x30, der[0].toInt())
    }
}
