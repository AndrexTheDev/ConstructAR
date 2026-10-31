/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

import com.constructar.core.math.PolygonMath
import com.constructar.core.math.Vec3
import com.constructar.core.model.HitSource
import com.constructar.core.model.SurfaceKind

/**
 * The strict, four-tier surface hit cascade.
 *
 * The order is not cosmetic — each tier is *strictly* more stable than the next, and a route
 * node inherits the quality of the tier it came from:
 *
 * | # | Source                    | Typical error | Why it is trusted                      |
 * |---|---------------------------|---------------|----------------------------------------|
 * | 1 | ARCore plane + polygon     | < 1 cm        | persistent, gravity-aligned, extensible |
 * | 2 | ARCore `DepthPoint`        | 1–4 cm        | real sensor geometry, per-pixel         |
 * | 3 | direct raw-depth raycast   | 2–6 cm        | same sensor, but no temporal filtering  |
 * | 4 | ARCore feature point       | 5–15 cm       | sparse, only where texture exists       |
 *
 * Two failure modes are handled explicitly because they are what actually goes wrong on a
 * site: a plane that *looks* hit but whose polygon the ray misses (ARCore extends planes
 * optimistically — tapping the visible wall beyond the tracked region would otherwise
 * silently place the cable 30 cm behind the plaster), and a depth hit that snaps through a
 * door reveal onto the far wall.
 */
class SurfaceHitResolver(private val config: HitCascadeConfig = HitCascadeConfig()) {

    fun resolve(request: HitRequest): HitResolution {
        val attempts = ArrayList<TierOutcome>(5)

        // ── Tier 1: plane polygon ────────────────────────────────────────────────────────
        val planeOutcome = resolvePlane(request)
        attempts += planeOutcome.first
        planeOutcome.second?.let { hit ->
            return HitResolution(hit, attempts, distanceFromPrevious(hit, request))
        }

        // ── Tier 2: ARCore DepthPoint ────────────────────────────────────────────────────
        if (!config.useArcDepthPoint) {
            attempts += TierOutcome(HitSource.AR_DEPTH_POINT, considered = false, accepted = false, reason = "disabled")
        } else {
            val candidate = request.depthPoint
            val rejection = reject(candidate, request)
            attempts += TierOutcome(
                HitSource.AR_DEPTH_POINT,
                considered = true,
                accepted = rejection == null,
                reason = rejection ?: "accepted",
            )
            if (candidate != null && rejection == null) {
                return HitResolution(candidate, attempts, distanceFromPrevious(candidate, request))
            }
        }

        // ── Tier 3: direct raw-depth raycast ─────────────────────────────────────────────
        if (!config.useDirectDepth) {
            attempts += TierOutcome(HitSource.DIRECT_DEPTH_RAYCAST, considered = false, accepted = false, reason = "disabled")
        } else {
            val candidate = request.directDepthHit
            val rejection = reject(candidate, request)
            attempts += TierOutcome(
                HitSource.DIRECT_DEPTH_RAYCAST,
                considered = true,
                accepted = rejection == null,
                reason = rejection ?: "accepted",
            )
            if (candidate != null && rejection == null) {
                return HitResolution(candidate, attempts, distanceFromPrevious(candidate, request))
            }
        }

        // ── Tier 4: feature point ────────────────────────────────────────────────────────
        if (!config.useFeaturePoint) {
            attempts += TierOutcome(HitSource.FEATURE_POINT, considered = false, accepted = false, reason = "disabled")
        } else {
            val candidate = request.featurePoint
            val rejection = reject(candidate, request)
            attempts += TierOutcome(
                HitSource.FEATURE_POINT,
                considered = true,
                accepted = rejection == null,
                reason = rejection ?: "accepted",
            )
            if (candidate != null && rejection == null) {
                return HitResolution(candidate, attempts, distanceFromPrevious(candidate, request))
            }
        }

        return HitResolution.miss(attempts)
    }

