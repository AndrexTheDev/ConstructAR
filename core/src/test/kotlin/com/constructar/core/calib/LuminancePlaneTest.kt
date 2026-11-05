/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera's plane is not a picture, and the decoder needs one.
 *
 * Every case here is a number a real device reports: a wide plane whose rows carry burst padding,
 * a sensor that samples every second byte, a buffer exactly one byte short, and a plane that is
 * eight pixels across - which a real camera never produces and a corrupt `Image` happily can.
 */
class LuminancePlaneTest {

    private fun plane(width: Int = 16, height: Int = 16, rowStride: Int = 20, pixelStride: Int = 1) =
        YuvPlane(width, height, rowStride, pixelStride)

    /** A plane whose sample at `(row, column)` holds `(row * width + column)`, padding marked 0xEE. */
    private fun filled(plane: YuvPlane, padding: Byte = 0xEE.toByte()): ByteBuffer {
        val bytes = ByteArray(plane.requiredBytes ?: 0) { padding }
        for (row in 0 until plane.height) {
            for (column in 0 until plane.width) {
                bytes[row * plane.rowStride + column * plane.pixelStride] =
                    (row * plane.width + column).toByte()
            }
        }
        return ByteBuffer.wrap(bytes)
    }

    private fun picture(size: Int = 256): ByteArray = ByteArray(size) { it.toByte() }

    @Test
    fun `a padded plane is packed without the padding`() {
        // Sixteen samples per row, twenty bytes apart: the four filler bytes per row must not
        // appear. If they did, every row after the first would shift by four - the picture would
        // look like a picture and the tag would shear.
        val padded = plane()
        val out = ByteArray(256)
        assertTrue(LuminancePlane.pack(filled(padded), padded, out))
        assertArrayEquals(picture(), out.copyOf(256))
        assertEquals(316, padded.requiredBytes ?: -1)
        assertEquals(256, padded.packedBytes ?: -1)
    }

    @Test
    fun `a doubled pixel stride takes every second byte`() {
        val doubled = plane(rowStride = 32, pixelStride = 2)
        val out = ByteArray(256)
        assertTrue(LuminancePlane.pack(filled(doubled), doubled, out))
        assertArrayEquals(picture(), out.copyOf(256))
        assertEquals(511, doubled.requiredBytes ?: -1)
    }

    @Test
    fun `a buffer one byte short is refused, and nothing is written`() {
        val short = ByteBuffer.wrap(ByteArray(315))  // a padded 16x16 plane needs 316
        val out = ByteArray(256) { 0x5A }
        assertFalse(LuminancePlane.pack(short, plane(), out))
        assertArrayEquals(ByteArray(256) { 0x5A }, out)
    }

    @Test
    fun `an output smaller than the picture is refused, a longer one is not`() {
        val buffer = filled(plane())
        assertFalse(LuminancePlane.pack(buffer, plane(), ByteArray(255)))
        // One scratch buffer, reused across frames of different sizes: it may be longer.
        assertTrue(LuminancePlane.pack(buffer, plane(), ByteArray(4096)))
    }

    @Test
    fun `a plane no camera could produce is not a plane`() {
        assertFalse(plane(rowStride = 12).isUsable)          // rows shorter than the picture
        assertFalse(plane(rowStride = 80).isUsable)          // padding beyond alignment
        assertFalse(plane(pixelStride = 0).isUsable)
        assertFalse(plane(pixelStride = 8).isUsable)
        assertFalse(plane(width = 8).isUsable)               // eight pixels across is not a frame
        assertFalse(plane(width = 9000, height = 9000, rowStride = 9000).isUsable)
        assertNull(LuminancePlane.packedSize(plane(rowStride = 12)))
        assertTrue(plane(width = 640, height = 480, rowStride = 704).isUsable)
        assertEquals(640 * 480, LuminancePlane.packedSize(plane(width = 640, height = 480, rowStride = 704)) ?: -1)
    }

    @Test
    fun `the largest plane the guard allows still fits an Int`() {
        // The byte count is multiplied in `Long` and refused if it does not fit, so that a wrapped
        // length cannot pass a bounds check as "small enough". This asserts the bound itself: the
        // widest, tallest, most padded plane the guard admits is still under 2^31 bytes.
        val largest = YuvPlane(
            width = LuminancePlane.MAX_SIDE,
            height = LuminancePlane.MAX_SIDE,
            rowStride = LuminancePlane.MAX_SIDE * LuminancePlane.MAX_STRIDE_FACTOR,
            pixelStride = LuminancePlane.MAX_PIXEL_STRIDE,
        )
        val bytes = LuminancePlane.requiredBytes(largest)
        assertNotNull(bytes)
        assertEquals(268_435_453, bytes ?: -1)
        assertNull(LuminancePlane.requiredBytes(YuvPlane(0, 16, 16, 1)))
    }

    @Test
    fun `the same plane packed twice gives the same bytes`() {
        val buffer = filled(plane())
        val first = ByteArray(256)
        val second = ByteArray(256)
        assertTrue(LuminancePlane.pack(buffer, plane(), first))
        assertTrue(LuminancePlane.pack(buffer, plane(), second))
        assertArrayEquals(first, second)
        // And the source's position is where it started: the plane buffer belongs to the session.
        assertEquals(0, buffer.position())
    }
}
