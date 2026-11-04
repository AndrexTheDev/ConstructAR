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
 * The project screen's exit rule: typed work is work.
 *
 * The AR screen asks before discarding a run with two points in it (finding 26). The project
 * screen - customer name, address, phone, the rate, the material lump sum - did not, although it
 * is the screen that is filled in on site between two phone calls. The back arrow and the system
 * back gesture dropped every unsaved field silently (finding 124).
 *
 * The rule is worth pinning in both directions, because the cheap way to get it wrong is to guard
 * only one of the two groups: a guard that watches the price and forgets the customer's address
 * looks correct on the price screen and still loses the one thing the operator typed last.
 *
 * That is exactly what happened next: the fix counted the customer fields and the price, and the
 * screen has two more drafts - the technician's name, which signs the protocol, and the note for
 * the office - each with its own Save button. Both were outside the guard, so a typed name went
 * over the back arrow without a question, a thumb's width from an address that got one
 * (finding 142).
 */
class ProjectEditsTest {

    @Test
    fun `a screen that matches the store leaves without asking`() {
        assertFalse(
            ProjectEdits.lostByLeaving(
                customerFieldsDiffer = false,
                priceDiffers = false,
                technicianDiffers = false,
                notesDiffer = false,
            ),
        )
    }

    @Test
    fun `an unsaved customer address is worth a question on its own`() {
        assertTrue(
            ProjectEdits.lostByLeaving(
                customerFieldsDiffer = true,
                priceDiffers = false,
                technicianDiffers = false,
                notesDiffer = false,
            ),
        )
    }

    @Test
    fun `an unsaved rate or material sum is worth a question on its own`() {
        assertTrue(
            ProjectEdits.lostByLeaving(
                customerFieldsDiffer = false,
                priceDiffers = true,
                technicianDiffers = false,
                notesDiffer = false,
            ),
        )
    }

    @Test
    fun `both at once is the same answer, not a different one`() {
        assertTrue(
            ProjectEdits.lostByLeaving(
                customerFieldsDiffer = true,
                priceDiffers = true,
                technicianDiffers = true,
                notesDiffer = true,
            ),
        )
    }

    @Test
    fun `an unsaved technician name is worth a question on its own`() {
        // The name printed on the protocol, next to the customer's: typed, then dropped in silence.
        assertTrue(
            ProjectEdits.lostByLeaving(
                customerFieldsDiffer = false,
                priceDiffers = false,
                technicianDiffers = true,
                notesDiffer = false,
            ),
        )
    }

    @Test
    fun `an unsaved note for the office is worth a question on its own`() {
        assertTrue(
            ProjectEdits.lostByLeaving(
                customerFieldsDiffer = false,
                priceDiffers = false,
                technicianDiffers = false,
                notesDiffer = true,
            ),
        )
    }
}
