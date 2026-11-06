/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

/**
 * A caveat that belongs to a measurement, in the order a reader meets them (finding 254).
 *
 * Not a string: the screen and the protocol each have their own voice and their own language, and
 * a `:core` value that decided on wording would be a third one. What is decided here is *which*
 * caveats apply and in what order, so no screen can quietly forget one.
 */
enum class ScanCaveat {
    /** The volume ran out of blocks, so the model covers only part of the room (finding 100). */
    PARTIAL_CAPTURE,

    /** The operator confirmed a loop closure and the optimiser folded the trajectory (245–251). */
    FOLDED_LOOP,

    /** A closure was confirmed but the solver stopped, so the positions are uncorrected (247). */
    UNCORRECTED_LOOP,
}

/**
 * How this measurement came to be, in one place (finding 254).
 *
 * Everything here was already stored and already printed *somewhere*: the scan summary on the
 * protocol and the export screen, the calibration on the project screen, the closure on the export
 * screen since finding 251. What nobody had was one answer to "what should this screen say about
 * this measurement?", so each screen assembled its own - and the project screen, the one an
 * operator looks at before handing anything over, said nothing at all about a folded loop or a
 * partial capture.
 *
 * The rules are deliberately dumb and total: no thresholds, no wording, no optional chaining into
 * a screen. A caveat either applies to the stored record or it does not.
 */
data class MeasurementProvenance(
    /** True when this project has a scan behind it at all. */
    val scanned: Boolean,
    /** Triangles the mesh held, for the one line that reports the model. */
    val triangles: Int,
    /** The caveats that apply, in [ScanCaveat] order. */
    val caveats: List<ScanCaveat>,
    /** The operator's reference factor; `1.0` means none was applied. */
    val calibrationScale: Double,
    /** The last closure's numbers, when there is a plausible record to print them from. */
    val closure: ScanClosure?,
) {

    /**
     * Whether any printed metre is a corrected one, and therefore has to say so.
     *
     * A factor of zero, a negative one or a NaN is not a calibration: `:core` refuses to call it
     * one here rather than letting a screen print "×0,00" as if the operator had chosen it.
     */
    val isCalibrated: Boolean
        get() = calibrationScale.isFinite() && calibrationScale > 0.0 && calibrationScale != 1.0

    companion object {

        /**
         * Reads a stored project. The only judgement made here is the one from finding 253: a
         * closure record the optimiser could not have written is treated as absent, because a
         * screen may not print numbers the app cannot justify.
         */
        fun of(project: ProjectRecord): MeasurementProvenance {
            val scan = project.scan
            val closure = scan?.closures?.takeIf { it.closures > 0 && it.isPlausible }
            val caveats = buildList {
                if (scan?.scanLimited == true) add(ScanCaveat.PARTIAL_CAPTURE)
                if (closure != null) {
                    add(if (closure.converged) ScanCaveat.FOLDED_LOOP else ScanCaveat.UNCORRECTED_LOOP)
                }
            }
            return MeasurementProvenance(
                scanned = (scan?.meshTriangleCount ?: 0) > 0,
                triangles = scan?.meshTriangleCount ?: 0,
                caveats = caveats,
                calibrationScale = project.calibrationScale,
                closure = closure,
            )
        }
    }
}
