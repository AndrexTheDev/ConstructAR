/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.slam

import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

/**
 * When a tracked frame is worth keeping: the policy, and nothing else.
 *
 * A keyframe is expensive - a pose graph node, a set of feature descriptors, possibly a bundle -
 * and the two ways to get it wrong are symmetrical. Keep too few and the graph has no geometry to
 * close a loop against, so a drift of half a metre stays a drift of half a metre. Keep too many
 * and the graph is dominated by frames that are a centimetre and a tenth of a degree apart, whose
 * relative measurement is mostly ARCore's own pose noise; the optimiser then spends its whole
 * budget reconciling noise and the drift gets worse, not better. The old rule was "every tenth
 * frame", which is wrong in both directions at once: at walking pace it keeps frames 20 cm apart,
 * standing still it keeps frames that differ by nothing, and a slow turn in a corner keeps none
 * at all even though the view has rotated 90 degrees.
 *
 * So the rule here is in metres and degrees, not frames:
 *
 *  * **Motion, either kind.** Translation of at least [KeyframeConfig.minTranslationMetres] **or**
 *    rotation of at least [KeyframeConfig.minRotationDegrees]. Rotation earns its own branch
 *    because a turn in a narrow corridor gives almost no translation while changing almost
 *    everything about what the camera sees, and translation earns its own because the pure
 *    forward walk along a wall is where the depth-free geometry is weakest and the frames differ
 *    least.
 *  * **Evidence.** The pose is only worth keeping if the tracker says it is tracked and the frame
 *    had enough features to place it - [KeyframeConfig.minFeaturePoints]. A keyframe taken in a
 *    white corridor at 8 features is a node with a wrong position; the graph will happily build
 *    on it and the loop closure will inherit the error as if it had been measured.
 *  * **Two gates, in this order.** Jitter first (nothing moved at all - a phone on a table, where
 *    per-frame pose noise is millimetres and a naive timer would keep a keyframe every 200 ms),
 *    then rate ([KeyframeConfig.minIntervalMillis] - a fast pan can cross 0.5 m between two
 *    consecutive frames and the two are then so close in time that their baseline is dominated by
 *    motion blur). The order makes each rejection say *which* of the two happened, which is the
 *    difference between "walk slower" and "make a real move".
 *
 * Rotation is measured the only way a quaternion allows it: `2·acos|q₁·q₂|`, with the absolute
 * value because `q` and `-q` are the same rotation and the dot product's sign is an artefact of
 * how the pose was represented. Without it a single sign flip would read as 358 degrees of
 * rotation and be "accepted" as the biggest keyframe of the session.
 *
 * Pure `:core`: no `android.*`, no clock, no thread. The timestamp is given, the decision is
 * returned, and [offer] is the only thing that remembers anything - so the whole policy is
 * JVM-testable, and the caller (the AR engine's frame loop) keeps its own thread confinement.
 */
