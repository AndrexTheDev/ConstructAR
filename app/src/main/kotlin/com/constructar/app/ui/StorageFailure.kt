/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.annotation.StringRes
import com.constructar.app.R

/**
 * Why a write to the operator's chosen folder failed, in words he can act on.
 *
 * Both the export and the backup used to print the platform's own message when a write threw:
 * `Export failed: java.io.IOException: write failed: ENOSPC (No space left on device)`. That is
 * English, it is Java, and it names a POSIX error number - in an app that speaks six languages and
 * is aimed at a man standing in a cellar holding a phone. The three things that actually happen
 * are a full phone, a folder the provider will not write to, and everything else, and each has a
 * different next step: free space, pick another folder, try again.
 *
 * The classification is a pure function of the throwable so it can be tested without a phone, a
 * folder or a permission dialog.
 */
internal enum class StorageFailure {
    /** No room left on the device or in the provider's quota. */
    OUT_OF_SPACE,

    /** The grant was revoked, the card is read-only, or the provider refuses writes. */
    NOT_WRITABLE,

    /** Anything else - the operator still gets a sentence rather than a stack trace. */
    OTHER,
}

/** The deepest cause chain that is still plausibly about the write. */
private const val MAX_CAUSE_DEPTH = 5

internal fun storageFailureOf(error: Throwable): StorageFailure {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is SecurityException) return StorageFailure.NOT_WRITABLE
        val message = current.message?.lowercase().orEmpty()
        when {
            // ENOSPC is what Linux says; "insufficient storage" is what Android's own wrappers say.
            "enospc" in message ||
                "no space left" in message ||
                "insufficient storage" in message ||
                "disk full" in message -> return StorageFailure.OUT_OF_SPACE

            // EACCES/EPERM and the read-only cases: the folder exists but cannot be written.
            "eacces" in message ||
                "eperm" in message ||
                "permission denied" in message ||
                "read-only" in message ||
                "readonly" in message ||
                "operation not permitted" in message -> return StorageFailure.NOT_WRITABLE
        }
        current = current.cause
        depth++
    }
    return StorageFailure.OTHER
}

/** The sentence the operator reads instead of the exception. */
@StringRes
internal fun StorageFailure.messageRes(): Int = when (this) {
    StorageFailure.OUT_OF_SPACE -> R.string.write_failed_space
    StorageFailure.NOT_WRITABLE -> R.string.write_failed_not_writable
    StorageFailure.OTHER -> R.string.write_failed_other
}

