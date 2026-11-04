/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

import com.constructar.core.geo.DepthIntrinsics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `close()` does to the native context, and what every other method does afterwards.
 *
 * `NativeSlamEngine` wraps a raw `EngineContext*`. Until finding 182 the handle was a `val` and
 * `close()` was `if (handle != 0L) nativeDestroy(handle)`: the handle survived the free, so a
 * second close handed the same pointer to `delete` twice (a double free), and a call after the
 * first close passed freed memory into the JNI layer, where `contextFrom()` cannot tell it from a
 * live context. Every entry point already carried a `handle == 0L` guard that could never fire.
 *
 * The native library is not present in a JVM test, which is why the free itself is injectable: the
 * point of these cases is *how many times* the context is freed and whether the other methods stop
 * calling into it. A handle that is not zeroed makes these cases fail the loud way - the unstubbed
 * `native…` function throws `UnsatisfiedLinkError` - so putting the `val` back is the mutation
 * check.
 */
class NativeSlamEngineCloseTest {

    /** Records every free, and throws nothing: the native library does not exist here. */
    private class Frees {
        val handles = mutableListOf<Long>()
        val destroy: (Long) -> Unit = { handle -> handles += handle }
    }

    private fun newEngine(frees: Frees, handle: Long = 42L) = NativeSlamEngine(handle, frees.destroy)

    private fun identity(): FloatArray =
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    @Test
    fun `close frees the context once`() {
        val frees = Frees()
        newEngine(frees).close()
        assertEquals(listOf(42L), frees.handles)
    }

    @Test
    fun `closing twice frees the context once`() {
        val frees = Frees()
        val engine = newEngine(frees)
        engine.close()
        engine.close()
        engine.close()
        assertEquals("the same context was handed to delete more than once", 1, frees.handles.size)
    }

    @Test
    fun `every entry point is a no-op after close`() {
        val frees = Frees()
        val engine = newEngine(frees)
        engine.close()

        // Each of these used to reach the JNI layer with the freed handle: on a phone that is a
        // use-after-free, in this test it is an UnsatisfiedLinkError.
        assertNull(engine.extractMesh())
        assertNull(engine.lastBounds())
        assertNull(engine.optimizePoseGraph())
        assertTrue(engine.loopClosureCandidates().isEmpty())
        assertFalse(engine.isRtabMapAvailable())
        assertEquals(-1, engine.addPoseNode(FloatArray(16)))
        assertNull(
            engine.integrateDepth(
                depthMm = ShortArray(4),
                width = 2,
                height = 2,
                rowStrideElements = 2,
                confidence = null,
                confidenceWidth = 0,
                confidenceHeight = 0,
                confidenceRowStride = 0,
                intrinsics = DepthIntrinsics(fx = 200f, fy = 200f, cx = 1f, cy = 1f),
                cameraToWorld = identity(),
                timestampNs = 0L,
                pixelStep = 2,
            ),
        )

        // The void-returning calls: nothing of theirs reaches the native side either.
        engine.reset()
        engine.resetPoseGraph()
        engine.addPoseEdge(from = 0, to = 1, measurement = identity())

        assertEquals("a call after close freed the context again", 1, frees.handles.size)
    }

    @Test
    fun `two threads closing at once free the context once`() {
        val frees = Frees()
        val engine = newEngine(frees, handle = 7L)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) {
            Thread {
                start.await()
                engine.close()
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("both closers finished", done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(7L), frees.handles)
    }
}
