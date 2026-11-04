/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.calib

import com.constructar.core.math.Vec3
import java.util.Locale

/**
 * The three things a site can measure against when ARCore's own metre has to be checked.
 *
 * The sizes are physical facts, not preferences, which is why they live here and nowhere else:
 * a second copy of `0.0856` in a screen would be a second definition of a centimetre.
 *
 * * [CREDIT_CARD_LONG] and [CREDIT_CARD_SHORT] are the two edges of an **ISO/IEC 7810 ID-1**
 *   card - 85.60 mm x 53.98 mm, the bank card, the health-insurance card, the site pass. Best
 *   used close up (under ~1.5 m), where two taps on a 8.5 cm edge are still ~2 mm apart.
 * * [ZOLLSTOCK_1M] and [ZOLLSTOCK_2M] are the folding rule a German site actually carries
 *   (MID class II). Its metre is the reference for room-sized distances (1 m - 4 m), where the
 *   card's edge is a quarter of the way across the screen.
 *
 * [realMetres] is the *true* length of the reference; the app measures the same edge in AR and
 * divides. Nothing here knows about Android, ARCore or Compose - that is the point: the scale a
 * measurement is multiplied by is arithmetic, and arithmetic is the one thing this repository
 * can test without a phone.
 */
enum class CalibrationReference(val realMetres: Double) {
    /** ISO/IEC 7810 ID-1, long edge: 85.60 mm. */
    CREDIT_CARD_LONG(0.0856),

    /** ISO/IEC 7810 ID-1, short edge: 53.98 mm. */
    CREDIT_CARD_SHORT(0.05398),

    /** A folding rule held at the 1 m mark. */
    ZOLLSTOCK_1M(1.0),

    /** A folding rule held at the 2 m mark. */
    ZOLLSTOCK_2M(2.0),
    ;

    /** True for the two card references - they share one dialog row. */
    val isCard: Boolean get() = this == CREDIT_CARD_LONG || this == CREDIT_CARD_SHORT
}

/** What a calibration attempt came to. */
sealed interface CalibrationOutcome {
    /**
     * The factor is plausible and may be applied.
     *
     * [scale] is what every measured metre is multiplied by: `1.03` means the phone measured
     * 97 cm where the site has 100 cm.
     */
    data class Accepted(val reference: CalibrationReference, val scale: Double) : CalibrationOutcome

    /**
     * A factor was computed and refused, because it would move every measurement by more than
     * the window allows.
     *
     * Refused, not clamped: a factor outside the window is a **bad measurement** (the wrong
     * object tapped, one tap on the far side of the room), and clamping it would print a
     * calibrated protocol made of a mis-tap. The number travels back so the screen can say what
     * was rejected instead of only that something was.
     */
    data class Rejected(val reference: CalibrationReference, val scale: Double) : CalibrationOutcome

    /**
     * Nothing was computed: the two points were the same point, or the measurement was not a
     * finite number. Distinct from [Rejected] because no factor exists at all - the operator
     * has to tap again, not reconsider.
     */
    data object Unmeasurable : CalibrationOutcome
}

/**
 * Turns "the operator measured a known object" into the factor that corrects the AR metre.
 *
 * The arithmetic is one line - `S = d_real / ||P2 - P1||` - and the rest of this file is the
 * two rules around it that keep it honest:
 *
 * 1. **A window.** `S` is accepted inside `[0.85, 1.15]` and refused outside it. ARCore's
 *    tracking is already metric; a factor at the edge of that window means either a genuinely
 *    bad day for the tracker or a mis-tap, and both are worth refusing rather than baking into
 *    a protocol the customer signs. Outside the window the measurement is not "a bit off", it is
 *    not a measurement.
 * 2. **An anchor.** A correction is applied *around a fixed point* rather than to the origin:
 *    `P' = P_ref + S * (P - P_ref)`. Scaling about the world origin moves a node that is 20 m
 *    away from where the operator stood by 20 m x (S - 1) - centimetres to metres of drift that
 *    nobody asked for - while scaling about the reference point leaves the thing that was
 *    actually measured where it is and moves only what was measured relative to it.
 */
object ReferenceCalibration {

    /** Smallest factor that is still a measurement rather than a mis-tap. */
    const val MIN_SCALE: Double = 0.85

    /** Largest factor that is still a measurement rather than a mis-tap. */
    const val MAX_SCALE: Double = 1.15

    /**
     * `S = d_real / ||P2 - P1||`, or `null` when that is not a number the app can use.
     *
     * A measured length of zero is the case that matters: two taps in the same spot divide by
     * zero, which in IEEE-754 quietly becomes `Infinity` - and a route scaled by infinity is a
     * bill of materials with infinite metres. Non-finite and negative input are answered the
     * same way, because all of them mean "there is no measurement here".
     */
    fun compute(realMetres: Double, measuredMetres: Double): Double? {
        if (!realMetres.isFinite() || !measuredMetres.isFinite()) return null
        if (realMetres <= 0.0 || measuredMetres <= 0.0) return null
        val scale = realMetres / measuredMetres
        return scale.takeIf { it.isFinite() && it > 0.0 }
    }

    /** True when [scale] is inside the accepted window, both ends included. */
    fun accepted(scale: Double): Boolean =
        scale.isFinite() && scale >= MIN_SCALE && scale <= MAX_SCALE

    /** The whole decision, so the screen cannot assemble it differently. */
    fun outcome(reference: CalibrationReference, measuredMetres: Double): CalibrationOutcome {
        val scale = compute(reference.realMetres, measuredMetres) ?: return CalibrationOutcome.Unmeasurable
        return if (accepted(scale)) {
            CalibrationOutcome.Accepted(reference, scale)
        } else {
            CalibrationOutcome.Rejected(reference, scale)
        }
    }

    /** The same decision for a [Vec3] distance, which is what the AR screen has. */
    fun outcome(reference: CalibrationReference, first: Vec3, second: Vec3): CalibrationOutcome {
        val measured = first.distanceTo(second).toDouble()
        return outcome(reference, measured)
    }

    /**
     * `P' = P_ref + S * (P - P_ref)` - the correction of one point about the reference.
     *
     * A factor of exactly `1.0` returns [point] unchanged to the bit, so "calibrated at 1.000"
     * cannot leave a rounding scar on a measurement that was already right.
     */
    fun apply(point: Vec3, reference: Vec3, scale: Double): Vec3 {
        if (scale == 1.0) return point
        val factor = scale.toFloat()
        return reference + (point - reference) * factor
    }

    /**
     * A measured length after the correction.
     *
     * Lengths are one-dimensional, so the anchor is irrelevant here - this is the same `S`
     * that [apply] uses, and keeping both in this object is what stops the app from scaling
     * points in one place and printing metres in another.
     */
    fun scaledMetres(metres: Double, scale: Double): Double {
        if (!metres.isFinite() || !scale.isFinite()) return metres
        return metres * scale
    }

    /**
     * The factor as the operator reads it: three decimals, root locale.
     *
     * Root locale on purpose - the number is a ratio, not a measurement, and `1,000` on a German
     * phone inside a sentence that already says "factor" is the kind of mixed notation the
     * export layer avoids everywhere else.
     */
    fun formatScale(scale: Double): String = String.format(Locale.ROOT, "%.3f", scale)
}
