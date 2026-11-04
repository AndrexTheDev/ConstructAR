/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine

import com.constructar.arengine.depth.DepthBufferPool
import com.constructar.arengine.depth.DepthFrameSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a pooled depth array may go back to the pool (finding 168).
 *
 * One frame is published to the hit-test cascade and handed to the integration worker, so
 * "the frame that replaced it" is not the last reader. These tests pin the rule the pipeline
 * relies on: the array is returned by the *last* holder, exactly once.
 */
class DepthBufferLifetimeTest {

    private fun snapshot(onLastRelease: () -> Unit): DepthFrameSnapshot =
        DepthFrameSnapshot(
            depthMm = ShortArray(4) { 1_000.toShort() },
            width = 2,
            height = 2,
            rowStride = 2,
            onLastRelease = onLastRelease,
        )

    @Test
    fun `references are counted from the capturing thread`() {
        val snapshot = snapshot(onLastRelease = {})
        assertEquals("the render thread starts out as the only holder", 1, snapshot.referenceCount)
        snapshot.retain()
        assertEquals(2, snapshot.referenceCount)
        snapshot.release()
        assertEquals(1, snapshot.referenceCount)
        snapshot.release()
        assertEquals(0, snapshot.referenceCount)
    }

    /**
     * The case the pipeline got wrong: the render thread publishes a newer frame while the
     * worker is still integrating the old one. The old frame's array must survive that.
     */
    @Test
    fun `a frame the worker still holds is not returned when a newer one replaces it`() {
        var returns = 0
        val snapshot = snapshot(onLastRelease = { returns++ })
        snapshot.retain() // the worker's reference
        snapshot.release() // the render thread publishes the next frame and lets go
        assertEquals("the worker is still reading the pixels", 0, returns)
        snapshot.release() // integration returns
        assertEquals("the last holder returns the array", 1, returns)
    }

    /**
     * Both planes of a frame belong to the pool, and both go back with the same release
     * (finding 172): the confidence image was a fresh `ByteArray` on every frame.
     */
    @Test
    fun `the confidence plane is pooled and comes back with the depth plane`() {
        val pool = DepthBufferPool(capacity = 2, initialPixels = 4)
        val depth = pool.acquire(4)
        val confidence = pool.acquireConfidence(6)
        val snapshot = DepthFrameSnapshot(
            depthMm = depth,
            width = 2,
            height = 2,
            rowStride = 2,
            confidence = confidence,
            confidenceWidth = 2,
            confidenceHeight = 3,
            confidenceRowStride = 2,
            onLastRelease = {
                pool.release(depth)
                pool.releaseConfidence(confidence)
            },
        )
        snapshot.release()
        assertTrue(
            "the depth plane never came back",
            listOf(pool.acquire(4), pool.acquire(4)).any { it === depth },
        )
        assertTrue(
            "the confidence plane never came back",
            listOf(pool.acquireConfidence(6), pool.acquireConfidence(6)).any { it === confidence },
        )
    }

    @Test
    fun `an array is never returned twice`() {
        var returns = 0
        val snapshot = snapshot(onLastRelease = { returns++ })
        snapshot.release()
        snapshot.release() // a stray second release: a bug, but not a second trip to the pool
        assertEquals(1, returns)
    }

    /**
     * End to end through the real pool: while a frame is held, the pool must not hand its
     * array to the next capture — that is how pixels from one frame end up under another
     * frame's pose.
     */
    @Test
    fun `the pool reuses an array only after the last reference goes`() {
        val pool = DepthBufferPool(capacity = 2, initialPixels = 4)
        val held = pool.acquire(4)
        val snapshot = DepthFrameSnapshot(
            depthMm = held,
            width = 2,
            height = 2,
            rowStride = 2,
            onLastRelease = { pool.release(held) },
        )

        val next = pool.acquire(4)
        assertNotSame("the pool handed out the frame the worker is integrating", held, next)

        snapshot.release()
        val afterRelease = listOf(pool.acquire(4), pool.acquire(4))
        assertTrue("the released array never came back to the pool", afterRelease.any { it === held })
    }
}
