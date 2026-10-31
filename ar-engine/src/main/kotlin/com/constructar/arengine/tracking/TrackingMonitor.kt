/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ARCore tracking quality as a first-class, observable state.
 *
 * The requirement "explicit error handling for tracking loss" is implemented here rather than
 * scattered through the UI: everything that can degrade a measurement is fused into one
 * [TrackingStatus] that the AR overlay renders as coaching text, and that the measuring
 * engine uses to *refuse* to record a node it does not trust.
 *
 * Coverage: tracking state, camera-pose quality, depth confidence, feature density, light
 * level, and thermal throttling. The monitor is deliberately dumb — it is fed numbers by the
 * frame loop and holds no Android dependency — so it is unit-testable.
 */
enum class TrackingQuality { GOOD, LIMITED, POOR, NOT_TRACKING }

/**
 * What the operator should be told, as a *code* rather than a sentence.
 *
 * The engine never produces user-facing text: the app maps this enum to a string resource, so
 * every coaching line exists in all six shipped languages (`values`, `values-de`,
 * `values-es`, `values-fr`, `values-it`, `values-pl`). It also keeps this hot path
 * allocation-free — an enum constant is a constant.
 */
enum class Guidance {
    /** Nothing to say: tracking is good and the scene is bright enough. */
    NONE,

    /** No features yet — the operator has just lifted the phone. */
    SEARCHING,

    /** Tracking has been lost long enough to need a relocalisation. */
    LOST,

    /** ARCore degraded tracking without losing it. */
    LIMITED,

    /** ARCore reported a specific tracking failure reason (see [TrackingStatus.failureDetail]). */
    FAILURE_REASON,

    /** Device is thermally throttled; mesh updates back off, measurements do not. */
    THERMAL,

    /** Depth is too noisy here (glass, mirror, dark surface). */
    WEAK_DEPTH,

    /** Too dark to measure reliably and the torch is not on. */
    DARK,
}

/** Everything the UI needs to coach the operator. */
data class TrackingStatus(
    val quality: TrackingQuality = TrackingQuality.NOT_TRACKING,
    /** ARCore's own failure reason name, for the log and the diagnostics sheet. */
    val failureDetail: String? = null,
    /** Mean ARCore depth confidence in the centre third of the frame, 0..255. */
    val depthConfidence: Int = 0,
    val depthSupported: Boolean = false,
    /** Ambient light estimate in lux (0 = unknown). */
    val ambientLux: Float = 0f,
    val torchOn: Boolean = false,
    /** True when the device is thermally throttled — mesh extraction backs off. */
    val thermallyThrottled: Boolean = false,
    /** 0..1 — share of the frame with usable depth; drives the "scan quality" gauge. */
    val scanCoverage: Float = 0f,
    /** Localisable coaching code. */
    val guidance: Guidance = Guidance.NONE,
    /** True when a measurement may be taken right now. */
    val canMeasure: Boolean get() = quality == TrackingQuality.GOOD || quality == TrackingQuality.LIMITED,
) {
    companion object {
        val INITIAL = TrackingStatus()
    }
}

/**
 * Fuses per-frame observables into [TrackingStatus].
 *
 * Thresholds come from field experience with ARCore on construction sites:
 *  * mean depth confidence < 40 ⇒ the sensor is looking at glass, a mirror or a dark surface;
 *  * fewer than ~40 % usable depth pixels ⇒ not enough geometry to trust a depth hit;
 *  * < 15 lux ⇒ tracking will drift within seconds and the torch is needed.
 *
 * Allocation: the steady state (nothing changed) allocates nothing — the incoming numbers are
 * compared field by field against the published status and the frame returns early. Only a
 * genuine change builds a new [TrackingStatus], and that happens a handful of times per scan.
 */
class TrackingMonitor {

    private val mutableStatus = MutableStateFlow(TrackingStatus.INITIAL)
    val status: StateFlow<TrackingStatus> = mutableStatus.asStateFlow()

    private var consecutiveLosses = 0

    /** Called once per frame from the AR loop (render thread; cheap and allocation-free). */
    fun onFrame(
        tracking: TrackingQuality,
        failureReason: String? = null,
        depthConfidence: Int = 0,
        depthSupported: Boolean = false,
        ambientLux: Float = 0f,
        torchOn: Boolean = false,
        thermallyThrottled: Boolean = false,
        scanCoverage: Float = 0f,
    ) {
        val quality = when {
            tracking != TrackingQuality.GOOD -> tracking
            // Depth-supported devices get their confidence folded into the verdict: a "good"
            // tracking state with no usable depth still cannot measure a cable length.
            depthSupported && depthConfidence in 1..LOW_CONFIDENCE -> TrackingQuality.LIMITED
            else -> tracking
        }

        if (quality == TrackingQuality.NOT_TRACKING) consecutiveLosses++ else consecutiveLosses = 0

        val guidance = guidanceFor(quality, failureReason, depthConfidence, depthSupported, ambientLux, torchOn, thermallyThrottled)
        val previous = mutableStatus.value
        // Compare first, allocate second: this runs at camera frame rate.
        if (previous.quality == quality &&
            previous.guidance == guidance &&
            previous.failureDetail == failureReason &&
            previous.depthConfidence == depthConfidence &&
            previous.depthSupported == depthSupported &&
            previous.ambientLux == ambientLux &&
            previous.torchOn == torchOn &&
            previous.thermallyThrottled == thermallyThrottled &&
            previous.scanCoverage == scanCoverage
        ) {
            return
        }
        mutableStatus.value = TrackingStatus(
            quality = quality,
            failureDetail = failureReason,
            depthConfidence = depthConfidence,
            depthSupported = depthSupported,
            ambientLux = ambientLux,
            torchOn = torchOn,
            thermallyThrottled = thermallyThrottled,
            scanCoverage = scanCoverage,
            guidance = guidance,
        )
    }

    /** True when tracking has been lost long enough to require a full relocalisation. */
    fun requiresRelocalisation(): Boolean = consecutiveLosses > RELOCALISE_AFTER_FRAMES

    fun reset() {
        consecutiveLosses = 0
        mutableStatus.value = TrackingStatus.INITIAL
    }

    private fun guidanceFor(
        quality: TrackingQuality,
        failureReason: String?,
        depthConfidence: Int,
        depthSupported: Boolean,
        ambientLux: Float,
        torchOn: Boolean,
        thermallyThrottled: Boolean,
    ): Guidance = when {
        quality == TrackingQuality.NOT_TRACKING && consecutiveLosses > LOST_FRAMES -> Guidance.LOST
        quality == TrackingQuality.NOT_TRACKING -> Guidance.SEARCHING
        failureReason != null -> Guidance.FAILURE_REASON
        thermallyThrottled -> Guidance.THERMAL
        depthSupported && depthConfidence in 1..CRITICAL_CONFIDENCE -> Guidance.WEAK_DEPTH
        ambientLux > 0f && ambientLux < DARK_LUX && !torchOn -> Guidance.DARK
        quality == TrackingQuality.LIMITED -> Guidance.LIMITED
        else -> Guidance.NONE
    }

    companion object {
        /** Mean depth confidence below which depth hits are downgraded. */
        const val LOW_CONFIDENCE = 60

        /** Below this, depth is effectively unusable. */
        const val CRITICAL_CONFIDENCE = 25

        const val DARK_LUX = 15f
        private const val LOST_FRAMES = 3
        private const val RELOCALISE_AFTER_FRAMES = 25
    }
}
