/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that keeps a deleted draft deleted.
 *
 * The writes are fire-and-forget, so they can complete in any order. One rule is enough to make the
 * outcome deterministic: only the most recently *submitted* request may write.
 */
class DraftGateTest {

    @Test
    fun `each request gets its own later ticket`() {
        val gate = DraftGate()
        val first = gate.ticket()
        val second = gate.ticket()
        assertTrue(second > first)
    }

    @Test
    fun `the newest request may write`() {
        val gate = DraftGate()
        gate.ticket()
        val newest = gate.ticket()
        assertTrue(gate.isNewest(newest))
    }

    @Test
    fun `a save queued before the delete cannot resurrect the draft`() {
        val gate = DraftGate()
        val save = gate.ticket()   // written on the third committed point
        gate.ticket()              // "Save as run" - deletes it
        assertFalse(gate.isNewest(save), "the delete is the newest decision, so the save must lose")
    }

    @Test
    fun `a delete followed by a new run may write again`() {
        val gate = DraftGate()
        gate.ticket()              // delete, from the leave dialog
        val fresh = gate.ticket()  // the next run's first committed point
        assertTrue(gate.isNewest(fresh))
    }
}
