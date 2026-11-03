/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * Whether leaving the project screen would throw away something the operator typed.
 *
 * The AR screen has guarded its own exit since finding 26: a run with two points in it asks before
 * it is discarded. The project screen - the one with the customer's name, address and phone, the
 * project's rate and its material lump sum - did not, and it is the screen he fills in between two
 * phone calls, with a glove on, one field at a time. The back arrow and the system back gesture
 * popped it and took every unsaved field with them without a word (finding 124).
 *
 * That fix counted two groups and the screen has four. The technician's name and the note for the
 * office each have their own Save button and their own `rememberSaveable` draft, and both were
 * outside the guard: the operator typed "M. Weber" into the field that signs the protocol, pressed
 * the back arrow, and it was gone - without the question the customer's address gets, on the same
 * screen, a thumb's width away (finding 142). Two of four is the shape this rule keeps having, so
 * every group is a named parameter and the screen has to name it.
 *
 * Kept out of the composable so the rule has one home and can be asserted, exactly like
 * `LeaveGuard` next door.
 */
internal object ProjectEdits {

    /**
     * [customerFieldsDiffer] when the name, address or phone on screen is not what the store has;
     * [priceDiffers] when the rate or the material sum is not, including a field that holds
     * something unreadable - a typo is unsaved work as much as a correction is, and leaving without
     * a question is how it disappears; [technicianDiffers] when the name that signs the protocol
     * does not; [notesDiffer] when the note for the office does not.
     */
    fun lostByLeaving(
        customerFieldsDiffer: Boolean,
        priceDiffers: Boolean,
        technicianDiffers: Boolean,
        notesDiffer: Boolean,
    ): Boolean = customerFieldsDiffer || priceDiffers || technicianDiffers || notesDiffer
}
