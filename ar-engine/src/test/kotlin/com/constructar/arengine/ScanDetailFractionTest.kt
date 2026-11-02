/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.scan.TsdfScanPipeline
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The number the HUD prints as a percentage while the operator walks the walls.
 *
 * It used to read "Room 41 %" - a claim about how much of the room had been scanned - and it was
 * `triangleCount / 68_000`: a share of a detail budget. A cluttered cellar filled it with a third
 * of the walls captured, an empty hall never reached it, and the operator decided whether to stop
 * walking on a number that did not mean what its label said (finding 102). The label is "Detail"
 * now, and this test pins the quantity behind it - the budget, the clamp, and the absence of any
 * other meaning.
 */
class ScanDetailFractionTest {

    private fun fractionFor(triangles: Int): Float =
        TsdfScanPipeline.ScanState(triangleCount = triangles).detailFraction

    @Test
    fun `an empty volume is zero and the budget is 68 000 triangles`() {
        assertTrue("an empty mesh must not show a filled gauge", abs(fractionFor(0) - 0f) < 1e-6f)
        assertTrue("half the budget is half the gauge", abs(fractionFor(34_000) - 0.5f) < 1e-6f)
        assertTrue("the budget itself fills it", abs(fractionFor(68_000) - 1f) < 1e-6f)
    }

    @Test
    fun `a dense mesh cannot print more than 100 %`() {
        assertEquals(1f, fractionFor(400_000), 0f)
        assertEquals(1f, fractionFor(Int.MAX_VALUE), 0f)
    }
}
