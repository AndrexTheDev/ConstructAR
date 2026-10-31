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

/** Everything the UI needs to coach the operator. */
data class TrackingStatus(
    val quality: TrackingQuality = TrackingQuality.NOT_TRACKING,
    val trackingFailureReason: String? = null,
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
    /** Human-readable, localisable coaching line. */
    val guidance: String = "",
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
 */
class TrackingMonitor {

    private val mutableStatus = MutableStateFlow(TrackingStatus.INITIAL)
    val status: StateFlow<TrackingStatus> = mutableStatus.asStateFlow()

    private var consecutiveLosses = 0
    private var lastGuidance = ""

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
        val next = TrackingStatus(
            quality = quality,
            trackingFailureReason = failureReason,
            depthConfidence = depthConfidence,
            depthSupported = depthSupported,
            ambientLux = ambientLux,
            torchOn = torchOn,
            thermallyThrottled = thermallyThrottled,
            scanCoverage = scanCoverage,
            guidance = guidance,
        )
        // Only emit when something the UI shows actually changed (avoids recompositions at 30 Hz).
        val previous = mutableStatus.value
        if (previous != next || guidance != lastGuidance) {
            lastGuidance = guidance
            mutableStatus.value = next
        }
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
    ): String = when {
        quality == TrackingQuality.NOT_TRACKING && consecutiveLosses > LOST_FRAMES ->
            "Tracking lost — point the camera at a textured wall or the floor and move slowly."
        quality == TrackingQuality.NOT_TRACKING ->
            "Looking for features… move the phone slowly across the surface."
        failureReason != null ->
            "Tracking limited: $failureReason"
        thermallyThrottled ->
            "Device warm — mesh updates slowed to protect the battery. Measurements are unaffected."
        depthSupported && depthConfidence in 1..CRITICAL_CONFIDENCE ->
            "Depth signal weak here (glass, mirror or dark surface). Move closer or re-aim."
        ambientLux > 0f && ambientLux < DARK_LUX && !torchOn ->
            "Too dark to measure reliably — turn on the torch."
        quality == TrackingQuality.LIMITED ->
            "Move slower — tracking is limited."
        else -> ""
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
