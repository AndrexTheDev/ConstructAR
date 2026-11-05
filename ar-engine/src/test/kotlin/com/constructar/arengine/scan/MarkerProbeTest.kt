/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.core.calib.MarkerSweep
import com.constructar.core.calib.MarkerSweepOptions
import com.constructar.core.calib.MarkerSweepState
import com.constructar.core.calib.YuvPlane
import com.constructar.core.math.Pose
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.constructar.nativebridge.AprilTagReport
import com.constructar.nativebridge.AprilTagSightingReport
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * From a camera frame to a sweep state, with the decoder replaced by a constant answer.
 *
 * The point of these tests is the *composition*: which tag is carried, what a frame with nothing in
 * it does to the sweep, and that the sighting leaves with the session the sweep was opened for. The
 * arithmetic below is [com.constructar.core.calib.MarkerBaseline]'s and is tested where it lives.
 */
class MarkerProbeTest {

    private class StubDecoder(private val answer: AprilTagReport?) : TagDecoder {
        var calls = 0

        override fun decode(
            gray: ByteArray,
            plane: YuvPlane,
            camera: CameraIntrinsics,
            tagSizeMetres: Float,
        ): AprilTagReport? {
            calls++
            return answer
        }
    }

    private val camera = CameraIntrinsics(fx = 800f, fy = 800f, cx = 320f, cy = 240f)
    private val plane = YuvPlane(width = 16, height = 16, rowStride = 16, pixelStride = 1)
    private val buffer = ByteBuffer.wrap(ByteArray(256) { 0x7F })
    private val pose = Pose(Quat.IDENTITY, Vec3(0f, 0f, 0f))

    private fun tag(id: Int = 3, x: Float = 0.1f, y: Float = 0.05f, z: Float = 2f) =
        AprilTagSightingReport(id = id, xMetres = x, yMetres = y, zMetres = z, decisionMargin = 0.9f, hamming = 0)

    private fun probe(decoder: TagDecoder, sessionId: Long = 7L): MarkerProbe {
        val sweep = MarkerSweep.start(
            MarkerSweepOptions(
                sessionId = sessionId,
                minBaselineMetres = 0.2f,
                maxRelativeDeviation = 0.04f,
            ),
        )!!
        return MarkerProbe(MarkerFrameReader(decoder), sweep)
    }

    @Test
    fun `a frame with a tag in it becomes one sighting`() {
        val decoder = StubDecoder(AprilTagReport(listOf(tag())))
        val probe = probe(decoder)
        assertEquals(MarkerSweepState.Watching(0, 0, 0), probe.state)

        val state = probe.onFrame(buffer, plane, camera, pose)

        assertEquals(1, decoder.calls)
        assertEquals(MarkerSweepState.Watching(sightings = 1, markerIds = 1, ignoredForeign = 0), state)
        assertSame(state, probe.state)
    }

    @Test
    fun `a frame with no tag in it is not a sighting`() {
        val probe = probe(StubDecoder(AprilTagReport(emptyList())))
        assertEquals(MarkerSweepState.Watching(0, 0, 0), probe.onFrame(buffer, plane, camera, pose))
    }

    @Test
    fun `a decoder that could not look is not a sighting`() {
        val probe = probe(StubDecoder(null))
        assertEquals(MarkerSweepState.Watching(0, 0, 0), probe.onFrame(buffer, plane, camera, pose))
    }

    @Test
    fun `two frames are two sightings of this session, not foreign ones`() {
        // The stamp is the whole point: a probe that left `sessionId` at its default would have
        // every second sighting counted as foreign and never get a factor. Seeing them arrive with
        // `ignoredForeign` still 0 is what proves the sighting carries the sweep's own session.
        val probe = probe(StubDecoder(AprilTagReport(listOf(tag()))), sessionId = 7L)
        probe.onFrame(buffer, plane, camera, pose)
        val state = probe.onFrame(buffer, plane, camera, pose)
        assertEquals(MarkerSweepState.Watching(sightings = 2, markerIds = 1, ignoredForeign = 0), state)
    }

    @Test
    fun `two tags in one frame are two markers, not one`() {
        // The field that proves it is `markerIds`: until finding 236 this probe took the nearest
        // tag only, so a printed sheet of tags arrived as a single marker and the count could
        // never exceed 1.
        val decoder = StubDecoder(AprilTagReport(listOf(tag(id = 3), tag(id = 4, x = -0.3f, z = 2.4f))))
        val probe = probe(decoder)

        val state = probe.onFrame(buffer, plane, camera, pose)

        assertEquals(MarkerSweepState.Watching(sightings = 2, markerIds = 2, ignoredForeign = 0), state)
    }

    @Test
    fun `a tag behind the camera does not take the tag beside it with it`() {
        val decoder = StubDecoder(AprilTagReport(listOf(tag(id = 3, z = -2f), tag(id = 4, z = 2.4f))))
        val probe = probe(decoder)

        val state = probe.onFrame(buffer, plane, camera, pose)

        assertEquals(MarkerSweepState.Watching(sightings = 1, markerIds = 1, ignoredForeign = 0), state)
    }

    @Test
    fun `more sightings from one spot are still no factor`() {
        // Two frames of two tags is four sightings, and every pair of them shares a camera pose:
        // the baseline is zero, so `MarkerBaseline` refuses the pair instead of publishing a 1.0.
        val decoder = StubDecoder(AprilTagReport(listOf(tag(id = 3), tag(id = 4, x = -0.3f, z = 2.4f))))
        val probe = probe(decoder)
        probe.onFrame(buffer, plane, camera, pose)
        val state = probe.onFrame(buffer, plane, camera, pose)

        assertEquals(MarkerSweepState.Watching(sightings = 4, markerIds = 2, ignoredForeign = 0), state)
    }

    @Test
    fun `a tag behind the camera is not a place to have been`() {
        val probe = probe(StubDecoder(AprilTagReport(listOf(tag(z = -2f)))))
        assertEquals(MarkerSweepState.Watching(0, 0, 0), probe.onFrame(buffer, plane, camera, pose))
    }

    @Test
    fun `reset ends the sweep`() {
        val probe = probe(StubDecoder(AprilTagReport(listOf(tag()))))
        probe.onFrame(buffer, plane, camera, pose)
        assertEquals(MarkerSweepState.Off, probe.reset())
    }
}
