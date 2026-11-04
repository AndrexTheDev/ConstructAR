/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * The one capture that is out with the camera app, in a form that survives this process dying.
 *
 * The field book stores it as three strings in saved state rather than as an object in a plain
 * `remember`, because the camera is a *separate app*: to make room for it Android is entitled to
 * reclaim this one, and a phone that has just been measuring with AR - native buffers, a depth
 * model, a rendered scene - is the first candidate. The operator closes the shutter, the system
 * hands the result back to a recreated app, and the screen it rebuilds had a `pending` field of
 * `null`: the picture came back, nothing claimed it, and the diary line or the defect it was taken
 * for stayed without a photo (finding 137). The evidence is the one thing on the punch list a
 * second visit cannot reconstruct, and nothing on screen ever said it was gone.
 *
 * Kept free of Android types on purpose - the URI stays a string and the file a path - so the
 * restore can be unit-tested on the JVM, where `Uri.parse` is not available.
 */
internal data class PendingCapture(
    /** Token of the diary entry or defect the picture belongs to. */
    val token: String,
    /** The FileProvider URI handed to the camera, as a string. */
    val uriString: String,
    /** Absolute path of the file in the cache the camera is writing into. */
    val path: String,
) {
    companion object {
        /**
         * Rebuilds the capture from the three saved strings, or `null`.
         *
         * All three are needed: without the token the picture would be attached to nothing,
         * without the URI there is nothing to copy from, and without the path the cache copy could
         * never be cleaned up. Saved state written by an older build, or half restored, is
         * therefore answered as "no capture" instead of guessed at.
         */
        fun restore(token: String?, uriString: String?, path: String?): PendingCapture? {
            if (token.isNullOrEmpty() || uriString.isNullOrEmpty() || path.isNullOrEmpty()) return null
            return PendingCapture(token, uriString, path)
        }
    }
}

/** What the camera's answer lets the screen do with the capture it had sent out. */
internal enum class CaptureOutcome {
    /** A picture came back and the capture still knows where it belongs: attach it. */
    ATTACH,

    /** The operator cancelled: the empty file the camera left in the cache goes. */
    DISCARD,

    /** No capture is known: nothing to attach, and no path whose file could be deleted. */
    NOTHING,
}

/**
 * The single decision point between the camera's answer and the cache copy.
 *
 * `NOTHING` is the branch the old code did not have. When saved state was missing it dropped the
 * result silently, which is how a taken photo disappeared without a sentence; it is a state the
 * screen may still reach (a camera that answers after the operator left the room), and it is
 * answered as "nothing to do" rather than by inventing a destination.
 */
internal fun captureOutcome(pending: PendingCapture?, captured: Boolean): CaptureOutcome = when {
    pending == null -> CaptureOutcome.NOTHING
    captured -> CaptureOutcome.ATTACH
    else -> CaptureOutcome.DISCARD
}
