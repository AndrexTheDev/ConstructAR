/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Turning an exception into something an electrician can act on.
 *
 * Before this, a failed write showed the platform's sentence in the app's own error dialog -
 * `Export failed: java.io.IOException: write failed: ENOSPC (No space left on device)` - which is
 * English, Java, and a POSIX error number, in an app that speaks six languages. The three cases
 * below are the ones that actually happen, and each has a different next step.
 */
class StorageFailureTest {

    @Test
    fun `a full phone is recognised from the message Linux writes`() {
        assertEquals(
            StorageFailure.OUT_OF_SPACE,
            storageFailureOf(IOException("write failed: ENOSPC (No space left on device)")),
        )
    }

    @Test
    fun `android's own wording counts as a full phone too`() {
        assertEquals(
            StorageFailure.OUT_OF_SPACE,
            storageFailureOf(IOException("Insufficient storage")),
        )
    }

    @Test
    fun `a revoked grant is not reported as a full phone`() {
        assertEquals(
            StorageFailure.NOT_WRITABLE,
            storageFailureOf(SecurityException("Permission Denial: writing to a revoked uri")),
        )
    }

    @Test
    fun `a read-only card is not reported as a full phone`() {
        assertEquals(
            StorageFailure.NOT_WRITABLE,
            storageFailureOf(IOException("open failed: EACCES (Read-only file system)")),
        )
    }

    @Test
    fun `the reason is found below a wrapper`() {
        // Android's providers wrap the real IOException, so the cause chain is what matters.
        val wrapped = IOException("Could not write", IOException("ENOSPC (No space left on device)"))
        assertEquals(StorageFailure.OUT_OF_SPACE, storageFailureOf(wrapped))
    }

    @Test
    fun `an unexplainable failure still gets a sentence`() {
        assertEquals(StorageFailure.OTHER, storageFailureOf(FileNotFoundException()))
        assertEquals(StorageFailure.OTHER, storageFailureOf(RuntimeException(null, null)))
    }

    @Test
    fun `every case has its own sentence`() {
        val ids = StorageFailure.entries.map { it.messageRes() }
        assertEquals(ids.size, ids.distinct().size, "each storage failure needs its own message")
    }
}
