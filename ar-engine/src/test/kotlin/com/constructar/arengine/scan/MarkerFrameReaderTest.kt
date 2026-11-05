/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.core.calib.YuvPlane
import com.constructar.nativebridge.AprilTagReport
import com.constructar.nativebridge.AprilTagSightingReport
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * From a camera plane to a distance, without a phone.
 *
 * The decoder is replaced by one that records what it was handed, which is the only way to assert
 * the thing that actually breaks here: the buffer the native layer reads must be **packed**, and
 * the stride it is told must be the width. A padded buffer with the camera's stride and a packed
 * buffer with the camera's stride are both "a buffer and a stride"; only one of them decodes a tag.
 */
class MarkerFrameReaderTest {

    private class RecordingDecoder(private val answer: AprilTagReport = AprilTagReport(emptyList())) :
        TagDecoder {
        var calls = 0
        var lastGray: ByteArray? = null
        var lastPlane: YuvPlane? = null
        var lastCamera: CameraIntrinsics? = null
        var lastTagSize = -1f

        override fun decode(
            gray: ByteArray,
            plane: YuvPlane,
            camera: CameraIntrinsics,
            tagSizeMetres: Float,
        ): AprilTagReport? {
            calls++
            lastGray = gray
            lastPlane = plane
            lastCamera = camera
            lastTagSize = tagSizeMetres
            return answer
        }
    }

    private val camera = CameraIntrinsics(fx = 800f, fy = 800f, cx = 320f, cy = 240f)
    private val plane = YuvPlane(width = 16, height = 16, rowStride = 20, pixelStride = 1)

    private fun filled(target: YuvPlane, padding: Byte = 0xEE.toByte()): ByteBuffer {
        val bytes = ByteArray(target.requiredBytes ?: 0) { padding }
        for (row in 0 until target.height) {
            for (column in 0 until target.width) {
                bytes[row * target.rowStride + column * target.pixelStride] =
                    (row * target.width + column).toByte()
            }
        }
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun `the decoder is handed a packed frame and the width as its stride`() {
        val decoder = RecordingDecoder()
        val report = MarkerFrameReader(decoder).read(filled(plane), plane, camera)
        assertNotNull(report)
        assertEquals(1, decoder.calls)
        assertEquals(plane, decoder.lastPlane)
        assertEquals(camera, decoder.lastCamera)
        assertEquals(MarkerFrameReader.DEFAULT_TAG_SIZE_METRES, decoder.lastTagSize, 0f)
        // 256 packed bytes, 0..255: no padding, no second-byte samples.
        assertArrayEquals(ByteArray(256) { it.toByte() }, decoder.lastGray)
    }

    @Test
    fun `a doubled pixel stride reaches the decoder as every second byte`() {
        val doubled = YuvPlane(width = 16, height = 16, rowStride = 32, pixelStride = 2)
        val decoder = RecordingDecoder()
        MarkerFrameReader(decoder).read(filled(doubled), doubled, camera)
        assertArrayEquals(ByteArray(256) { it.toByte() }, decoder.lastGray)
        assertEquals(32, decoder.lastPlane?.rowStride)
    }

    @Test
    fun `a broken question is refused before the decoder is called`() {
        val decoder = RecordingDecoder()
        val tooSmall = MarkerFrameReader(decoder, tagSizeMetres = 0f)
        assertFalse(tooSmall.isUsable)
        assertNull(tooSmall.read(filled(plane), plane, camera))

        val reader = MarkerFrameReader(decoder)
        assertNull(reader.read(filled(plane), plane, camera.copy(fx = 0f)))
        assertNull(reader.read(filled(plane), plane, camera.copy(cx = -1f)))
        assertNull(reader.read(ByteBuffer.wrap(ByteArray(100)), plane, camera))
        assertNull(reader.read(filled(plane), YuvPlane(16, 16, 12, 1), camera))
        assertEquals(0, decoder.calls)
    }

    @Test
    fun `an empty frame is an answer, not a failure`() {
        val decoder = RecordingDecoder()
        val report = MarkerFrameReader(decoder).read(filled(plane), plane, camera)
        assertNotNull(report)
        assertEquals(0, report?.tags?.size)
        assertEquals(1, decoder.calls)
    }

    @Test
    fun `a sighting passes through untouched`() {
        val sighting = AprilTagSightingReport(
            id = 7,
            xMetres = 0f,
            yMetres = 0f,
            zMetres = 2f,
            decisionMargin = 0.85f,
            hamming = 0,
        )
        val reader = MarkerFrameReader(RecordingDecoder(AprilTagReport(listOf(sighting))))
        val report = reader.read(filled(plane), plane, camera)
        assertEquals(listOf(sighting), report?.tags)
        assertEquals(2f, report?.nearest?.distanceMetres ?: 0f, 1e-4f)
    }

    @Test
    fun `one scratch buffer serves frames of different sizes`() {
        val decoder = RecordingDecoder()
        val reader = MarkerFrameReader(decoder)
        reader.read(filled(plane), plane, camera)
        val first = decoder.lastGray
        // A tighter frame reuses the same buffer: the reader must not shrink it, and must not
        // allocate a new one for every frame of a live session.
        val tight = YuvPlane(width = 16, height = 16, rowStride = 16, pixelStride = 1)
        reader.read(filled(tight), tight, camera)
        assertSame(first, decoder.lastGray)
        assertEquals(256, reader.scratchSizeFor(plane))
        assertEquals(0, reader.scratchSizeFor(YuvPlane(16, 16, 12, 1)))
    }
}
