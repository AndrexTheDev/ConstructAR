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
 * The attributions the app carries.
 *
 * A licence notice is the kind of thing that is written once, looks right, and quietly stops
 * being right: a dependency is added and nobody thinks about the Help screen. Apache-2.0 §4 and
 * BSD-3-Clause both make the notice an obligation of *distribution*, so "the list exists" is not
 * enough - it has to name what the APK actually links (finding 109).
 */
class ThirdPartyNoticesTest {

    @Test
    fun `the components the release checklist names are all attributed`() {
        // The same six names `docs/RELEASE_CHECKLIST.md` asks a release engineer to tick off.
        // `verify_sources.py` rule 20 reads them out of that document; this test holds the list
        // itself to the same content, so neither can be edited in isolation and pass.
        val required = listOf(
            "SceneView", "Filament", "ARCore", "LiteRT", "RTAB-Map", "Depth Anything V2 Small",
        )
        val names = ThirdPartyNotices.ALL.map { it.name }
        for (component in required) {
            assertTrue(
                "$component is linked into the app, so its notice has to be shown - list: $names",
                names.any { it.contains(component) },
            )
        }
    }

    @Test
    fun `every notice has a name, a licence and a copyright holder`() {
        for (notice in ThirdPartyNotices.ALL) {
            assertTrue("a notice with no component name: $notice", notice.name.isNotBlank())
            assertTrue("${notice.name} names no licence", notice.licence.isNotBlank())
            assertTrue(
                "${notice.name} carries no copyright line - the licence requires one",
                notice.copyright.contains("Copyright"),
            )
            // No user-visible prose lives here, so the title is the one line the UI formats.
            assertEquals("${notice.name} - ${notice.licence}", notice.title)
        }
    }

    @Test
    fun `no notice claims a licence this product may not ship`() {
        for (notice in ThirdPartyNotices.ALL) {
            assertTrue(
                "${notice.name} is listed as ${notice.licence}; only fee-free licences may ship",
                notice.licence in ThirdPartyNotices.ALLOWED_LICENCES,
            )
        }
    }

    @Test
    fun `the BSD-3 component carries the reservation BSD-3 requires`() {
        val bsd = ThirdPartyNotices.ALL.filter { it.licence == "BSD-3-Clause" }
        assertTrue("BSD-3-Clause is a listed licence, so something uses it", bsd.isNotEmpty())
        for (notice in bsd) {
            assertTrue(
                "${notice.name} must reproduce the copyright notice and the reservation",
                notice.copyright.contains("All rights reserved"),
            )
        }
    }
}
