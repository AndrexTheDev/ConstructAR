/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the customer sees on the glass is what the customer signs on the page.
 *
 * The pad draws a dot when a stroke is a single point (a touch, not a drag) and a line otherwise.
 * The bitmap used to draw only the paths, so a protocol could come out of an app that showed ink
 * with a blank signature box - and the customer had no way to know until he held the paper
 * (finding 41). These are raster assertions on purpose: the bug was in the pixels, not in the
 * stroke list.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SignaturePadTest {

    private fun pad(vararg strokes: List<Offset>): SignaturePadState {
        val state = SignaturePadState()
        state.surface = IntSize(1_000, 360)
        state.strokes = strokes.toList()
        return state
    }

    private fun inkedPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) count++
            }
        }
        return count
    }

    @Test
    fun `an empty pad has no signature to print`() {
        assertNull(SignaturePadState().bitmap())
        val measured = SignaturePadState()
        measured.surface = IntSize(1_000, 360)
        assertNull(measured.bitmap())
    }

    @Test
    fun `a touch without a drag still leaves a signature`() {
        val bitmap = assertNotNull(pad(listOf(Offset(500f, 180f))).bitmap())
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        assertTrue(inkedPixels(bitmap) > 0, "a tapped pad must not print a blank signature")
    }

    @Test
    fun `a dragged signature is drawn and the page stays white`() {
        val bitmap = assertNotNull(
            pad(listOf(Offset(100f, 300f), Offset(400f, 60f), Offset(900f, 320f))).bitmap(),
        )
        val inked = inkedPixels(bitmap)
        assertTrue(inked > 100, "the stroke must be on the page: $inked pixels")
        // The box is a printed signature field: the ink is the only thing on it.
        assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(0, 0))
        assertEquals(1_000, bitmap.width)
        assertEquals(360, bitmap.height)
    }
}
