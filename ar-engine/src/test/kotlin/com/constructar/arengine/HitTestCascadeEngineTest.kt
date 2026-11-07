/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.hit.HitTestCascadeEngine
import com.constructar.core.model.HitSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HitTestCascadeEngineTest {
    @Test
    fun `a frontal plane with no depth uncertainty has full confidence`() {
        val score = HitTestCascadeEngine.confidence(HitSource.PLANE_POLYGON, 1f, 0f, 0.05f)
        assertEquals(1f, score, 1e-5f)
        assertFalse(HitTestCascadeEngine.needsWarning(score))
    }

    @Test
    fun `incidence and depth uncertainty multiply rather than replace the type weight`() {
        val score = HitTestCascadeEngine.confidence(HitSource.AR_DEPTH_POINT, 0.8f, 0.01f, 0.05f)
        assertEquals(0.85f * 0.8f * 0.8f, score, 1e-5f)
    }

    @Test
    fun `the direct raycast prior belongs to its half weight band`() {
        assertEquals(0.65f, HitSource.DIRECT_DEPTH_RAYCAST.reliability, 1e-5f)
        val score = HitTestCascadeEngine.confidence(HitSource.DIRECT_DEPTH_RAYCAST, 1f, 0.025f, 0.05f)
        assertEquals(0.325f, score, 1e-5f)
        assertTrue(HitTestCascadeEngine.needsWarning(score))
    }

    @Test
    fun `sixty percent is the exact warning boundary`() {
        assertFalse(HitTestCascadeEngine.needsWarning(0.60f))
        assertTrue(HitTestCascadeEngine.needsWarning(0.5999f))
    }

    @Test
    fun `grazing and non finite observations never manufacture confidence`() {
        assertEquals(0f, HitTestCascadeEngine.confidence(HitSource.PLANE_POLYGON, 0f, 0f, 0.05f))
        assertEquals(0f, HitTestCascadeEngine.confidence(HitSource.PLANE_POLYGON, Float.NaN, 0f, 0.05f))
        assertEquals(0f, HitTestCascadeEngine.confidence(HitSource.PLANE_POLYGON, 1f, 0f, 0f))
    }
}
