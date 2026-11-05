/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

/**
 * What a marker status line has to say, as a value.
 *
 * The dialog printed a factor and nothing else (finding 237), and
 * [MarkerScaleProposal] carries more than that: how many pairs agreed, how many were filtered out,
 * and the longest baseline behind the number. A factor from one 20 cm pair and a factor from six
 * pairs over three metres look identical on a screen that prints only the factor, and the operator
 * has no way to tell which one they are about to apply to a project.
 *
 * This is the mapping, not the text: the four cases a screen has to distinguish are decided here,
 * in `:core`, where they can be tested without a phone, and the words stay in `:app`'s six locales.
 * No number is rounded, formatted or interpreted on the way through - a line that formatted here
 * would put a locale decision in `:core`.
 */
sealed interface MarkerStatusLine {

    /** No sweep is running: nothing to print. */
    data object Off : MarkerStatusLine

    /** Running, with sightings in hand and no factor. */
    data class Watching(
        val sightings: Int,
        val markerIds: Int,
        /**
         * Sightings refused because they came from another ARCore session.
         *
         * Structurally 0 for the screen that exists today - `ArMeasureController` opens a new sweep
         * when a session begins - and that is the point: the count is what makes 0 *evidence* that
         * the guard works rather than an assumption, and a future caller that keeps one sweep
         * across a re-localisation cannot pair two world origins.
         */
        val ignoredForeign: Int,
    ) : MarkerStatusLine

    /** A factor, with the evidence it rests on. */
    data class Measured(
        val factor: Double,
        val pairs: Int,
        val rejectedPairs: Int,
        val baselineMetres: Float,
    ) : MarkerStatusLine

    /** A measured factor the acceptance window would not take. */
    data class OutOfWindow(
        val factor: Double,
        val minScale: Double,
        val maxScale: Double,
    ) : MarkerStatusLine

    companion object {

        /**
         * The line for [state].
         *
         * The window is asked again rather than trusted: a proposal carries an `accepted` flag, and
         * a screen that printed a factor under that flag would repeat whatever the producer
         * believed. `ReferenceCalibration.accepted` is the one definition of the window, so this
         * cannot disagree with the two-tap sweep beside it.
         */
        fun of(state: MarkerSweepState): MarkerStatusLine = when (state) {
            MarkerSweepState.Off -> Off
            is MarkerSweepState.Watching -> Watching(
                sightings = state.sightings,
                markerIds = state.markerIds,
                ignoredForeign = state.ignoredForeign,
            )
            is MarkerSweepState.Ready -> {
                val proposal = state.proposal
                if (ReferenceCalibration.accepted(proposal.factor)) {
                    Measured(
                        factor = proposal.factor,
                        pairs = proposal.pairs,
                        rejectedPairs = proposal.rejectedPairs,
                        baselineMetres = proposal.baselineMetres,
                    )
                } else {
                    OutOfWindow(
                        factor = proposal.factor,
                        minScale = ReferenceCalibration.MIN_SCALE,
                        maxScale = ReferenceCalibration.MAX_SCALE,
                    )
                }
            }
        }
    }
}
