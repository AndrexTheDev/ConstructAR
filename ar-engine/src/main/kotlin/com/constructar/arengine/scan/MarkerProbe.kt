/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.core.calib.MarkerObservation
import com.constructar.core.calib.MarkerSweep
import com.constructar.core.calib.MarkerSweepState
import com.constructar.core.calib.YuvPlane
import com.constructar.core.math.Pose
import com.constructar.core.math.Vec3
import java.nio.ByteBuffer

/**
 * One marker sweep, driven from camera frames.
 *
 * This is the step that was still missing between the pieces that already existed: a
 * [MarkerFrameReader] that can turn a plane into metric sightings, a [MarkerSweep] that can turn
 * sightings into a factor, and - until now - nothing that put a frame into the first and a sighting
 * into the second. The composition lives here rather than in a composable because it is arithmetic
 * and it can be tested with a fake decoder, all the way from a byte buffer to `Ready`.
 *
 * **One thread.** [onFrame] is written for the render thread and holds no lock: it is called from
 * the camera callback, and the screen reads [state] after the fact. The sweep is immutable, so a
 * reader that catches `state` between two frames gets a consistent one.
 *
 * **Which tags.** Every tag the decoder read, one sighting each. Taking only the nearest one
 * (`AprilTagReport.nearest`, which is what this class did until finding 236) made `MarkerSweep`'s
 * `markerIds` a field that could never exceed 1: a printed *sheet* of tags - the evidence the
 * layout path is built around - would have arrived as a single marker, and the sweep's whole reason
 * for counting ids would have been unreachable through the one caller it has. Two tags in one
 * frame share the camera pose, so a pair between them measures a zero baseline and is refused by
 * [com.constructar.core.calib.MarkerBaseline] rather than silently averaged in; ids are never
 * paired with each other either, so a sheet contributes one baseline per tag and the mean is taken
 * over all of them.
 *
 * **Which sightings count.** All of them - the margin is carried on the observation and never used
 * as a filter here. Grading a read is the decoder's `isClean`, and a read that is wrong by half a
 * metre is what [MarkerSweep]'s deviation filter is for; a second judgement in this class would be
 * a second definition of "a sighting worth trusting", competing with the one the native
 * `TagScaleOptions` already makes.
 */
class MarkerProbe(
    private val reader: MarkerFrameReader,
    start: MarkerSweep,
) {

    private var sweep: MarkerSweep = start

    /** What a status line shows. `Off` only after [reset]. */
    val state: MarkerSweepState get() = sweep.state

    /** The session these sightings belong to; foreign observations are refused by the sweep. */
    val sessionId: Long get() = sweep.options.sessionId

    /**
     * Decodes one frame and adds what it saw, if anything.
     *
     * Returns the state as it now stands, so a caller does not have to re-read [state]. A frame
     * with no tag, an unreadable buffer and a decoder that could not look all leave the sweep
     * untouched - "no tag in view" is not a sighting, and it must not be able to produce a factor.
     */
    fun onFrame(
        source: ByteBuffer,
        plane: YuvPlane,
        camera: CameraIntrinsics,
        cameraPose: Pose,
    ): MarkerSweepState {
        val report = reader.read(source, plane, camera) ?: return sweep.state
        if (report.tags.isEmpty()) return sweep.state
        var next = sweep
        for (sighting in report.tags) {
            val observation = MarkerObservation(
                id = sighting.id,
                tagInCamera = Vec3(sighting.xMetres, sighting.yMetres, sighting.zMetres),
                cameraPose = cameraPose,
                sessionId = sweep.options.sessionId,
                decisionMargin = sighting.decisionMargin,
                hamming = sighting.hamming,
            )
            // A tag behind the camera or a NaN pose is not a place to have been. It is dropped on
            // its own: one unreadable corner of a sheet must not throw away the tags beside it.
            if (!observation.isFinite) continue
            next = next.observe(observation)
        }
        sweep = next
        return sweep.state
    }

    /** Ends the sweep and forgets every sighting; the next start needs a new [MarkerSweep]. */
    fun reset(): MarkerSweepState {
        sweep = sweep.reset()
        return sweep.state
    }
}
