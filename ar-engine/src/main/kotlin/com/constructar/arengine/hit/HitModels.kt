/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

import com.constructar.core.math.Plane3
import com.constructar.core.math.Ray
import com.constructar.core.math.Vec3
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind

/**
 * A tracked ARCore plane reduced to what the hit cascade needs.
 *
 * Kept engine-agnostic (no `com.google.ar.core` types) so the cascade can be unit-tested on
 * the host and so the same negotiation logic can be reused for recorded datasets.
 */
data class PlaneCandidate(
    val id: String,
    /** Convex polygon in world space, vertices in order (ARCore `Plane.polygon`). */
    val polygon: List<Vec3>,
    /** Geometric normal (`Plane.centerPose` Y axis). Points out of the material. */
    val normal: Vec3,
    /** Distance from the world origin along [normal] — the plane equation constant. */
    val offset: Float,
    val center: Vec3,
    val areaSquareMeters: Float,
    val isTracking: Boolean = true,
    /** Set when ARCore merged this plane into a larger coplanar one. */
    val subsumedBy: String? = null,
    /** Horizontal (floor/ceiling) or vertical (wall) — ARCore's own classification. */
    val isVertical: Boolean = false,
) {
    val plane3: Plane3 get() = Plane3(normal, offset)

    companion object {
        fun fromPolygon(id: String, polygon: List<Vec3>, normal: Vec3, center: Vec3, isTracking: Boolean = true): PlaneCandidate? {
            if (polygon.size < 3) return null
            val n = normal.normalizedOrZero()
            if (n.isDegenerate()) return null
            return PlaneCandidate(
                id = id,
                polygon = polygon,
                normal = n,
                offset = n dot center,
                center = center,
                areaSquareMeters = com.constructar.core.math.PolygonMath.area(polygon),
                isTracking = isTracking,
                isVertical = kotlin.math.abs(n.y) < 0.6f,
            )
        }
    }
}

/**
 * Where a hit was pulled to, and by how far - the record of a snap that moved the reticle.
 *
 * The snap is silent by design ([ReticleSnap] only moves a point within its tolerance), and a
 * silent move is exactly what an operator cannot diagnose: the reticle jumps two centimetres, the
 * number changes, and nothing on the screen says whether that was the corner or the sensor. This
 * carries the *fact* of the move - which architectural feature, how many metres - so a screen can
 * say it. It is deliberately not part of [SurfaceHit.toRouteNode]: a stored node records where the
 * point is, and the protocol prints that position, not the reticle's opinion of it.
 */
data class SnapInfo(
    /** The kind of feature the point was pulled onto. */
    val kind: SnapFeatureKind,
    /** Metres between the raw hit and the feature it was moved onto. */
    val distanceM: Float,
)

/** A resolved 3D surface point, ready to become a route node. */
data class SurfaceHit(
    val point: Vec3,
    /** Unit normal, already flipped to face the operator. */
    val normal: Vec3,
    val source: HitSource,
    /** 0..1 — plane hit = 1, feature point = 0.4, etc. */
    val confidence: Float,
    /** Distance from the camera, metres. */
    val distanceMeters: Float,
    val planeId: String? = null,
    val surfaceKind: SurfaceKind = SurfaceKind.UNKNOWN,
    val label: String = "",
    /** Set when [ReticleSnap] moved this point onto an architectural corner or edge. */
    val snap: SnapInfo? = null,
) {
    /**
     * @param sessionId the AR session this point is being placed in
     *   (`RouteSession.sessionId`). It travels with the node because every later question about a
     *   *height* is a question about one coordinate system, and ARCore starts a new one per
     *   session (finding 217); a length does not care, which is why the field was missed for so
     *   long.
     */
    fun toRouteNode(
        id: String,
        anchorId: String? = null,
        sessionId: String = "",
    ): RouteNodeRecord = RouteNodeRecord(
        id = id,
        position = point,
        normal = normal,
        surface = surfaceKind,
        hitSource = source,
        anchorId = anchorId,
        confidence = confidence,
        sessionId = sessionId,
    )
}

/** Per-tier outcome, kept for the diagnostics screen and for field bug reports. */
data class TierOutcome(
    val source: HitSource,
    val considered: Boolean,
    val accepted: Boolean,
    val reason: String,
)

/** The complete resolution of one tap / reticle sample. */
data class HitResolution(
    val hit: SurfaceHit?,
    val attempts: List<TierOutcome>,
    /** Metres between the accepted point and the previous route node, when known. */
    val distanceFromPreviousMeters: Float? = null,
    /**
     * What the app decided about the floor, on this frame's planes.
     *
     * Carried on the resolution because the planes it comes from are only alive inside the hit
     * source: nothing above this layer sees a `PlaneCandidate`. The *decision* travels, not just
     * the number, because a mounting height built on an uncertain guess is exactly what this app
     * may not print (finding 218).
     */
    val floor: FloorDecision? = null,
) {
    /** The floor height alone, for the callers that only need a number. */
    val floorHeightM: Float? get() = floor?.heightM

    val accepted: Boolean get() = hit != null

    companion object {
        fun miss(attempts: List<TierOutcome>) = HitResolution(null, attempts)
    }
}

/** Everything the cascade may consider for one request. Produced by [ArHitSource]. */
data class HitRequest(
    /** Camera ray through the tapped pixel (world space, unit direction). */
    val ray: Ray,
    val viewerPosition: Vec3,
    /** Tier 1 inputs. */
    val planes: List<PlaneCandidate> = emptyList(),
    /** Tier 2: ARCore `DepthPoint` hit (sampled from the depth image by ARCore itself). */
    val depthPoint: SurfaceHit? = null,
    /** Tier 3: our own raycast into the raw depth image. */
    val directDepthHit: SurfaceHit? = null,
    /** Tier 4: ARCore feature point. */
    val featurePoint: SurfaceHit? = null,
    /** Optional previous node, used to reject snapping through a corner. */
    val previousNode: RouteNodeRecord? = null,
)

/** Tuning of the strict priority cascade required by the specification. */
data class HitCascadeConfig(
    /**
     * Tolerance applied to `isPoseInPolygon`: a tap on the very edge of a seen wall must
     * count as a wall hit rather than falling through to the noisier depth tiers.
     */
    val polygonToleranceMeters: Float = 0.03f,
    /** Ignore anything further away than this (metres). */
    val maxDistanceMeters: Float = 8f,
    /** Reject depth hits whose normal differs from the previous node by more than this. */
    val maxNormalChangeDegrees: Float = 80f,
    /** Reject a new node closer than this to the previous one (double-tap protection). */
    val minimumNodeSpacingMeters: Float = 0.02f,
    /** ARCore confidence floor for a tier-3 depth raycast hit, 0..255. */
    val minDepthConfidence: Int = 96,
    /** Allow ARCore's DepthPoint tier (device must support the Depth API). */
    val useArcDepthPoint: Boolean = true,
    /** Allow our own depth raycast (tier 3). */
    val useDirectDepth: Boolean = true,
    /** Allow the feature-point fallback (tier 4). Off = "only trust real surfaces". */
    val useFeaturePoint: Boolean = true,
    /**
     * When true, honour ARCore's own hit ordering *within* a tier and keep the nearest.
     * Always true for planes: the closest valid plane is the one the user is aiming at.
     */
    val preferNearestPlane: Boolean = true,
    /**
     * Pull accepted plane hits onto architectural edges and corners ([ReticleSnap]).
     * Off = the reticle reports the raw polygon hit; useful when a device's plane polygons
     * are too ragged for the derived edges to be trusted.
     */
    val snapToArchitecturalFeatures: Boolean = true,
)
