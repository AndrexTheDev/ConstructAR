/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

import com.constructar.core.model.HitSource

/**
 * Applies [ArchitecturalSnap] to the output of the hit cascade.
 *
 * The cascade finds *which surface* the reticle is on; this step decides *where on it* the
 * point belongs. A route node that sits 3 cm short of the wall corner makes the exported
 * floor plan look like the operator missed the corner, and the 2-point wall drawing then
 * carries that error into every stud on the wall.
 *
 * ## What is snapped, and what is deliberately not
 *
 * Only [HitSource.PLANE_POLYGON] hits are considered. Depth raycasts, depth points and
 * feature points carry no plane geometry of their own, so there is no edge or corner they
 * could legitimately be pulled onto — snapping them would invent precision that ARCore
 * never measured.
 *
 * The accepted [SurfaceHit.normal] is kept as the aimed plane's normal. At a corner the
 * perpendicular wall has an equally valid normal, but the tube being drawn belongs to the
 * wall the operator aimed at; flipping the normal there would silently move the line to the
 * neighbouring surface.
 *
 * The snap distance is bounded by [SnapOptions.toleranceM], so the returned point never
 * moves further than the reticle's own visual radius, and the source, confidence, plane id
 * and distance stay exactly as the cascade reported them.
 */
object ReticleSnap {

    /**
     * Returns [resolution] unchanged unless it is an accepted plane-polygon hit within
     * [SnapOptions.toleranceM] of an architectural edge or corner.
     */
    fun snap(
        resolution: HitResolution,
        planes: List<PlaneCandidate>,
        options: SnapOptions = SnapOptions(),
        enabled: Boolean = true,
    ): HitResolution {
        if (!enabled) return resolution
        val hit = resolution.hit ?: return resolution
        if (hit.source != HitSource.PLANE_POLYGON) return resolution
        if (planes.isEmpty()) return resolution

        val features = ArchitecturalSnap.features(planes, options)
        if (features.isEmpty()) return resolution

        val snap = ArchitecturalSnap.snapToFeatures(hit.point, features, options) ?: return resolution
        // `feature` is non-null exactly when the point moved; the distance is then always known
        // (`bestDistance` decided the move), so the `?: 0f` is a formality the compiler asks for.
        val feature = snap.feature ?: return resolution
        return resolution.copy(
            hit = hit.copy(
                point = snap.point,
                snap = SnapInfo(kind = feature.kind, distanceM = snap.distanceM ?: 0f),
            ),
        )
    }
}
