/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What happens to a photo that comes back from the camera app.
 *
 * The capture used to live in a plain `remember`: the token, the URI and the cache file were lost
 * when Android reclaimed the field book while the camera was in front, so the picture the operator
 * had just taken arrived with nothing to attach it to and disappeared without a sentence - on the
 * defect whose photo is the evidence (finding 137).
 */
class PendingCaptureTest {

    private val capture = PendingCapture(
        token = "room__defect_1",
        uriString = "content://com.constructar.app.fileprovider/capture/entry-1.jpg",
        path = "/data/user/0/com.constructar.app/cache/capture/entry-1.jpg",
    )

    @Test
    fun `a capture comes back whole from saved state`() {
        assertEquals(capture, PendingCapture.restore(capture.token, capture.uriString, capture.path))
    }

    @Test
    fun `a capture that lost any of its three strings is no capture`() {
        // Half-restored state must not be guessed at: without the token the picture belongs to
        // nothing, without the URI there is nothing to copy, without the path the cache copy could
        // never be cleaned up.
        assertNull(PendingCapture.restore(null, capture.uriString, capture.path))
        assertNull(PendingCapture.restore(capture.token, null, capture.path))
        assertNull(PendingCapture.restore(capture.token, capture.uriString, null))
        assertNull(PendingCapture.restore("", "", ""))
    }

    @Test
    fun `the camera answer decides between attaching and deleting`() {
        // A picture that came back is attached, a cancelled one leaves an empty file to remove...
        assertEquals(CaptureOutcome.ATTACH, captureOutcome(capture, captured = true))
        assertEquals(CaptureOutcome.DISCARD, captureOutcome(capture, captured = false))
        // ...and a result with no capture known is neither, which is the branch the old code did
        // not have - the state it reached after the process had been reclaimed.
        assertEquals(CaptureOutcome.NOTHING, captureOutcome(null, captured = true))
    }
}
