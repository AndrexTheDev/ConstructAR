/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The screen said nothing about why two buttons were grey; the first device run reported them as
 * broken (finding 258). These are the sentences it now says, and the thresholds they must not drift
 * away from.
 */
class ActionGatingTest {

    @Test
    fun `an empty run asks for the two points a finish needs`() {
        assertEquals(2, ActionGating.missingPoints(nodeCount = 0, canFinish = false))
    }

    @Test
    fun `one point is not a run either`() {
        assertEquals(2, ActionGating.missingPoints(nodeCount = 1, canFinish = false))
    }

    @Test
    fun `two points finish but do not close a loop`() {
        assertEquals(3, ActionGating.missingPoints(nodeCount = 2, canFinish = true))
    }

    @Test
    fun `three points leave nothing to explain`() {
        assertNull(ActionGating.missingPoints(nodeCount = 3, canFinish = true))
        assertNull(ActionGating.missingPoints(nodeCount = 12, canFinish = true))
    }

    @Test
    fun `the loop threshold is the one the screen enables on`() {
        // `ArMeasureScreen` enables the button at `nodeCount >= 3`; this is the same number, and a
        // test is the only thing that keeps two literals in two files from drifting apart.
        assertEquals(3, ActionGating.NODES_FOR_A_LOOP)
    }
}
