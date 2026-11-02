/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.billing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who gets to activate on which build.
 *
 * Two failures meet here, and they point in opposite directions: a re-signed APK must not keep the
 * paid features it was patched to keep, and the operator who *bought* a licence must not lose it
 * because the developer rotated a signing key. The app used to answer the first case by refusing
 * every activation before looking at the key, which made the second case permanent - the new
 * certificate could never be adopted, and the only way out was clearing the app's data, i.e.
 * deleting every room the operator had measured.
 */
class ActivationGateTest {

    private fun report(
        debuggable: Boolean = false,
        debuggerAttached: Boolean = false,
        signature: String? = "AAAA",
        certificateChanged: Boolean = false,
    ) = AppIntegrity.Report(
        debuggable = debuggable,
        debuggerAttached = debuggerAttached,
        signature = signature,
        certificateChanged = certificateChanged,
    )

    @Test
    fun `an untouched build accepts a valid key`() {
        assertEquals(ActivationGate.ACCEPT, activationGate(report(), licenceValid = true))
    }

    @Test
    fun `a valid key adopts a rotated certificate`() {
        assertEquals(
            ActivationGate.ADOPT_SIGNATURE,
            activationGate(report(certificateChanged = true), licenceValid = true),
        )
    }

    @Test
    fun `a key that does not verify cannot re-trust a changed build`() {
        // Otherwise the certificate check would protect nothing: paste anything, get Pro.
        assertEquals(ActivationGate.REFUSE, activationGate(report(certificateChanged = true), licenceValid = false))
    }

    @Test
    fun `a changed build whose new digest is unreadable cannot be adopted`() {
        assertEquals(
            ActivationGate.REFUSE,
            activationGate(report(signature = null, certificateChanged = true), licenceValid = true),
        )
    }

    @Test
    fun `a debugger on a release build is refused even with a valid key`() {
        assertEquals(
            ActivationGate.REFUSE,
            activationGate(report(debuggerAttached = true), licenceValid = true),
        )
    }

    @Test
    fun `a debug build is not mistaken for a tampered one`() {
        // Beta testers run debug builds with a debugger attached all day; that is not an attack.
        assertEquals(
            ActivationGate.ACCEPT,
            activationGate(report(debuggable = true, debuggerAttached = true), licenceValid = true),
        )
    }
}
