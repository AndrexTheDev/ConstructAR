/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

import com.constructar.core.math.Ray
import com.constructar.core.model.HitConfidence
import com.constructar.core.model.HitSource
import kotlin.math.abs
import kotlin.math.max

/**
 * The confidence model shared by every tier of [SurfaceHitResolver].
 *
 * `cosTheta` is the incidence between the incoming viewing ray and the outward surface normal.
 * A frontal plane observation therefore keeps its type weight, while a grazing or noisy depth
 * observation falls below the one warning threshold used by AR, the material list and exports.
 */
object HitTestCascadeEngine {
    const val WARNING_THRESHOLD: Float = HitConfidence.TRUSTED

    fun confidence(
        source: HitSource,
        cosTheta: Float,
        sigmaDepthMeters: Float,
        sigmaMaxMeters: Float,
    ): Float {
        if (!cosTheta.isFinite() || !sigmaDepthMeters.isFinite() || !sigmaMaxMeters.isFinite()) return 0f
        if (sigmaMaxMeters <= 0f) return 0f
        val angle = abs(cosTheta).coerceIn(0f, 1f)
        val depthFactor = (1f - max(0f, sigmaDepthMeters) / sigmaMaxMeters).coerceIn(0f, 1f)
        return (source.reliability * angle * depthFactor).coerceIn(0f, 1f)
    }

    fun score(hit: SurfaceHit, ray: Ray, config: HitCascadeConfig): SurfaceHit {
        val incidence = -(ray.direction.normalizedOrZero() dot hit.normal.normalizedOrZero())
        val sigma = hit.depthSigmaMeters ?: defaultSigma(hit.source)
        return hit.copy(
            confidence = confidence(hit.source, incidence, sigma, config.maxDepthSigmaMeters),
            depthSigmaMeters = sigma,
        )
    }

    fun needsWarning(confidence: Float): Boolean = !confidence.isFinite() || confidence < WARNING_THRESHOLD

    /** Conservative sensor priors used when ARCore supplies no metric uncertainty of its own. */
    fun defaultSigma(source: HitSource): Float = when (source) {
        HitSource.PLANE_POLYGON -> 0f
        HitSource.AR_DEPTH_POINT -> 0.010f
        HitSource.DIRECT_DEPTH_RAYCAST -> 0.025f
        HitSource.FEATURE_POINT -> 0.040f
        HitSource.MANUAL -> 0.050f
    }
}
