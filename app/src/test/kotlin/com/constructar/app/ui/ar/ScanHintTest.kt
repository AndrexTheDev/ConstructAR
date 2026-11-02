/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the AR overlay says about the room scan.
 *
 * The TSDF volume has a hard block budget, and when it is spent the mesh stops growing exactly
 * where the operator is still walking along the wall. The engine computed the flag and the app
 * never read it, so the HUD kept printing "Room 41 % · 28 k triangles" with a number that had
 * quietly stopped rising: a stuck scan looks like a slow one, and the room the protocol claims to
 * have scanned is only part of the room (finding 97).
 *
 * The decision is a pure function so the priority can be tested without a camera: the budget
 * outranks both the gauge and the "walk the walls" instruction.
 */
class ScanHintTest {

    @Test
    fun `a full volume is said out loud, never a gauge that stopped moving`() {
        assertEquals(
            ScanHint.FULL,
            scanHint(showScanner = true, ready = true, hitBlockBudget = true, meshVersion = 7),
        )
    }

    @Test
    fun `the budget outranks the instruction and the first extraction`() {
        // A budget spent before any mesh existed is still a budget spent - telling the operator to
        // keep walking would be the old defect with a new sentence.
        assertEquals(
            ScanHint.FULL,
            scanHint(showScanner = true, ready = true, hitBlockBudget = true, meshVersion = 0),
        )
    }

    @Test
    fun `nothing is said about a scanner that is off or missing`() {
        assertEquals(
            ScanHint.NONE,
            scanHint(showScanner = false, ready = true, hitBlockBudget = true, meshVersion = 3),
        )
        assertEquals(
            ScanHint.UNAVAILABLE,
            scanHint(showScanner = true, ready = false, hitBlockBudget = true, meshVersion = 0),
        )
    }

    @Test
    fun `a healthy scan shows the instruction once and then the gauge`() {
        assertEquals(
            ScanHint.INSTRUCTION,
            scanHint(showScanner = true, ready = true, hitBlockBudget = false, meshVersion = 0),
        )
        assertEquals(
            ScanHint.PROGRESS,
            scanHint(showScanner = true, ready = true, hitBlockBudget = false, meshVersion = 4),
        )
    }
}