    /**
     * Tier 1 evaluation. Returns a per-tier outcome plus the accepted hit.
     *
     * `Frame.hitTest` already returns only geometrically valid results, but we re-test the
     * polygon ourselves: ARCore's hit list can be dominated by a *nearer* depth point while
     * the correct plane hit sits behind it, and we must prefer the plane.
     */
    private fun resolvePlane(request: HitRequest): Pair<TierOutcome, SurfaceHit?> {
        val candidates = request.planes.filter { it.isTracking && it.subsumedBy == null }
        if (candidates.isEmpty()) {
            return TierOutcome(HitSource.PLANE_POLYGON, true, false, "no tracked planes") to null
        }

        var best: Pair<PlaneCandidate, Vec3>? = null
        var bestDistance = Float.MAX_VALUE
        var sawPolygonMiss = false
        var sawDistanceReject = false

        for (plane in candidates) {
            val t = plane.plane3.intersect(request.ray) ?: continue
            if (t <= 0f || t > config.maxDistanceMeters) {
                sawDistanceReject = true
                continue
            }
            val intersection = request.ray.pointAt(t)
            if (!PolygonMath.contains(plane.polygon, intersection, config.polygonToleranceMeters)) {
                sawPolygonMiss = true
                continue
            }
            if (!config.preferNearestPlane) {
                best = plane to intersection
                break
            }
            if (t < bestDistance) {
                bestDistance = t
                best = plane to intersection
            }
        }

        val accepted = best
        if (accepted == null) {
            val reason = when {
                sawDistanceReject -> "plane out of range"
                sawPolygonMiss -> "ray outside every tracked polygon"
                else -> "no plane intersection"
            }
            return TierOutcome(HitSource.PLANE_POLYGON, true, false, reason) to null
        }

        val (plane, point) = accepted
        // ARCore's plane normal points out of the material, so it may face away from the
        // operator; the cable must always sit on the visible side.
        val normal = flipTowards(point, plane.normal, request.viewerPosition)
        val hit = SurfaceHit(
            point = point,
            normal = normal,
            source = HitSource.PLANE_POLYGON,
            confidence = 1f,
            distanceMeters = bestDistance,
            planeId = plane.id,
            // The *geometric* normal (out of the material) classifies the surface; the
            // flipped one only orients the conduit. An UNKNOWN here cannot happen after the
            // degenerate check in PlaneCandidate.fromPolygon, but be explicit anyway.
            surfaceKind = SurfaceKind.fromNormal(plane.normal)
                .let { if (it == SurfaceKind.UNKNOWN) SurfaceKind.WALL else it },
            label = if (plane.isVertical) "Wall" else if (plane.normal.y > 0f) "Floor" else "Ceiling",
        )
        val rejection = rejectDomain(hit, request)
        if (rejection != null) {
            return TierOutcome(HitSource.PLANE_POLYGON, true, false, rejection) to null
        }
        return TierOutcome(HitSource.PLANE_POLYGON, true, true, "accepted (plane ${plane.id})") to hit
    }

    /** Common validity checks for tiers 2–4. */
    private fun reject(hit: SurfaceHit?, request: HitRequest): String? {
        if (hit == null) return "no candidate"
        if (hit.distanceMeters > config.maxDistanceMeters) return "beyond ${config.maxDistanceMeters} m"
        return rejectDomain(hit, request)
    }

    /** Checks that need the previous node: normal continuity and minimum spacing. */
    private fun rejectDomain(hit: SurfaceHit, request: HitRequest): String? {
        val previous = request.previousNode ?: return null
        val spacing = hit.point.distanceTo(previous.position)
        if (spacing < config.minimumNodeSpacingMeters) return "too close to the previous node"
        if (spacing > 0.0f) {
            val angle = java.lang.Math.toDegrees(previous.normal.angleTo(hit.normal).toDouble()).toFloat()
            if (angle > config.maxNormalChangeDegrees) {
                return "normal changes by ${angle.toInt()}° — likely snapped through a corner"
            }
        }
        return null
    }

    private fun distanceFromPrevious(hit: SurfaceHit, request: HitRequest): Float? =
        request.previousNode?.let { hit.point.distanceTo(it.position) }

    companion object {
        /** Flips [normal] so that it points towards [viewer]. */
        fun flipTowards(point: Vec3, normal: Vec3, viewer: Vec3): Vec3 {
            val n = normal.normalizedOrZero()
            if (n.isDegenerate()) return Vec3.Y_AXIS
            return if ((n dot (viewer - point)) < 0f) -n else n
        }
    }
}
