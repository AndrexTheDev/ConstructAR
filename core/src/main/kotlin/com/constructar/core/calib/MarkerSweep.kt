/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

/**
 * The knobs a sweep is opened with.
 *
 * [sessionId] is not a knob: it is the identity of the ARCore session the sightings belong to, and
 * every observation carrying a different one is refused rather than paired (see
 * [MarkerObservation.sessionId]).
 */
data class MarkerSweepOptions(
    val sessionId: Long,
    val minBaselineMetres: Float,
    val maxRelativeDeviation: Float,
    val minPairs: Int = 1,
    /** Sightings kept per marker. The newest win; older ones are the ones that may have moved. */
    val maxSightingsPerId: Int = 8,
)

/** What a sweep currently has to say, which is what a status line shows. */
sealed interface MarkerSweepState {

    /** No sweep is running. */
    data object Off : MarkerSweepState

    /** Running, with sightings in hand and no factor yet. */
    data class Watching(val sightings: Int, val markerIds: Int, val ignoredForeign: Int) : MarkerSweepState

    /** A factor is published; the screen may apply it, and the decision is on the proposal. */
    data class Ready(
        val proposal: MarkerScaleProposal,
        val sightings: Int,
        val ignoredForeign: Int,
    ) : MarkerSweepState
}

/**
 * The sightings of one marker distance check, as a value the screen can hold.
 *
 * [MarkerBaseline] answers "what does this *pair* say"; a screen does not have a pair, it has a
 * stream of frames and a phone being waved about. Three things go wrong in that gap, and each of
 * them produces a believable number rather than an error:
 *
 * * **Sightings from two sessions get paired.** The phone re-localises, the operator leaves the
 *   screen and comes back, and the sweep pairs a camera position from one world with a tag vector
 *   measured in the other. That is finding 217's defect in a new place, so the guard is the same:
 *   foreign sightings are counted and never paired.
 * * **A sweep runs forever.** A marker seen twenty minutes ago, before the operator walked into the
 *   next room, still votes — and it votes for a phone that has long since re-localised. Sightings
 *   are capped per marker, newest kept.
 * * **Standing still is mistaken for consent.** Two sightings from the same spot have a baseline of
 *   zero; the honest answer is "not yet", and [state] stays [MarkerSweepState.Watching] rather than
 *   publishing the 1.0 that would certify an unchecked phone.
 *
 * Immutable: [observe] returns the next value, so Compose holds one `state` and the sequence logic
 * lives here instead of in a composable that recomposes sixty times a second.
 */
class MarkerSweep private constructor(
    val options: MarkerSweepOptions,
    val state: MarkerSweepState,
    private val observations: List<MarkerObservation>,
    private val ignoredForeign: Int,
) {

    /** Appends one sighting and re-estimates; refuses a sighting from another session. */
    fun observe(observation: MarkerObservation): MarkerSweep {
        if (observation.sessionId != options.sessionId) {
            return MarkerSweep(options, recompute(observations, ignoredForeign + 1), observations, ignoredForeign + 1)
        }
        if (options.maxSightingsPerId < 1) return this
        val kept = (observations + observation).let { combined ->
            // Newest N per marker, in arrival order: a marker that was physically moved shows up
            // as an outlier for a while, and dropping the oldest first is what lets the sweep
            // forget it.
            combined.withIndex()
                .groupBy { it.value.id }
                .flatMap { (_, group) -> group.takeLast(options.maxSightingsPerId) }
                .sortedBy { it.index }
                .map { it.value }
        }
        return MarkerSweep(options, recompute(kept, ignoredForeign), kept, ignoredForeign)
    }

    /** Ends the sweep: no sightings, no factor, and the next start gets a new session id. */
    fun reset(): MarkerSweep = MarkerSweep(options, MarkerSweepState.Off, emptyList(), 0)

    private fun recompute(
        sightings: List<MarkerObservation>,
        foreign: Int,
    ): MarkerSweepState {
        val proposal = MarkerBaseline.estimate(
            observations = sightings,
            minBaselineMetres = options.minBaselineMetres,
            maxRelativeDeviation = options.maxRelativeDeviation,
            minPairs = options.minPairs,
        )
        return if (proposal != null) {
            MarkerSweepState.Ready(proposal, sightings.size, foreign)
        } else {
            MarkerSweepState.Watching(
                sightings = sightings.size,
                markerIds = sightings.map { it.id }.toSet().size,
                ignoredForeign = foreign,
            )
        }
    }

    companion object {

        /** How many sightings a sweep keeps of one marker before the oldest is dropped. */
        const val DEFAULT_MAX_SIGHTINGS_PER_ID = 8

        /**
         * Opens a sweep for one session, or `null` when the options cannot describe one.
         *
         * The baseline floor and the deviation are the caller's: the layout path's defaults are
         * `TagScaleOptions` in the native layer, and this file does not restate them.
         */
        fun start(options: MarkerSweepOptions): MarkerSweep? {
            if (!(options.minBaselineMetres > 0f) || !options.minBaselineMetres.isFinite()) return null
            if (!(options.maxRelativeDeviation > 0f) || !options.maxRelativeDeviation.isFinite()) return null
            if (options.minPairs < 1 || options.maxSightingsPerId < 1) return null
            // A running sweep that has seen nothing is **watching**, not off. Returning `Off` here
            // would make "the probe is running and has no evidence yet" indistinguishable from
            // "the probe is not running", which is exactly the pair of states a status line exists
            // to tell apart (finding 235).
            return MarkerSweep(options, MarkerSweepState.Watching(0, 0, 0), emptyList(), 0)
        }
    }
}