data class KeyframeConfig(
    /** Minimum translation between two keyframes, metres. */
    val minTranslationMetres: Float = DEFAULT_MIN_TRANSLATION_METRES,
    /** Minimum rotation between two keyframes, degrees. */
    val minRotationDegrees: Float = DEFAULT_MIN_ROTATION_DEGREES,
    /** Minimum wall-clock time between two keyframes, milliseconds. */
    val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    /**
     * Motion floor below which two frames are the same frame, metres. Small on purpose: it is the
     * line between "the tracker's pose noise moved a little" and "the operator moved a little",
     * and it is what makes a phone left on a wall not produce keyframes through the time gate.
     */
    val minMotionMetres: Float = DEFAULT_MIN_MOTION_METRES,
    /** Minimum tracked feature points for a frame to carry usable geometry. */
    val minFeaturePoints: Int = DEFAULT_MIN_FEATURE_POINTS,
) {
    init {
        require(minTranslationMetres > 0f && minTranslationMetres.isFinite()) {
            "minTranslationMetres must be positive and finite, was $minTranslationMetres"
        }
        require(minRotationDegrees > 0f && minRotationDegrees.isFinite()) {
            "minRotationDegrees must be positive and finite, was $minRotationDegrees"
        }
        require(minIntervalMillis >= 0L) {
            "minIntervalMillis must not be negative, was $minIntervalMillis"
        }
        require(minMotionMetres >= 0f && minMotionMetres.isFinite()) {
            "minMotionMetres must be finite and not negative, was $minMotionMetres"
        }
        require(minFeaturePoints >= 0) {
            "minFeaturePoints must not be negative, was $minFeaturePoints"
        }
        // A motion floor above the translation threshold would make the translation branch
        // unreachable, and a rotation floor of zero would accept every frame: both are
        // configuration mistakes that would only show up as a silent quality loss.
        require(minMotionMetres < minTranslationMetres) {
            "minMotionMetres ($minMotionMetres) must stay below minTranslationMetres ($minTranslationMetres)"
        }
    }

    companion object {
        /** 0.5 m: at walking pace roughly every half step, far enough for a usable baseline. */
        const val DEFAULT_MIN_TRANSLATION_METRES: Float = 0.5f
        /** 15 degrees: a turn that changes most of the view without crossing a room. */
        const val DEFAULT_MIN_ROTATION_DEGREES: Float = 15f
        /** 200 ms: the floor on how fast keyframes may follow each other. */
        const val DEFAULT_MIN_INTERVAL_MILLIS: Long = 200L
        /** 5 cm: the jitter floor - above ARCore's pose noise, below any deliberate move. */
        const val DEFAULT_MIN_MOTION_METRES: Float = 0.05f
        /** 30 features: below this the pose is a guess with a timestamp. */
        const val DEFAULT_MIN_FEATURE_POINTS: Int = 30
    }
}

/**
 * One tracked frame, as the engine sees it. Position and rotation are already in the session
 * frame - this policy compares them, it does not interpret them.
 */
data class KeyframeCandidate(
    val position: Vec3,
    val rotation: Quat,
    val timestampMillis: Long,
    val trackingValid: Boolean,
    val featurePoints: Int,
)

/** Why a frame was or was not kept. Rejections are values, never exceptions: they are counted. */
enum class KeyframeReason {
    /** No keyframe yet in this session - the first usable frame anchors the graph. */
    FIRST,

    /** Translation alone reached the threshold. */
    TRANSLATION,

    /** Rotation alone reached the threshold - a turn in place still changes the view. */
    ROTATION,

    /** The tracker is not in a valid state; nothing about this frame may be trusted. */
    TRACKING_INVALID,

    /** Too few tracked features for the pose to carry geometry. */
    TOO_FEW_FEATURES,

    /** Nothing moved beyond pose noise - the phone is resting, whatever the timer says. */
    TOO_CLOSE,

    /** Motion is real but arrived faster than the interval allows - motion blur, no baseline. */
    TOO_SOON,
    ;

    val accepted: Boolean get() = this == FIRST || this == TRANSLATION || this == ROTATION
}

/**
 * The decision, with the two quantities it was made from. The numbers are carried so that a
 * caller can log "0.12 m, 3.4 degrees, kept=false" and see immediately which gate fired; a bare
 * boolean would make a keyframe-rate complaint impossible to diagnose from a bug report.
 */
data class KeyframeDecision(
    val reason: KeyframeReason,
    val translationMetres: Float,
    val rotationDegrees: Float,
    val elapsedMillis: Long,
) {
    val accepted: Boolean get() = reason.accepted
}

/**
 * The stateful wrapper: remembers the last **accepted** keyframe and decides the next frame.
 *
 * Confined to one thread by its caller (the engine's frame loop); it holds no locks and no clock.
 * A rejected frame does not move the reference point - that is the whole point of hysteresis,
 * since updating it on every offered frame would turn the 0.5 m rule into "0.5 m from where the
 * phone was 16 ms ago", which at walking pace is never reached.
 */
