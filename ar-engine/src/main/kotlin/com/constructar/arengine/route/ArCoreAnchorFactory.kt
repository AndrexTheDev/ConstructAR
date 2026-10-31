/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.route

import android.util.Log
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

/**
 * Binds route nodes to real ARCore [Anchor]s.
 *
 * Each node's anchor is placed *on the surface* with its +Y axis along the surface normal, so
 * the anchor's own frame is meaningful for downstream consumers (drill direction, clip
 * orientation) and not just a translation. ARCore then refines that pose as it observes more
 * of the scene — which is why [AnchorHandle.position] is read every frame instead of caching
 * the tap position.
 *
 * Anchors are hosted by the session; they die with it. After a relocalisation (`session.pause`
 * / `resume`, or a tracking-loss recovery) [rehost] recreates them from the persisted node
 * positions, so a route survives a session hiccup without the operator losing the run.
 */
class ArCoreAnchorFactory(session: Session) : AnchorFactory {

    @Volatile
    private var session: Session? = session
    private val handles = HashMap<Anchor, ArCoreAnchorHandle>(16)

    fun bind(newSession: Session) {
        session = newSession
    }

    override fun create(position: Vec3, normal: Vec3): AnchorHandle? {
        val activeSession = session ?: return null
        return try {
            val rotation = RouteSession.quaternionForNormal(normal).toXyzw()
            val pose = Pose(
                floatArrayOf(position.x, position.y, position.z),
                rotation,
            )
            val anchor = activeSession.createAnchor(pose)
            ArCoreAnchorHandle(anchor).also { handles[anchor] = it }
        } catch (error: Throwable) {
            // createAnchor throws on a full anchor budget or a lost session; the caller
            // reports "could not pin that point" instead of losing the whole run.
            Log.w(TAG, "anchor creation failed", error)
            null
        }
    }

    /** Recreates the anchors of [nodes] (after a session restart) and returns the new ids. */
    fun rehost(nodes: List<com.constructar.core.model.RouteNodeRecord>): List<Pair<String, String>> {
        val remapped = ArrayList<Pair<String, String>>(nodes.size)
        for (node in nodes) {
            val created = create(node.position, node.normal) ?: continue
            remapped += node.id to created.id
        }
        return remapped
    }

    fun detachAll() {
        handles.values.forEach { it.detach() }
        handles.clear()
    }

    private class ArCoreAnchorHandle(private val anchor: Anchor) : AnchorHandle {
        override val id: String = anchor.hashCode().toString()

        override val position: Vec3
            get() = anchor.pose.position()

        override val rotation: Quat
            get() = anchor.pose.rotationQuaternion().let { Quat(it[3], it[0], it[1], it[2]) }

        override val tracking: Boolean
            get() = anchor.trackingState == TrackingState.TRACKING

        override fun detach() = runCatching { anchor.detach() }.getOrNull()
    }

    companion object {
        private const val TAG = "ArCoreAnchorFactory"
    }
}

/** ARCore `Pose.position()` → :core [Vec3]. */
private fun Pose.position(): Vec3 {
    val t = translation
    return Vec3(t[0], t[1], t[2])
}

/** ARCore `Pose` quaternion `[x, y, z, w]`. */
private fun Pose.rotationQuaternion(): FloatArray {
    val q = FloatArray(4)
    getRotationQuaternion(q, 0)
    return q
}
