/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.arengine.depth.DepthSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DepthAcquisitionModeTest {

    @Test
    fun `production policy tries matching raw depth and confidence before full depth`() {
        assertEquals(
            listOf(DepthSource.RAW, DepthSource.FULL),
            acquisitionOrder(DepthAcquisitionMode.PREFER_RAW),
        )
    }

    @Test
    fun `raw only never substitutes a smoothed frame without matching confidence`() {
        assertEquals(listOf(DepthSource.RAW), acquisitionOrder(DepthAcquisitionMode.RAW_ONLY))
    }

    @Test
    fun `full only never acquires raw depth or its confidence channel`() {
        assertEquals(listOf(DepthSource.FULL), acquisitionOrder(DepthAcquisitionMode.FULL_ONLY))
    }
}