class KeyframeSelector(val config: KeyframeConfig = KeyframeConfig()) {

    private var last: KeyframeCandidate? = null

    /** Read-only view of the reference keyframe, for tests and for the debug HUD. */
    val lastKeyframe: KeyframeCandidate? get() = last

    /** The number of frames kept so far; rejections are not counted here. */
    var acceptedCount: Int = 0
        private set

    /**
     * Decide [candidate] against the reference keyframe, and adopt it if it is kept.
     *
     * The comparison always happens against the last *accepted* keyframe, so the motion measured
     * is the motion since the last node in the graph - which is what the optimiser will see.
     */
    fun offer(candidate: KeyframeCandidate): KeyframeDecision {
        val decision = decide(last, candidate, config)
        if (decision.accepted) {
            last = candidate
            acceptedCount += 1
        }
        return decision
    }

    /** Forget the reference frame - a new session, a relocalisation, or a jumped pose. */
    fun reset() {
        last = null
        acceptedCount = 0
    }

    companion object {
        /**
         * The pure rule, with no memory at all: given the last kept frame (or `null`) and the
         * candidate, say whether it is kept and why. [offer] is this plus the one assignment.
         */
        fun decide(
            last: KeyframeCandidate?,
            candidate: KeyframeCandidate,
            config: KeyframeConfig = KeyframeConfig(),
        ): KeyframeDecision {
            if (last == null) {
                // Without a reference there is no motion to measure - but the evidence gates
                // still apply: a first keyframe from an untracked or featureless frame would
                // anchor the whole graph on a pose nobody measured.
                val reason = when {
                    !candidate.trackingValid -> KeyframeReason.TRACKING_INVALID
                    candidate.featurePoints < config.minFeaturePoints -> KeyframeReason.TOO_FEW_FEATURES
                    else -> KeyframeReason.FIRST
                }
                return KeyframeDecision(reason, 0f, 0f, 0L)
            }

            val translation = last.position.distanceTo(candidate.position)
            val rotation = rotationDegrees(last.rotation, candidate.rotation)
            val elapsed = candidate.timestampMillis - last.timestampMillis

            val reason = when {
                !candidate.trackingValid -> KeyframeReason.TRACKING_INVALID
                candidate.featurePoints < config.minFeaturePoints -> KeyframeReason.TOO_FEW_FEATURES
                translation < config.minMotionMetres && rotation < config.minRotationDegrees ->
                    KeyframeReason.TOO_CLOSE
                elapsed < config.minIntervalMillis -> KeyframeReason.TOO_SOON
                translation >= config.minTranslationMetres -> KeyframeReason.TRANSLATION
                rotation >= config.minRotationDegrees -> KeyframeReason.ROTATION
                else -> KeyframeReason.TOO_CLOSE
            }
            return KeyframeDecision(reason, translation, rotation, elapsed)
        }

        /**
         * The angle between two orientations, in degrees: `2·acos|q₁·q₂|`.
         *
         * The absolute value is not a shortcut. `q` and `-q` describe the same rotation, so the
         * dot product of two equal placements may be `-1`; without the `abs` that reads as a
         * 358-degree turn. The clamp guards the `acos` domain against the dot product of two
         * normalised-but-rounded quaternions landing a hair above 1.
         */
        fun rotationDegrees(a: Quat, b: Quat): Float {
            // The dot product is written out rather than added to `Quat`: it is two lines, it is
            // the only place in the app that needs it, and `Quat` is on the hot path for the whole
            // renderer. Both sides are assumed unit-length (the caller's poses are), which is what
            // makes the dot product equal to `cos(θ/2)`.
            val dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            val angle = 2.0 * acos(min(1.0, abs(dot.toDouble())))
            return Math.toDegrees(angle).toFloat()
        }
    }
}
