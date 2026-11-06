/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a screen has to say about a measurement (finding 254).
 *
 * The facts were all stored and all printed somewhere; the project screen - the one an operator
 * looks at before handing anything over - printed none of the loop facts and no partial-capture
 * warning. These tests pin the assembly, so a screen renders a list instead of deciding for itself
 * which caveats exist.
 */
class MeasurementProvenanceTest {

    private fun project(
        scan: ScanSummary? = null,
        calibrationScale: Double = 1.0,
    ): ProjectRecord = ProjectRecord(
        id = "p1",
        name = "Wohnzimmer",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        scan = scan,
        calibrationScale = calibrationScale,
    )

    @Test
    fun `a plain measured room carries no caveats`() {
        val provenance = MeasurementProvenance.of(project(ScanSummary(meshTriangleCount = 89000)))

        assertTrue(provenance.scanned)
        assertEquals(89000, provenance.triangles)
        assertEquals("nothing went wrong, so nothing has to be said", emptyList<ScanCaveat>(), provenance.caveats)
        assertNull("no closure was recorded", provenance.closure)
        assertFalse("an uncalibrated room is the normal case", provenance.isCalibrated)
    }

    @Test
    fun `a partial capture is a caveat, and so is a folded loop`() {
        val provenance = MeasurementProvenance.of(
            project(
                ScanSummary(
                    meshTriangleCount = 12000,
                    scanLimited = true,
                    closures = ScanClosure(closures = 1, movedNodes = 42, nodes = 380, maxShiftMetres = 0.183f),
                ),
            ),
        )

        assertEquals(
            "order matters: the capture limit explains the room before the correction explains the graph",
            listOf(ScanCaveat.PARTIAL_CAPTURE, ScanCaveat.FOLDED_LOOP),
            provenance.caveats,
        )
        assertEquals(42, provenance.closure?.movedNodes)
    }

    @Test
    fun `a closure without a result is its own caveat and carries no numbers`() {
        val provenance = MeasurementProvenance.of(
            project(ScanSummary(meshTriangleCount = 5000, closures = ScanClosure(closures = 1, converged = false))),
        )

        assertEquals(listOf(ScanCaveat.UNCORRECTED_LOOP), provenance.caveats)
        assertEquals("the record is kept, but it has nothing to print", 0, provenance.closure?.movedNodes)
    }

    @Test
    fun `a record the optimiser could not have written is not a caveat`() {
        // Finding 253, applied where the screen reads the file: 42 of 10 nodes is not a report, so
        // it is not a sentence either.
        val provenance = MeasurementProvenance.of(
            project(ScanSummary(meshTriangleCount = 5000, closures = ScanClosure(closures = 1, movedNodes = 42, nodes = 10))),
        )

        assertEquals(emptyList<ScanCaveat>(), provenance.caveats)
        assertNull(provenance.closure)
    }

    @Test
    fun `a project without a scan says so`() {
        val provenance = MeasurementProvenance.of(project())

        assertFalse(provenance.scanned)
        assertEquals(0, provenance.triangles)
        assertNull(provenance.closure)
    }

    @Test
    fun `only a real factor counts as a calibration`() {
        assertTrue(MeasurementProvenance.of(project(calibrationScale = 1.02)).isCalibrated)
        assertFalse("exactly one is no calibration", MeasurementProvenance.of(project(calibrationScale = 1.0)).isCalibrated)
        assertFalse("zero is not a factor a person chose", MeasurementProvenance.of(project(calibrationScale = 0.0)).isCalibrated)
        assertFalse("and neither is NaN", MeasurementProvenance.of(project(calibrationScale = Double.NaN)).isCalibrated)
    }
}
