/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the camera's cache copy may be deleted.
 *
 * Without a storage permission the camera writes into the app's own cache and the app copies the
 * picture into private storage under the entry's token. Nothing removed the cache copy, so every
 * site photo was stored twice - tens of megabytes the operator cannot see, in the one app whose
 * promise is that it fits on a cheap phone (finding 128).
 *
 * The case that must *not* delete is the interesting one: when the copy into private storage fails,
 * the cache file is the only copy of a photograph of a wall that no longer looks like that.
 */
class CaptureFileTest {

    @Test
    fun `a cancelled camera leaves nothing to keep`() {
        assertTrue(CaptureFile.shouldDelete(captured = false, attached = false))
    }

    @Test
    fun `a photo that reached private storage takes its cache copy with it`() {
        assertTrue(CaptureFile.shouldDelete(captured = true, attached = true))
    }

    @Test
    fun `a photo whose copy failed stays where it is`() {
        assertFalse(CaptureFile.shouldDelete(captured = true, attached = false))
    }
}
