/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refused permanent folder grant is not a failed export.
 *
 * Both writers ask the picker for a persistable grant after the operator chose a folder. Some
 * document providers refuse that request, and Android caps how many persisted grants an app may
 * hold - when either happens the call throws, and while it sat inside the export's own
 * `runCatching` the operator was told the export had failed over a folder he had just picked, with
 * the protocol already built. These cases pin the "best effort" contract the call now has.
 */
class UriGrantsTest {

    @Test
    fun `a refusal is swallowed and reported as not taken`() {
        val taken = UriGrants.take { throw SecurityException("Failed to take persistable permission") }

        assertFalse(taken)
    }

    @Test
    fun `a granted folder runs exactly once and is reported`() {
        var calls = 0

        val taken = UriGrants.take { calls++ }

        assertTrue(taken)
        assertEquals(1, calls)
    }
}
