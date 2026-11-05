/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.slam

import kotlin.math.abs

/**
 * A revisit the native detector found: two nodes that are close *in the current estimate*.
 *
 * The numbers come from `ProximityLoopClosureDetector`, which already applies the geometry -
 * twenty keyframes apart, 0.35 m, half a radian of heading. This type exists so that the Kotlin
 * side can reason about a candidate without depending on the bridge, and so that the thresholds
 * stay in **one** place: a second copy here would be a second answer to the same question, which
 * is how two layers end up disagreeing about when two places are the same place.
 */
data class ClosureCandidate(
    val from: Int,
    val to: Int,
    val distanceMetres: Float,
)

/** What the operator is shown: one candidate, never a list. */
data class ClosureOffer(
    val from: Int,
    val to: Int,
    val distanceMetres: Float,
)

/**
 * How the question "is this a loop?" is asked, and when it is not asked at all.
 *
 * The geometry is the detector's; these are the two rules the detector cannot have, because both
 * are about the conversation rather than about space:
 *
 *  * **A loop needs a loop.** Below [ClosurePolicyConfig.minNodes] nodes the graph is a corridor
 *    or a single wall. Offering a closure there would teach the operator to dismiss the prompt,
 *    and a dismissed prompt is a prompt that stops working. At the 0.5 m keyframe spacing of
 *    [KeyframeSelector] sixty nodes is roughly thirty metres of walking - a room, not a step.
 *  * **A refusal is remembered.** After a dismissal the same pair is not offered again, and no
 *    prompt appears for [ClosurePolicyConfig.cooldownMillis]. A "no" that is asked again on the
 *    next frame is not a question, it is a nag.
 *
 * The remaining choice is which candidate to show: the closest one, because distance is the only
 * evidence there is. The graph is never touched here, and nothing is applied without the operator
 * saying so - a false closure folds a room in half, and the fold is invisible afterwards.
 */
data class ClosurePolicyConfig(
    /** Nodes the graph must hold before a closure is worth asking about. */
    val minNodes: Int = DEFAULT_MIN_NODES,
    /** How long a "no" is respected before another prompt may appear. */
    val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
) {
    init {
        require(minNodes > 0) { "minNodes must be positive, was $minNodes" }
        require(cooldownMillis >= 0L) { "cooldownMillis must not be negative, was $cooldownMillis" }
    }

    companion object {
        /** ~30 m of keyframes at the policy's 0.5 m spacing. */
        const val DEFAULT_MIN_NODES: Int = 60

        /** A minute: long enough that a dismissal ends the conversation for this run. */
        const val DEFAULT_COOLDOWN_MILLIS: Long = 60_000L
    }
}

class ClosurePolicy(val config: ClosurePolicyConfig = ClosurePolicyConfig()) {

    /** Pairs the operator refused, as one `Long` per unordered pair. */
    private val dismissed = HashSet<Long>()
    private var dismissedAtMillis: Long? = null

    /**
     * Decide whether to show an offer now. `null` means "say nothing", which is the answer for
     * almost every frame: an empty graph, a cooldown, or nothing the detector found.
     */
    fun decide(
        candidates: List<ClosureCandidate>,
        nodeCount: Int,
        nowMillis: Long,
    ): ClosureOffer? {
        if (nodeCount < config.minNodes) return null
        val cooldown = dismissedAtMillis
        if (cooldown != null && nowMillis - cooldown < config.cooldownMillis) return null

        val best = candidates
            .asSequence()
            .filter { it.distanceMetres.isFinite() && it.from != it.to }
            .filter { key(it.from, it.to) !in dismissed }
            .minWithOrNull(
                compareBy<ClosureCandidate>({ it.distanceMetres }, { -abs(it.to - it.from) }),
            )
            ?: return null
        return ClosureOffer(best.from, best.to, best.distanceMetres)
    }

    /** The operator said no. Remembered for this run, and it silences the next minute. */
    fun dismiss(offer: ClosureOffer, nowMillis: Long) {
        dismissed += key(offer.from, offer.to)
        dismissedAtMillis = nowMillis
    }

    /** A new session or a new graph: what was refused about the old one says nothing. */
    fun clear() {
        dismissed.clear()
        dismissedAtMillis = null
    }

    private fun key(a: Int, b: Int): Long =
        (minOf(a, b).toLong() shl 32) or (maxOf(a, b).toLong() and 0xFFFF_FFFFL)
}
