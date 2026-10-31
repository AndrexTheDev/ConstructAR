/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

import android.opengl.Matrix
import android.util.Log
import com.constructar.arengine.depth.DepthFrameSnapshot
import com.constructar.arengine.depth.DepthImageMath
import com.constructar.arengine.route.positionVec
import com.constructar.arengine.route.toCoreQuat
import com.constructar.core.math.Ray
import com.constructar.core.math.Vec3
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

/**
 * Translates an ARCore [Frame] + a screen tap into a [HitRequest] — the raw material of the
 * priority cascade.
 *
 * ## Screen → world ray
 *
 * ARCore exposes the camera's view and projection matrices for the *current display
 * orientation*, so the correct ray comes from inverting `P · V` and unprojecting two clip
 * points. Using the depth intrinsics instead would be wrong in landscape (the depth image is
 * never rotated), which is a classic source of "the cable lands 20 cm to the left" bugs.
 *
 * ## Which ARCore API feeds which tier
 *
 * | Tier | Source | ARCore API |
 * |------|--------|-----------|
 * | 1 | [Plane] polygon | `session.getAllTrackables(Plane::class.java)` + `Plane.polygon` |
 * | 2 | DepthPoint | `frame.hitTest(x, y)` → `HitResult.Type.DEPTH_POINT` |
 * | 3 | Direct depth raycast | our own march through `acquireDepthImage16Bits()` |
 * | 4 | Point | `frame.hitTest(x, y)` → `HitResult.Type.POINT` |
 *
 * (ARCore has no `hitTestDepth`; `hitTest` returns depth hits when the Depth API is enabled,
 * and tier 3 exists precisely to avoid depending on that behaviour on quirky devices.)
 */
