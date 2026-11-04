/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.scan.ThermalFrameGate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The thermal down-sample is a count of frames, not a reading of the clock (finding 169).
 *
 * `TsdfScanPipeline` used to skip the frames whose `frame.timestamp` was odd. Frame stamps are
 * nanoseconds from the camera HAL, so on a device that reports them quantised to microseconds
 * the period (33 333 000 ns) is even and the parity never changes: the pipeline then skipped
 * either every frame — the mesh stops updating while the HUD says it is integrating half of
 * them — or none at all.
 */
class ThermalFrameGateTest {

    @Test
    fun `the gate admits every other frame and never the same one twice`() {
        val gate = ThermalFrameGate()
        assertEquals(
            "the cadence must alternate from the first throttled frame",
            listOf(true, false, true, false, true, false),
            List(6) { gate.admits() },
        )
    }

    @Test
    fun `half the frames of a run are integrated`() {
        val gate = ThermalFrameGate()
        assertEquals(
            "a 30-frame run at THROTTLED must integrate 15 of them, which is what the mode " +
                "promises and what the clock could not guarantee",
            15,
            (1..30).count { gate.admits() },
        )
    }
}
