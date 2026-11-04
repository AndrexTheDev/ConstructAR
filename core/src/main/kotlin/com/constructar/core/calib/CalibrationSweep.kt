/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import com.constructar.core.math.Vec3

/**
 * The two taps of a reference measurement, as a value the screen can hold.
 *
 * The arithmetic of a calibration is one division ([ReferenceCalibration]), and everything that
 * can go wrong around it is a *sequence* problem: the operator starts a sweep, taps the far end of
 * a folding rule twice because the first tap was a mis-tap, or taps a third time while the dialog
 * that says "factor 1.053?" is still open. A bare `var firstPoint: Vec3?` in a composable answers
 * none of those: the second tap either replaces the first (and the sweep measures from the
 * operator's thumb to his thumb) or is appended somewhere nobody reads.
 *
 * This is that sequence written down once, as an immutable value, so the screen holds a state
 * instead of three nullables and the AR button does not have to know the rules:
 *
 * * [Idle] — nothing is being measured. A tap does nothing.
 * * [FirstPoint] — a reference is chosen and one end has been tapped.
 * * [SecondPoint] — both ends are in; the next tap produces the outcome, right or wrong.
 * * [Finished] — the answer is in and **stays** in: a further tap is ignored rather than quietly
 *   replacing a measurement the operator is looking at. Starting again is a deliberate act.
 *
 * Deliberately free of Android: `Vec3` is `:core`'s own type, so the whole flow is unit-testable
 * on the JVM - which matters, because the phone it runs on cannot be in the test.
 */
sealed interface CalibrationSweep {

    /** The step a screen shows, without the screen knowing the state machine. */
    enum class Step {
        /** No sweep running: the button offers one. */
        IDLE,

        /** Waiting for the first end. */
        FIRST,

        /** Waiting for the second end. */
        SECOND,

        /** The outcome is on screen; the operator accepts or discards it. */
        DONE,
        ;

        /** True while the screen should be collecting taps. */
        val collecting: Boolean get() = this == FIRST || this == SECOND
    }

    /** The step this value is in. */
    val step: Step

    /** The reference being measured, or `null` in [Idle]. */
    val reference: CalibrationReference?

    /** The computed outcome, or `null` while the sweep is still running. */
    val outcome: CalibrationOutcome?

    /** Begins a sweep against [reference]; a sweep already running is replaced. */
    fun start(reference: CalibrationReference): CalibrationSweep

    /** Feeds one tapped point; the value that comes back is what the screen holds next. */
    fun tap(point: Vec3): CalibrationSweep

    /** Ends the sweep without an answer. */
    fun cancel(): CalibrationSweep = Idle

    /** Nothing being measured. A tap here is a tap on the AR scene, not on a calibration. */
    data object Idle : CalibrationSweep {
        override val step: Step get() = Step.IDLE
        override val reference: CalibrationReference? get() = null
        override val outcome: CalibrationOutcome? get() = null
        override fun start(reference: CalibrationReference): CalibrationSweep = FirstPoint(reference)
        override fun tap(point: Vec3): CalibrationSweep = this
    }

    /** One end is in; the screen is waiting for the other. */
    data class FirstPoint(override val reference: CalibrationReference) : CalibrationSweep {
        override val step: Step get() = Step.FIRST
        override val outcome: CalibrationOutcome? get() = null
        override fun start(reference: CalibrationReference): CalibrationSweep = FirstPoint(reference)
        override fun tap(point: Vec3): CalibrationSweep = SecondPoint(reference, point)
    }

    /**
     * Both ends are in; the next tap is the answer.
     *
     * The pair is kept, so a screen that shows "1,00 m · tippe das andere Ende" can also show what
     * it already has; and so a mis-tap can be undone by [start]ing again rather than by tapping,
     * which is the difference between "measure it again" and "measure it differently".
     */
    data class SecondPoint(
        override val reference: CalibrationReference,
        val first: Vec3,
    ) : CalibrationSweep {
        override val step: Step get() = Step.SECOND
        override val outcome: CalibrationOutcome? get() = null
        override fun start(reference: CalibrationReference): CalibrationSweep = FirstPoint(reference)
        override fun tap(point: Vec3): CalibrationSweep =
            Finished(ReferenceCalibration.outcome(reference, first, point))
    }

    /**
     * The answer, held until the operator acts on it.
     *
     * [tap] is ignored on purpose: a third tap while the coffee-coloured dialog is open would
     * otherwise recompute the factor from the *last two* taps, and the number the operator is
     * reading would change under his thumb.
     */
    data class Finished(val result: CalibrationOutcome) : CalibrationSweep {
        override val step: Step get() = Step.DONE
        override val outcome: CalibrationOutcome get() = result
        override val reference: CalibrationReference?
            get() = when (result) {
                is CalibrationOutcome.Accepted -> result.reference
                is CalibrationOutcome.Rejected -> result.reference
                CalibrationOutcome.Unmeasurable -> null
            }
        override fun start(reference: CalibrationReference): CalibrationSweep = FirstPoint(reference)
        override fun tap(point: Vec3): CalibrationSweep = this
    }
}
