/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first run on a phone showed an unusable HUD: the action stack grew taller than the display
 * and drew itself over the status bar, and three buttons side by side wrapped their labels to one
 * character per line. These are the numbers that keep both from happening again (finding 257).
 */
class HudLayoutTest {

    @Test
    fun `three buttons fit side by side on a normal phone`() {
        // 360 dp is the narrowest width most phones report.
        assertEquals(3, HudLayout.columns(360f, 3, 1f))
    }

    @Test
    fun `a narrow phone stacks them`() {
        // (300 - 32 - 20) / 3 = 82.7 dp per button: "Close loop" would wrap per character.
        assertEquals(1, HudLayout.columns(300f, 3, 1f))
    }

    @Test
    fun `a large system font stacks them`() {
        assertEquals(3, HudLayout.columns(360f, 3, 1f))
        assertEquals(1, HudLayout.columns(360f, 3, 1.6f))
    }

    @Test
    fun `two buttons survive a large font on a wide screen`() {
        // (400 - 32 - 10) / 2 = 179 dp, and a 1.3x font needs 124.8 dp.
        assertEquals(2, HudLayout.columns(400f, 2, 1.3f))
    }

    @Test
    fun `a single button is never stacked into two columns`() {
        assertEquals(1, HudLayout.columns(120f, 1, 2f))
        assertEquals(1, HudLayout.columns(1080f, 1, 1f))
    }

    @Test
    fun `the status bar and the sheet never claim the whole screen`() {
        val scales = listOf(1f, 1.15f, 1.3f, 1.5f, 2f, 2.5f)
        var height = 200f
        while (height <= 1600f) {
            for (scale in scales) {
                val status = HudLayout.statusMaxHeightDp(height, scale)
                val sheet = HudLayout.sheetMaxHeightDp(height, scale)
                assertTrue(
                    "height $height at font $scale: status $status + sheet $sheet leaves no camera",
                    status + sheet <= height * HudLayout.MAX_TOTAL_FRACTION + 0.01f,
                )
                assertTrue("height $height at font $scale: nothing left for the sheet", sheet > 0f)
                assertTrue("height $height at font $scale: nothing left for the status", status > 0f)
            }
            height += 25f
        }
    }

    @Test
    fun `a short screen still shows both panels`() {
        // A 320 dp-tall window (a small phone in landscape) is the worst case that shipped.
        val status = HudLayout.statusMaxHeightDp(320f, 1f)
        val sheet = HudLayout.sheetMaxHeightDp(320f, 1f)
        assertTrue("status is at least its floor", status >= HudLayout.MIN_STATUS_DP - 0.01f)
        assertTrue("sheet is at least its floor", sheet >= 160f - 0.01f)
        assertTrue(status + sheet < 320f)
    }

    @Test
    fun `a larger font never gives the sheet less room`() {
        assertTrue(HudLayout.sheetMaxHeightDp(640f, 1f) < HudLayout.sheetMaxHeightDp(640f, 2f))
        assertTrue(HudLayout.statusMaxHeightDp(640f, 1f) < HudLayout.statusMaxHeightDp(640f, 2f))
    }

    @Test
    fun `a taller screen gives the sheet more room than a short one`() {
        assertTrue(HudLayout.sheetMaxHeightDp(400f, 1f) < HudLayout.sheetMaxHeightDp(900f, 1f))
    }

    @Test
    fun `the sheet never exceeds its ceiling`() {
        val scales = listOf(1f, 2f, 5f)
        for (height in listOf(320f, 640f, 900f, 1400f)) {
            for (scale in scales) {
                val sheet = HudLayout.sheetMaxHeightDp(height, scale)
                assertTrue(
                    "height $height at font $scale: sheet $sheet over the ceiling",
                    sheet <= height * HudLayout.SHEET_MAX_FRACTION + 0.01f,
                )
            }
        }
    }
}
