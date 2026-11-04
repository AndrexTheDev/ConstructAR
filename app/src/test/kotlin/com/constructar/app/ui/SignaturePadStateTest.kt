/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A signature the customer just gave must survive the phone being turned.
 *
 * The pad used to keep the strokes in `remember` and in pad *pixels*, so a rotation both threw them
 * away and (had they been kept) squashed them into the new width. Points are fractions of the pad
 * now, and they travel through the saved state; these tests hold the encoding that carries them.
 */
class SignaturePadStateTest {

    private val signature = listOf(
        listOf(Offset(0.20f, 0.40f), Offset(0.35f, 0.55f), Offset(0.50f, 0.30f)),
        listOf(Offset(0.60f, 0.70f), Offset(0.75f, 0.65f)),
    )

    @Test
    fun `a signature survives being packed and unpacked`() {
        assertEquals(signature, decodeStrokes(encodeStrokes(signature)))
    }

    @Test
    fun `the strokes keep their order, because that is the direction the pen moved`() {
        val decoded = decodeStrokes(encodeStrokes(signature))
        assertEquals(2, decoded.size)
        assertEquals(signature[0], decoded[0])
        assertEquals(signature[1], decoded[1])
    }

    @Test
    fun `a single touch is still a signature`() {
        // Finding 41: a tap must print a dot, not a blank page. It has to survive the round trip
        // just as a drawn line does.
        val dot = listOf(listOf(Offset(0.5f, 0.5f)))
        assertEquals(dot, decodeStrokes(encodeStrokes(dot)))
    }

    @Test
    fun `an empty pad encodes to nothing and decodes to nothing`() {
        assertEquals(0, encodeStrokes(emptyList()).size)
        assertTrue(decodeStrokes(null).isEmpty())
        assertTrue(decodeStrokes(FloatArray(0)).isEmpty())
    }

    @Test
    fun `a truncated array is dropped instead of restored half-way`() {
        // A stroke that promises three points but carries two is not something to guess about.
        val broken = floatArrayOf(3f, 0.1f, 0.1f, 0.2f, 0.2f)
        assertTrue(decodeStrokes(broken).isEmpty())
    }

    @Test
    fun `a point is stored as a fraction of the pad, not as a pixel`() {
        val state = SignaturePadState()
        state.surface = androidx.compose.ui.unit.IntSize(1000, 500)
        assertEquals(Offset(0.1f, 0.2f), state.normalise(Offset(100f, 100f)))
        // The same touch on a narrower pad is the same fraction of that pad.
        state.surface = androidx.compose.ui.unit.IntSize(500, 500)
        assertEquals(Offset(0.2f, 0.2f), state.normalise(Offset(100f, 100f)))
    }
    @Test
    fun `a tap is committed as a one-point stroke`() {
        // The pad has two input paths - a drag and a tap - and only the drag one existed, so a
        // customer who touched the glass instead of dragging left an empty pad and an unsigned
        // protocol (finding 82).
        val state = SignaturePadState()
        state.surface = androidx.compose.ui.unit.IntSize(1_000, 360)

        state.commit(listOf(state.normalise(Offset(500f, 180f))))

        assertTrue(!state.isEmpty)
        assertEquals(listOf(listOf(Offset(0.5f, 0.5f))), state.strokes)
    }

    @Test
    fun `a cancelled drag commits nothing`() {
        // `onDragCancel` hands over an empty list; a cancelled touch must not leave a phantom dot.
        val state = SignaturePadState()
        state.commit(emptyList())
        assertTrue(state.isEmpty)
    }

}
