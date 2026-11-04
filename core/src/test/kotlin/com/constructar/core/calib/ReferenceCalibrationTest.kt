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
 * The scale a measured metre is multiplied by, and the two rules around it.
 *
 * The interesting cases are not "the arithmetic works" - it is one division - but the ones that
 * decide what the app does when the operator measured the wrong thing: zero length, a factor at
 * the edge of the window, and a factor that would drag a whole room away from where it was
 * measured.
 */
class ReferenceCalibrationTest {

    @Test
    fun `a card measured at its true size scales by one`() {
        val outcome = ReferenceCalibration.outcome(
            CalibrationReference.CREDIT_CARD_LONG,
            CalibrationReference.CREDIT_CARD_LONG.realMetres,
        )

        assertEquals(
            CalibrationOutcome.Accepted(CalibrationReference.CREDIT_CARD_LONG, 1.0),
            outcome,
        )
    }

    @Test
    fun `the standard says 85_60 by 53_98 millimetres`() {
        // ISO/IEC 7810 ID-1. Not a preference and not tunable: two decimals of a millimetre on a
        // card edge is 0.02 % of the reference, which is far below anything ARCore can resolve.
        assertEquals(0.0856, CalibrationReference.CREDIT_CARD_LONG.realMetres, 1e-12)
        assertEquals(0.05398, CalibrationReference.CREDIT_CARD_SHORT.realMetres, 1e-12)
        assertTrue(CalibrationReference.CREDIT_CARD_LONG.isCard)
        assertTrue(CalibrationReference.CREDIT_CARD_SHORT.isCard)
        assertFalse(CalibrationReference.ZOLLSTOCK_1M.isCard)
    }

    @Test
    fun `a folding rule measured short corrects the metre upward`() {
        // The operator holds a 2 m rule; the phone says the two ends are 1.90 m apart. Every
        // measured metre is therefore 5 % short, and S = 2.00 / 1.90 = 1.0526 restores it.
        val outcome = ReferenceCalibration.outcome(CalibrationReference.ZOLLSTOCK_2M, 1.90)

        val accepted = outcome as CalibrationOutcome.Accepted
        assertEquals(2.0 / 1.90, accepted.scale, 1e-9)
        assertEquals(10.526, ReferenceCalibration.scaledMetres(10.0, accepted.scale), 1e-3)
    }

    @Test
    fun `a factor outside the window is refused, not clamped`() {
        // A 1 m rule that measures 0.70 m gives S = 1.43: not a calibration, a mis-tap or a
        // tracker that has lost the room. Clamping to 1.15 would print "calibrated" over it.
        val outcome = ReferenceCalibration.outcome(CalibrationReference.ZOLLSTOCK_1M, 0.70)

        val rejected = outcome as CalibrationOutcome.Rejected
        assertEquals(1.0 / 0.70, rejected.scale, 1e-9)
        assertFalse(ReferenceCalibration.accepted(rejected.scale))
    }

    @Test
    fun `the window includes both of its ends and nothing past them`() {
        assertTrue(ReferenceCalibration.accepted(ReferenceCalibration.MIN_SCALE))
        assertTrue(ReferenceCalibration.accepted(ReferenceCalibration.MAX_SCALE))
        assertFalse(ReferenceCalibration.accepted(ReferenceCalibration.MAX_SCALE + 1e-9))
        assertFalse(ReferenceCalibration.accepted(ReferenceCalibration.MIN_SCALE - 1e-9))
        assertEquals(0.85, ReferenceCalibration.MIN_SCALE, 0.0)
        assertEquals(1.15, ReferenceCalibration.MAX_SCALE, 0.0)
    }

    @Test
    fun `two taps in the same spot are not a measurement`() {
        // The zero-length case: 0.0856 / 0.0 is Infinity, and a length scaled by Infinity is a
        // bill of materials with infinite metres. It has to be "unmeasurable", not "rejected" -
        // there is no factor to show the operator.
        assertEquals(CalibrationOutcome.Unmeasurable, ReferenceCalibration.outcome(CalibrationReference.CREDIT_CARD_LONG, 0.0))
        assertEquals(
            CalibrationOutcome.Unmeasurable,
            ReferenceCalibration.outcome(CalibrationReference.ZOLLSTOCK_1M, Vec3(1f, 2f, 3f), Vec3(1f, 2f, 3f)),
        )
    }

    @Test
    fun `a length that is not a number is not a calibration`() {
        assertNull(ReferenceCalibration.compute(1.0, Double.NaN))
        assertNull(ReferenceCalibration.compute(1.0, Double.POSITIVE_INFINITY))
        assertNull(ReferenceCalibration.compute(Double.NaN, 1.0))
        assertNull(ReferenceCalibration.compute(0.0, 1.0))
        assertNull(ReferenceCalibration.compute(-1.0, 1.0))
        assertNull(ReferenceCalibration.compute(1.0, -1.0))
    }

    @Test
    fun `the correction is applied around the reference point, not the origin`() {
        // A point 2 m above the reference, measured 5 % short: it moves to 2.10 m above it and
        // stays at the same x - the reference itself does not move at all.
        val reference = Vec3(5f, 0f, -3f)
        val point = Vec3(5f, 2f, -3f)

        val corrected = ReferenceCalibration.apply(point, reference, 1.05)

        assertEquals(5f, corrected.x, 1e-6f)
        assertEquals(2.1f, corrected.y, 1e-6f)
        assertEquals(-3f, corrected.z, 1e-6f)
        assertEquals(reference, ReferenceCalibration.apply(reference, reference, 1.05))
    }

    @Test
    fun `a factor of exactly one leaves the point untouched`() {
        // Identity must be bit-exact: every project that never calibrated is scaled by 1.0 on
        // its way out, and rounding noise there would be a change to data nobody asked for.
        val point = Vec3(0.1f, 0.2f, 0.3f)

        assertEquals(point, ReferenceCalibration.apply(point, Vec3(0f, 0f, 0f), 1.0))
        assertEquals(12.345, ReferenceCalibration.scaledMetres(12.345, 1.0), 0.0)
    }

    @Test
    fun `the factor is printed with three decimals and a dot`() {
        // A ratio, not a measurement: root locale, so a German phone does not write "1,053"
        // inside a sentence that already calls it a factor.
        assertEquals("1.000", ReferenceCalibration.formatScale(1.0))
        assertEquals("1.053", ReferenceCalibration.formatScale(10.0 / 9.5))
        assertEquals("0.850", ReferenceCalibration.formatScale(ReferenceCalibration.MIN_SCALE))
    }

    @Test
    fun `the two-point outcome and the measured-length outcome agree`() {
        // The AR screen has a Vec3 pair; the dialog has a distance. One decision, both shapes.
        val first = Vec3(0f, 0f, 0f)
        val second = Vec3(0f, 0f, 0.95f)

        val fromPoints = ReferenceCalibration.outcome(CalibrationReference.ZOLLSTOCK_1M, first, second)
        val fromLength = ReferenceCalibration.outcome(CalibrationReference.ZOLLSTOCK_1M, 0.95)

        // Same decision, not the same bit pattern: a `Vec3` distance is a Float, so the two
        // factors differ in the seventh decimal. Both must accept, and both must agree to the
        // millimetre the operator is shown.
        val points = fromPoints as CalibrationOutcome.Accepted
        val length = fromLength as CalibrationOutcome.Accepted
        assertEquals(CalibrationReference.ZOLLSTOCK_1M, points.reference)
        assertEquals(length.scale, points.scale, 1e-6)
    }
}
