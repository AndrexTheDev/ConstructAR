/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two taps of a reference measurement — and the taps that must *not* count.
 *
 * Every case here is a thing a person does on site with a folding rule in one hand: a tap on the
 * wrong end, a tap twice on the same end, a third tap while reading the answer, and a tap with no
 * sweep running at all (which is an ordinary measurement, not a calibration).
 */
class CalibrationSweepTest {

    private val near = Vec3(0f, 0f, 0f)
    private val oneMetre = Vec3(0f, 0f, 1.02f)

    @Test
    fun `a sweep needs a reference before taps mean anything`() {
        val idle = CalibrationSweep.Idle

        assertEquals(CalibrationSweep.Step.IDLE, idle.step)
        assertFalse(idle.step.collecting)
        assertNull(idle.reference)
        assertEquals(idle, idle.tap(near))
    }

    @Test
    fun `the first tap asks for the second and the second answers`() {
        val started = CalibrationSweep.Idle.start(CalibrationReference.ZOLLSTOCK_1M)
        assertEquals(CalibrationSweep.Step.FIRST, started.step)
        assertTrue(started.step.collecting)

        val afterFirst = started.tap(near)
        assertEquals(CalibrationSweep.Step.SECOND, afterFirst.step)
        assertTrue(afterFirst.step.collecting)
        assertNull(afterFirst.outcome)

        val finished = afterFirst.tap(oneMetre)
        assertEquals(CalibrationSweep.Step.DONE, finished.step)
        assertFalse(finished.step.collecting)
        // A `Vec3` distance is a Float, so the factor is compared to the millimetre the operator
        // is shown, not to the bit pattern of `1.0 / 1.02`.
        val accepted = finished.outcome as CalibrationOutcome.Accepted
        assertEquals(1.0 / 1.02, accepted.scale, 1e-6)
        assertEquals(CalibrationReference.ZOLLSTOCK_1M, finished.reference)
    }

    @Test
    fun `the pair is kept while the operator aims at the far end`() {
        val second = CalibrationSweep.Idle
            .start(CalibrationReference.CREDIT_CARD_LONG)
            .tap(near) as CalibrationSweep.SecondPoint

        assertEquals(near, second.first)
        assertEquals(CalibrationReference.CREDIT_CARD_LONG, second.reference)
    }

    @Test
    fun `a third tap does not quietly replace the answer under the operator's thumb`() {
        // The dialog with "factor 1.053?" is open; he taps the scene again to dismiss it. That tap
        // must not recompute the factor from the last two taps - the number he is reading would
        // change while he decides.
        val finished = CalibrationSweep.Idle
            .start(CalibrationReference.ZOLLSTOCK_2M)
            .tap(near)
            .tap(Vec3(0f, 0f, 1.9f))

        assertEquals(finished, finished.tap(Vec3(5f, 5f, 5f)))
        assertEquals(finished, finished.tap(near))
    }

    @Test
    fun `two taps on the same end are unmeasurable, not a factor of infinity`() {
        val finished = CalibrationSweep.Idle
            .start(CalibrationReference.CREDIT_CARD_LONG)
            .tap(near)
            .tap(near)

        assertEquals(CalibrationOutcome.Unmeasurable, finished.outcome)
        assertNull(finished.reference)
        assertEquals(CalibrationSweep.Step.DONE, finished.step)
    }

    @Test
    fun `a sweep measured far too long is rejected by the window, not accepted`() {
        // A 1 m rule where the phone measures 0.6 m: S = 1.67. The sweep is done and the answer is
        // a refusal - which is the point, because the screen has to show the number it refused.
        val finished = CalibrationSweep.Idle
            .start(CalibrationReference.ZOLLSTOCK_1M)
            .tap(near)
            .tap(Vec3(0f, 0f, 0.6f))

        val rejected = finished.outcome as CalibrationOutcome.Rejected
        assertEquals(1.0 / 0.6, rejected.scale, 1e-5)
        assertEquals(CalibrationReference.ZOLLSTOCK_1M, finished.reference)
    }

    @Test
    fun `starting again replaces the sweep, from the first end`() {
        // A mis-tap on the near end: the operator does not "undo", he starts the measurement over.
        val misTapped = CalibrationSweep.Idle
            .start(CalibrationReference.ZOLLSTOCK_1M)
            .tap(Vec3(9f, 9f, 9f)) as CalibrationSweep.SecondPoint

        val restarted = misTapped.start(CalibrationReference.CREDIT_CARD_SHORT)

        assertEquals(CalibrationSweep.FirstPoint(CalibrationReference.CREDIT_CARD_SHORT), restarted)
        assertEquals(CalibrationSweep.Step.FIRST, restarted.step)
    }

    @Test
    fun `cancelling forgets the pair`() {
        val running = CalibrationSweep.Idle.start(CalibrationReference.ZOLLSTOCK_2M).tap(near)

        assertEquals(CalibrationSweep.Idle, running.cancel())
        assertEquals(CalibrationSweep.Step.IDLE, running.cancel().step)
    }

    @Test
    fun `an accepted sweep reports its reference, so the protocol can name the reference`() {
        // The label in the PDF says which object the operator held ("Zollstock, 1,00 m"), which is
        // the difference between a document that claims precision and one that shows its basis.
        val finished = CalibrationSweep.Idle
            .start(CalibrationReference.ZOLLSTOCK_1M)
            .tap(near)
            .tap(Vec3(0f, 0f, 0.97f))

        assertTrue(finished.outcome is CalibrationOutcome.Accepted)
        assertEquals(CalibrationReference.ZOLLSTOCK_1M, finished.reference)
    }
}
