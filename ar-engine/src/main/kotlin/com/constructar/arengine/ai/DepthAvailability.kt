/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.ai

/**
 * Which depth the app is actually measuring with - the answer a screen has to be able to give.
 *
 * Three paths can supply the millimetres a hit is resolved against, and the operator cannot tell
 * them apart by looking at the viewfinder: ARCore's depth API (a hardware or fused depth image), the
 * optional monocular model (Depth Anything V2, `BuildConfig.DEPTH_MODEL_ASSET`), and - when neither
 * is there - the detected planes and their own geometry. The last one is not a failure: a plane hit
 * is exact on the surface it lands on. What it is *not* is a depth field, so a reticle aimed at a
 * chair's edge can find nothing to measure, and an operator who does not know that reads it as the
 * app being broken.
 *
 * The decision is a pure function of three booleans (finding 221), which is why it lives here
 * instead of inside a composable: the alternative was three different screens each deciding what
 * "no depth" means.
 */
enum class DepthSourceState {
    /** The monocular model loaded and has produced at least one fused frame. Best case on paper. */
    AI_FUSED,

    /** ARCore's depth API is available; the model is absent, not bundled, or failed to load. */
    ARCORE_ONLY,

    /**
     * No depth at all: hits come from planes and feature points.
     *
     * Never hidden from the operator, because it changes where a tap can land.
     */
    PLANES_ONLY,
}

/** The state and the fact that explains it, so a screen can print one sentence without guessing. */
data class DepthStatus(
    val state: DepthSourceState,
    /** True when the APK carries the model file - i.e. the fallback *could* run on this build. */
    val modelBundled: Boolean,
) {
    /** True when the phone measures millimetres against a depth field of some kind. */
    val hasDepthField: Boolean get() = state != DepthSourceState.PLANES_ONLY
}

object DepthAvailability {

    /**
     * What the app may claim, from what it knows.
     *
     * `aiRunning` wins over `arcoreDepthSupported` on purpose: a fused frame is a *better* depth
     * field than ARCore's raw one (that is the whole point of the model), so when both work the
     * honest answer is the one that is actually in use.
     *
     * `modelBundled` does not change the state - a model that is present but has not produced a
     * frame has not helped - it only explains why [DepthSourceState.ARCORE_ONLY] is not
     * [DepthSourceState.AI_FUSED].
     */
    fun of(
        modelBundled: Boolean,
        arcoreDepthSupported: Boolean,
        aiRunning: Boolean,
    ): DepthStatus = DepthStatus(
        state = when {
            aiRunning -> DepthSourceState.AI_FUSED
            arcoreDepthSupported -> DepthSourceState.ARCORE_ONLY
            else -> DepthSourceState.PLANES_ONLY
        },
        modelBundled = modelBundled,
    )
}
