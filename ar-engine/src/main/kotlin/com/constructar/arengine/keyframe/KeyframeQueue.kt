/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.keyframe

import kotlinx.coroutines.channels.Channel

/** One pose-graph write, as a value: nothing here touches the native layer. */
sealed interface KeyframeCommand {

    /** [index] is the queue's logical numbering, not the engine's. */
    data class AddNode(
        val index: Int,
        val poseColumnMajor: FloatArray,
        val fixed: Boolean,
    ) : KeyframeCommand

    data class AddEdge(
        val from: Int,
        val to: Int,
        val measurementColumnMajor: FloatArray,
    ) : KeyframeCommand
}

/**
 * The sink the render thread uses: it enqueues, and returns the logical index it just handed out.
 *
 * This exists because of one sentence in `NativeSlamEngine`'s contract - integrate, extract and
 * pose-graph calls must be serialised by the pipeline's single worker thread - while the decision
 * to keep a keyframe has to be made on the frame that produced the pose. Doing the JNI call where
 * the decision is made would break that contract; doing the decision on the worker would mean
 * shipping every frame's pose across a channel for a comparison of two vectors. So the decision
 * stays on the render thread and only an *accepted* keyframe crosses over.
 *
 * Bounded on purpose. A keyframe the worker never drains is a keyframe the operator does not need
 * - dropping one loses a graph node, blocking loses the frame loop - and [dropped] makes the loss
 * countable instead of invisible.
 */
class KeyframeQueue(capacity: Int = DEFAULT_CAPACITY) : PoseGraphSink {

    private val channel = Channel<KeyframeCommand>(capacity = capacity)

    /** Volatile for the same reason as [dropped]: the worker reads it for the status line. */
    @Volatile
    private var nextIndex: Int = 0

    /**
     * Commands refused because the queue was full. Evidence, not a warning to be ignored.
     *
     * Volatile: the worker publishes it in the pose-graph status while this thread keeps
     * producing (finding 244).
     */
    @Volatile
    var dropped: Int = 0
        private set

    /** How many nodes have been handed a logical index so far. */
    val produced: Int get() = nextIndex

    override fun addNode(poseColumnMajor: FloatArray, fixed: Boolean): Int {
        val index = nextIndex
        val accepted = channel.trySend(KeyframeCommand.AddNode(index, poseColumnMajor, fixed)).isSuccess
        if (!accepted) {
            dropped += 1
            return PoseGraphSink.NO_NODE
        }
        nextIndex += 1
        return index
    }

    override fun addEdge(from: Int, to: Int, measurementColumnMajor: FloatArray) {
        val accepted = channel.trySend(
            KeyframeCommand.AddEdge(from, to, measurementColumnMajor),
        ).isSuccess
        if (!accepted) dropped += 1
    }

    /** The next command, or null when there is nothing (or the queue is closed). Worker thread. */
    fun poll(): KeyframeCommand? = channel.tryReceive().getOrNull()

    /** No further commands will be produced. */
    fun close() {
        channel.close()
    }

    companion object {
        /**
         * 64 commands is over a second of walking at the fastest rate the policy allows (one
         * keyframe per 200 ms), so a queue this full means the worker is not running at all.
         */
        const val DEFAULT_CAPACITY: Int = 64
    }
}

/**
 * Applies queued commands to a real sink, on the thread that owns the engine.
 *
 * The two things it exists for:
 *
 *  * **Logical to native indices.** The queue numbers what it produced; only the engine knows the
 *    node index it assigned. Edges reference the queue's numbering and are translated here.
 *  * **An edge whose node never made it is dropped, not guessed.** If the queue overflowed and the
 *    node was lost, the edge would otherwise reference a wrong node - a plausible-looking
 *    measurement between two places that were never adjacent, which the optimiser would then
 *    spread over the trajectory.
 */
class KeyframeApplier(private val sink: PoseGraphSink) {

    private val nativeIndex = HashMap<Int, Int>()

    var appliedNodes: Int = 0
        private set
    var appliedEdges: Int = 0
        private set
    var refusedNodes: Int = 0
        private set
    var droppedEdges: Int = 0
        private set

    fun apply(command: KeyframeCommand) {
        when (command) {
            is KeyframeCommand.AddNode -> {
                val index = sink.addNode(command.poseColumnMajor, command.fixed)
                if (index == PoseGraphSink.NO_NODE) {
                    refusedNodes += 1
                } else {
                    nativeIndex[command.index] = index
                    appliedNodes += 1
                }
            }
            is KeyframeCommand.AddEdge -> {
                val from = nativeIndex[command.from]
                val to = nativeIndex[command.to]
                if (from == null || to == null) {
                    droppedEdges += 1
                } else {
                    sink.addEdge(from, to, command.measurementColumnMajor)
                    appliedEdges += 1
                }
            }
        }
    }

    /** Forgets the translation table - the native graph was reset, so the indices are gone. */
    fun clear() {
        nativeIndex.clear()
    }
}
