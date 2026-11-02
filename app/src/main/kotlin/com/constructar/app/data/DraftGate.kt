/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

/**
 * Which of several queued draft writes is still allowed to touch the disk.
 *
 * The draft of a run being measured is written fire-and-forget on every committed point - a write
 * must never block a tap - and the delete that follows "Save as run", "Discard" or a completed
 * run is queued the same way. Both go through `Dispatchers.IO`, and **completion order is not
 * submission order**: the delete can land first and the save that preceded it can land afterwards,
 * putting the draft back on disk. The next entry then offers "recover the interrupted run" for a
 * run that is already saved, and accepting it adds the same conduit twice - twice the metres, twice
 * the fittings, on the invoice.
 *
 * So each request takes a ticket at submission time and only the newest ticket may write. Nothing
 * older can resurrect a file that a later decision removed. Kept as its own tiny class because that
 * rule is the whole defect, and it can be asserted without a phone, a camera or a coroutine.
 */
internal class DraftGate {

    private var newestTicket = 0L

    /** Call in submission order - on the caller's thread, before the write is dispatched. */
    fun ticket(): Long {
        newestTicket += 1
        return newestTicket
    }

    /** True only for the most recently submitted request. */
    fun isNewest(ticket: Long): Boolean = ticket == newestTicket
}
