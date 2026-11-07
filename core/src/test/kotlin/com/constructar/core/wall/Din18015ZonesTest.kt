/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.wall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Din18015ZonesTest {
    @Test
    fun `lower and middle zones use the complete ranges`() {
        listOf(0.15f, 0.30f, 0.45f).forEach {
            assertEquals(Din18015Zones.Horizontal.ZW_U, Din18015Zones.assign(it, 2.6f).zone)
        }
        listOf(1.00f, 1.15f, 1.30f).forEach {
            assertEquals(Din18015Zones.Horizontal.ZW_M, Din18015Zones.assign(it, 2.6f).zone)
        }
    }

    @Test
    fun `upper zone is measured down from the detected ceiling`() {
        assertEquals(Din18015Zones.Horizontal.ZW_O, Din18015Zones.assign(2.30f, 2.60f).zone)
        assertEquals(Din18015Zones.Horizontal.ZW_O, Din18015Zones.assign(2.15f, 2.60f).zone)
        assertEquals(Din18015Zones.Horizontal.SONDER, Din18015Zones.assign(2.10f, 2.60f).zone)
    }

    @Test
    fun `snap uses five centimetres without moving a point outside its band`() {
        assertEquals(0.30f, requireNotNull(Din18015Zones.assign(0.324f, 2.6f).snappedHeightMeters), 1e-5f)
        assertEquals(1.15f, requireNotNull(Din18015Zones.assign(1.126f, 2.6f).snappedHeightMeters), 1e-5f)
        // 27.4 cm below the ceiling rounds to 25 cm: 2.60 m - 0.25 m = 2.35 m.
        assertEquals(2.35f, requireNotNull(Din18015Zones.assign(2.326f, 2.6f).snappedHeightMeters), 1e-5f)
    }

    @Test
    fun `Sonderverlegung is explicit and never snapped into a DIN band`() {
        val result = Din18015Zones.assign(0.70f, 2.6f)
        assertEquals(Din18015Zones.Horizontal.SONDER, result.zone)
        assertTrue(result.sonderverlegung)
        assertNull(result.snappedHeightMeters)
    }

    @Test
    fun `vertical corner zones are independent of horizontal bands`() {
        val corner = Din18015Zones.point(0.70f, 2.6f, 0.10f)
        assertEquals(Din18015Zones.Vertical.ZW_V, corner.vertical)
        assertFalse(corner.sonderverlegung)
        val middle = Din18015Zones.point(0.70f, 2.6f, 0.30f)
        assertEquals(Din18015Zones.Vertical.OUTSIDE, middle.vertical)
        assertTrue(middle.sonderverlegung)
    }

    @Test
    fun `an unknown ceiling never invents an upper zone`() {
        val result = Din18015Zones.assign(2.30f, null)
        assertEquals(Din18015Zones.Horizontal.SONDER, result.zone)
        assertNull(result.snappedHeightMeters)
    }
}