class ArHitSource(
    private val session: Session,
    private val cascade: HitCascadeConfig = HitCascadeConfig(),
) {
    /** Last computed plane set — recomputed when a tap happens, not every frame. */
    private var cachedPlanes: List<PlaneCandidate> = emptyList()
    private var cachedPlaneTimestamp: Long = -1

    /**
     * Builds the request for a tap at screen pixel ([x], [y]) in a viewport of
     * ([viewWidth] × [viewHeight]).
     */
    fun buildRequest(
        frame: Frame,
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        snapshot: DepthFrameSnapshot?,
        previousNode: RouteNodeRecord? = null,
    ): HitRequest {
        val ray = screenRay(frame, x, y, viewWidth, viewHeight)
        val planes = planes(frame.timestamp)
        val hits = runCatching { frame.hitTest(x, y) }.getOrElse {
            Log.w(TAG, "hitTest failed", it)
            emptyList()
        }

        // ARCore's `HitResult` carries no type discriminator: the *trackable* it hit is the
        // type (Plane / Point / DepthPoint / InstantPlacementPoint). `getType()` does not exist
        // on the ARCore API - SceneView's own hit helpers switch on `hitResult.trackable` too.
        val depthPoint = hits.firstOrNull { it.trackable is DepthPoint }?.toSurfaceHit(frame, snapshot)
        val featurePoint = hits.firstOrNull { it.trackable is Point }?.toSurfaceHit(frame, snapshot)

        // Tier 3 needs a metric depth frame; the TSDF pipeline keeps the latest one alive.
        val directDepth = if (snapshot != null) {
            val depthPixel = screenToDepthPixel(frame, x, y, viewWidth, viewHeight, snapshot)
            DepthImageMath.raycast(
                snapshot = snapshot,
                intrinsics = snapshot.intrinsics,
                cameraToWorld = snapshot.cameraToWorld,
                screenX = depthPixel.first,
                screenY = depthPixel.second,
                minConfidence = cascade.minDepthConfidence,
            )?.let { hit ->
                SurfaceHit(
                    point = hit.point,
                    normal = hit.normal,
                    source = HitSource.DIRECT_DEPTH_RAYCAST,
                    confidence = hit.confidence,
                    distanceMeters = hit.distanceMeters,
                    surfaceKind = SurfaceKind.fromNormal(hit.normal),
                    label = "Depth surface",
                )
            }
        } else null

        return HitRequest(
            ray = ray ?: Ray(Vec3.ZERO, Vec3(0f, 0f, -1f)),
            viewerPosition = frame.camera.pose.positionVec(),
            planes = planes,
            depthPoint = depthPoint,
            directDepthHit = directDepth,
            featurePoint = featurePoint,
            previousNode = previousNode,
        )
    }

    /** Resolves the tap end-to-end. */
    fun resolve(
        frame: Frame,
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        snapshot: DepthFrameSnapshot?,
        previousNode: RouteNodeRecord? = null,
    ): HitResolution = SurfaceHitResolver(cascade)
        .resolve(buildRequest(frame, x, y, viewWidth, viewHeight, snapshot, previousNode))

    // ── Planes ──────────────────────────────────────────────────────────────────────────

    /** Cached for one frame only: plane polygon extraction allocates and is not per-frame. */
    private fun planes(timestamp: Long): List<PlaneCandidate> {
        if (timestamp == cachedPlaneTimestamp) return cachedPlanes
        cachedPlaneTimestamp = timestamp
        val all = runCatching { session.getAllTrackables(Plane::class.java) }
            .getOrElse {
                Log.w(TAG, "getAllTrackables(Plane) failed", it)
                emptyList()
            }
        val out = ArrayList<PlaneCandidate>(all.size)
        for (plane in all) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            val polygonBuffer = try {
                plane.polygon
            } catch (error: Throwable) {
                continue
            }
            if (polygonBuffer == null || polygonBuffer.remaining() < 9) continue
            polygonBuffer.rewind()
            val vertices = ArrayList<Vec3>(polygonBuffer.remaining() / 3)
            while (polygonBuffer.remaining() >= 3) {
                vertices += Vec3(polygonBuffer.get(), polygonBuffer.get(), polygonBuffer.get())
            }
            val centerPose = plane.centerPose
            // ARCore's plane normal is the pose's +Y axis.
            val normal = centerPose.toCoreQuat().rotate(Vec3.Y_AXIS)
            val candidate = PlaneCandidate.fromPolygon(
                id = plane.hashCode().toString(),
                polygon = vertices,
                normal = normal,
                center = centerPose.positionVec(),
                isTracking = true,
            ) ?: continue
            out += candidate.copy(subsumedBy = plane.subsumedBy?.hashCode()?.toString())
        }
        cachedPlanes = out
        return out
    }

    // ── Screen ↔ camera ─────────────────────────────────────────────────────────────────

    /**
     * World-space ray through the screen pixel, computed from the inverse view-projection.
     * Returns null when the matrices are degenerate (first frame).
     */
    private fun screenRay(frame: Frame, x: Float, y: Float, viewWidth: Int, viewHeight: Int): Ray? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val projection = FloatArray(16)
        val view = FloatArray(16)
        frame.camera.getProjectionMatrix(projection, 0, NEAR_PLANE, FAR_PLANE)
        frame.camera.getViewMatrix(view, 0)
        val viewProjection = FloatArray(16)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        val inverse = FloatArray(16)
        if (!Matrix.invertM(inverse, 0, viewProjection, 0)) return null

        val ndcX = 2f * x / viewWidth - 1f
        val ndcY = 1f - 2f * y / viewHeight

        val near = unproject(inverse, ndcX, ndcY, -1f) ?: return null
        val far = unproject(inverse, ndcX, ndcY, 1f) ?: return null
        val direction = (far - near).normalizedOrZero()
        if (direction.isDegenerate()) return null
        val origin = frame.camera.pose.positionVec()
        return Ray(origin, direction)
    }

    private fun unproject(inverseViewProjection: FloatArray, ndcX: Float, ndcY: Float, ndcZ: Float): Vec3? {
        val m = inverseViewProjection
        val x = m[0] * ndcX + m[4] * ndcY + m[8] * ndcZ + m[12]
        val y = m[1] * ndcX + m[5] * ndcY + m[9] * ndcZ + m[13]
        val z = m[2] * ndcX + m[6] * ndcY + m[10] * ndcZ + m[14]
        val w = m[3] * ndcX + m[7] * ndcY + m[11] * ndcZ + m[15]
        if (kotlin.math.abs(w) < 1e-6f) return null
        return Vec3(x / w, y / w, z / w)
    }

    /**
     * Maps a *screen* pixel to a *depth-image* pixel.
     *
     * The depth image is in sensor space (typically landscape), while the screen follows the
     * display rotation. Projecting the screen ray into the depth camera and dividing out the
     * intrinsics is rotation-agnostic — the only formulation that stays correct in both
     * portrait and landscape.
     */
    private fun screenToDepthPixel(
        frame: Frame,
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        snapshot: DepthFrameSnapshot,
    ): Pair<Float, Float> {
        val ray = screenRay(frame, x, y, viewWidth, viewHeight) ?: return 0f to 0f
        val cameraToWorld = snapshot.cameraToWorld
        val origin = Vec3(cameraToWorld[12], cameraToWorld[13], cameraToWorld[14])
        val localOrigin = rotateInverse(cameraToWorld, ray.origin - origin)
        val localDirection = rotateInverse(cameraToWorld, ray.direction)
        if (localDirection.z >= -1e-4f) return 0f to 0f // pointing behind the depth camera
        // Intersect with the plane one metre in front of the depth camera (z = −1) and
        // project: px = fx·X + cx, py = fy·(−Y) + cy (image y grows downwards).
        val t = (-1f - localOrigin.z) / localDirection.z
        if (t <= 0f) return 0f to 0f
        val px = localOrigin.x + localDirection.x * t
        val py = localOrigin.y + localDirection.y * t
        return (snapshot.intrinsics.fx * px + snapshot.intrinsics.cx) to
            (-snapshot.intrinsics.fy * py + snapshot.intrinsics.cy)
    }

    private fun rotateInverse(cameraToWorld: FloatArray, v: Vec3): Vec3 = Vec3(
        cameraToWorld[0] * v.x + cameraToWorld[1] * v.y + cameraToWorld[2] * v.z,
        cameraToWorld[4] * v.x + cameraToWorld[5] * v.y + cameraToWorld[6] * v.z,
        cameraToWorld[8] * v.x + cameraToWorld[9] * v.y + cameraToWorld[10] * v.z,
    )

    // ── HitResult conversion ────────────────────────────────────────────────────────────

    private fun HitResult.toSurfaceHit(frame: Frame, snapshot: DepthFrameSnapshot?): SurfaceHit {
        val point = hitPose.positionVec()
        // Depth/point hits carry no reliable orientation: derive the normal from the depth
        // neighbourhood when we have a metric frame, otherwise face the camera.
        val normal = runCatching {
            if (snapshot != null) {
                val pixel = screenToDepthPixelNearest(frame, point, snapshot)
                DepthImageMath.normalAt(snapshot, snapshot.intrinsics, snapshot.cameraToWorld, pixel.first, pixel.second)
            } else {
                null
            }
        }.getOrNull() ?: SurfaceHitResolver.flipTowards(point, Vec3(0f, 0f, 1f), frame.camera.pose.positionVec())

        val source = if (trackable is DepthPoint) HitSource.AR_DEPTH_POINT else HitSource.FEATURE_POINT
        return SurfaceHit(
            point = point,
            normal = normal,
            source = source,
            confidence = source.reliability,
            distanceMeters = frame.camera.pose.positionVec().distanceTo(point),
            surfaceKind = SurfaceKind.fromNormal(normal),
            label = if (source == HitSource.AR_DEPTH_POINT) "Depth point" else "Feature point",
        )
    }

    /** Projects a world point into the depth image (for normal estimation). */
    private fun screenToDepthPixelNearest(frame: Frame, worldPoint: Vec3, snapshot: DepthFrameSnapshot): Pair<Int, Int> {
        val cameraToWorld = snapshot.cameraToWorld
        val origin = Vec3(cameraToWorld[12], cameraToWorld[13], cameraToWorld[14])
        val local = rotateInverse(cameraToWorld, worldPoint - origin)
        val depth = -local.z
        if (depth <= 1e-3f) return 0 to 0
        val px = snapshot.intrinsics.fx * local.x / depth + snapshot.intrinsics.cx
        val py = -snapshot.intrinsics.fy * local.y / depth + snapshot.intrinsics.cy
        return px.toInt().coerceIn(0, snapshot.width - 1) to py.toInt().coerceIn(0, snapshot.height - 1)
    }

    companion object {
        private const val TAG = "ArHitSource"
        private const val NEAR_PLANE = 0.05f
        private const val FAR_PLANE = 100f
    }
}

