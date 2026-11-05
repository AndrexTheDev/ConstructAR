/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.hit

import android.util.Log
import com.constructar.arengine.depth.DepthFrameSnapshot
import com.constructar.core.model.RouteNodeRecord
import com.google.ar.core.Frame
import com.google.ar.core.Session
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe bridge between UI taps and ARCore frames.
 *
 * A tap arrives on the main thread, but hit-testing needs a `Frame` that ARCore only
 * guarantees on the render thread. Calling `frame.hitTest` later from the main thread works
 * in practice until it doesn't — the frame may already have been recycled by `update()`,
 * which produces exactly the "sometimes my tap registers 30 cm off" reports that are
 * impossible to reproduce.
 *
 * The protocol implemented here:
 *  1. UI calls [requestTap] — it only records the pixel and returns immediately.
 *  2. The render thread calls [onFrameEnter] with the *current* frame; if a tap is pending it
 *     is resolved there and published through [results].
 *  3. The UI collects [results] and mutates the route session on the main thread (anchor
 *     creation, state changes), where ARCore's session calls belong.
 *
 * Reticle hits are sampled at a lower rate ([reticleIntervalMs]) because they only drive the
 * preview tube; 12 Hz is indistinguishable from continuous while costing a third of the work.
 */
class ArInteraction(
    private val session: Session,
    cascade: HitCascadeConfig = HitCascadeConfig(),
    private val reticleIntervalMs: Long = 80L,
) {
    private val resolver = SurfaceHitResolver(cascade)
    private val hitSource = ArHitSource(session, cascade)

    private val pendingTap = AtomicReference<ScreenTap?>(null)

    private val mutableResults = MutableStateFlow<HitResolution?>(null)
    val results: StateFlow<HitResolution?> = mutableResults.asStateFlow()

    private val mutableReticle = MutableStateFlow<SurfaceHit?>(null)
    val reticle: StateFlow<SurfaceHit?> = mutableReticle.asStateFlow()

    /** Last depth snapshot, published by the TSDF pipeline (render thread → here). */
    @Volatile
    var latestSnapshot: DepthFrameSnapshot? = null

    /** Where the reticle should sample, in screen pixels; null hides the reticle. */
    @Volatile
    var reticlePoint: Pair<Float, Float>? = null

    /**
     * The node the hit cascade validates a candidate against - written on the **main** thread
     * (`applyPendingHit`, `undo`, `finish`), read on the **render** thread (`onFrameEnter`).
     *
     * `@Volatile` because of that, and because a stale read has an effect the operator can see:
     * the cascade's "too close to the previous node" rejection and its normal-continuity check
     * would answer about a point the chain no longer ends at, so the reticle falls to a weaker
     * tier (a lower-confidence preview) or accepts a hit the committed node should have refused.
     * The two fields above this one were volatile from the start; this one was not, which is how
     * the omission is visible (finding 118).
     */
    @Volatile
    var previousNode: RouteNodeRecord? = null

    /**
     * Invoked on the render thread as soon as a tap has been resolved. The app funnels this
     * into a `MutableStateFlow` and applies it on the main thread (anchor creation must not
     * happen here).
     *
     * `@Volatile` for the same reason as [previousNode]: assigned once on the main thread when
     * the session is created, called from the render thread on every tap.
     */
    @Volatile
    var onTapResolved: ((HitResolution) -> Unit)? = null

    /**
     * Invoked on the render thread with every resolved **floor**, tap or reticle.
     *
     * A tap and the reticle run the same cascade, and the floor decision is computed on both paths
     * and was thrown away on one: the app learned which plane is the floor only when the operator
     * tapped, so walking into a room, pointing at the floor and waiting recorded nothing - and the
     * wall screen's sentence ("walk the room once and the zones appear") was an instruction that
     * did not work. It is the reticle that looks at the floor all the time; this is where its
     * answer leaves the hit source (finding 226).
     *
     * `@Volatile` for the same reason as [onTapResolved]: assigned once on the main thread,
     * called from the render thread.
     */
    @Volatile
    var onFloorResolved: ((FloorDecision) -> Unit)? = null

    private var lastReticleMillis = 0L

    private data class ScreenTap(val x: Float, val y: Float, val width: Int, val height: Int)

    /** Called from the UI thread on a tap (pixels relative to the AR view). */
    fun requestTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        pendingTap.set(ScreenTap(x, y, viewWidth, viewHeight))
    }

    /**
     * Called from the render thread once per frame. Resolves a pending tap first (a tap must
     * never wait behind the reticle), then refreshes the reticle sample if due.
     */
    fun onFrameEnter(frame: Frame, viewWidth: Int, viewHeight: Int, nowMillis: Long) {
        val snapshot = latestSnapshot
        val tap = pendingTap.getAndSet(null)
        if (tap != null) {
            val resolution = try {
                hitSource.resolve(
                    frame = frame,
                    x = tap.x,
                    y = tap.y,
                    viewWidth = if (tap.width > 0) tap.width else viewWidth,
                    viewHeight = if (tap.height > 0) tap.height else viewHeight,
                    snapshot = snapshot,
                    previousNode = previousNode,
                )
            } catch (error: Throwable) {
                Log.w(TAG, "tap resolution failed", error)
                HitResolution.miss(emptyList())
            }
            mutableResults.value = resolution
            resolution.floor?.let { onFloorResolved?.invoke(it) }
            onTapResolved?.invoke(resolution)
            return
        }

        val point = reticlePoint ?: return
        if (nowMillis - lastReticleMillis < reticleIntervalMs) return
        lastReticleMillis = nowMillis
        val resolution = try {
            hitSource.resolve(
                frame = frame,
                x = point.first,
                y = point.second,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                snapshot = snapshot,
                previousNode = previousNode,
            )
        } catch (error: Throwable) {
            null
        }
        // The reticle is the path that looks at the floor continuously: a room where the operator
        // points at the floor and waits, without tapping, still records a floor (finding 226).
        resolution?.floor?.let { onFloorResolved?.invoke(it) }
        mutableReticle.value = resolution?.hit
    }

    /** UI consumes a tap result; returns it and clears the slot. */
    fun consumeResult(): HitResolution? {
        val value = mutableResults.value
        mutableResults.value = null
        return value
    }

    fun clearTap() {
        pendingTap.set(null)
    }

    companion object {
        private const val TAG = "ArInteraction"
    }
}
