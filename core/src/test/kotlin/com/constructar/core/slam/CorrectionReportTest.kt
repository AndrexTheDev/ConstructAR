/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.slam

import com.constructar.core.math.Mat4
import com.constructar.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far a confirmed closure moved the trajectory (finding 246).
 *
 * The optimiser hands back corrected poses by node index and the app used to drop them, so "the
 * closure moved the whole room by 20 cm" and "the graph was already consistent" printed the same
 * sentence. These tests pin the three ways that number can lie: counting a node whose original is
 * gone, counting a node twice through a broken matrix, and calling a rounding error a movement.
 */
class CorrectionReportTest {

    private fun poseAt(x: Float, y: Float = 0f, z: Float = 0f): FloatArray =
        Mat4.fromRotationTranslation(com.constructar.core.math.Quat.IDENTITY, Vec3(x, y, z)).elements.copyOf()

    @Test
    fun `a graph the optimiser did not move is not reported as moved`() {
        val originals = listOf(poseAt(0f), poseAt(1f), poseAt(2f))
        val report = CorrectionReport.of(originals) { index -> originals.getOrNull(index) }

        assertEquals(3, report.nodes)
        assertEquals(0, report.movedNodes)
        assertEquals(0f, report.maxShiftMetres, 1e-6f)
        assertFalse(report.moved)
    }

    @Test
    fun `the largest and the average shift are both carried`() {
        val originals = listOf(poseAt(0f), poseAt(1f), poseAt(2f))
        val optimised = listOf(poseAt(0f), poseAt(1.1f), poseAt(2.3f))
        val report = CorrectionReport.of(optimised) { index -> originals.getOrNull(index) }

        assertEquals(3, report.nodes)
        assertEquals("the first node stayed put, the other two moved", 2, report.movedNodes)
        assertEquals(0.3f, report.maxShiftMetres, 1e-4f)
        // (0 + 0.1 + 0.3) / 3 - the mean is what separates "one node jumped" from "everything drifted".
        assertEquals(0.1333f, report.meanShiftMetres, 1e-3f)
        assertTrue(report.moved)
    }

    @Test
    fun `a node whose original the app never had is skipped, not counted as zero`() {
        val originals = listOf(poseAt(0f), null, poseAt(2f))
        val optimised = listOf(poseAt(0f), poseAt(9f), poseAt(2.2f))
        val report = CorrectionReport.of(optimised) { index -> originals.getOrNull(index) }

        assertEquals("the middle node has no before, so it has no shift", 2, report.nodes)
        assertEquals("and cannot be counted as having moved", 1, report.movedNodes)
        assertEquals(0.2f, report.maxShiftMetres, 1e-4f)
    }

    @Test
    fun `a broken matrix is not a movement`() {
        val originals = listOf(poseAt(0f), FloatArray(16) { Float.NaN })
        val optimised = listOf(poseAt(0f), poseAt(5f))
        val report = CorrectionReport.of(optimised) { index -> originals.getOrNull(index) }

        assertEquals(1, report.nodes)
        assertEquals(0f, report.maxShiftMetres, 1e-6f)
    }

    @Test
    fun `a shift of a millimetre is rounding, not a movement`() {
        val originals = listOf(poseAt(0f))
        val optimised = listOf(poseAt(0.0005f))
        val report = CorrectionReport.of(optimised) { index -> originals.getOrNull(index) }

        assertFalse("a sub-millimetre shift must not read as a correction", report.moved)
        assertEquals(0.0005f, report.maxShiftMetres, 1e-6f)
    }

    @Test
    fun `no node in common is an empty report, not a failure`() {
        val report = CorrectionReport.of(listOf(poseAt(0f))) { null }
        assertEquals(0, report.nodes)
        assertFalse(report.moved)
    }

    @Test
    fun `one displaced node is one node, not the room`() {
        // Ten nodes, one of them five hundred millimetres out. The maximum cannot tell this apart
        // from ten nodes moving five hundred millimetres - the count can (finding 249).
        val originals = List(10) { poseAt(0f) }
        val optimised = originals.toMutableList().also { it[7] = poseAt(0.5f) }
        val report = CorrectionReport.of(optimised) { index -> originals.getOrNull(index) }

        assertEquals(10, report.nodes)
        assertEquals(1, report.movedNodes)
        assertEquals(0.5f, report.maxShiftMetres, 1e-4f)
        assertEquals(0.05f, report.meanShiftMetres, 1e-4f)
    }

    @Test
    fun `the count agrees with the threshold, not with the mean`() {
        // Shifts of 0.0005, 0.0015 and 0.2 m: two nodes are over the millimetre, and the mean
        // (0.067 m) says nothing about how many.
        val originals = listOf(poseAt(0f), poseAt(0f), poseAt(0f))
        val optimised = listOf(poseAt(0.0005f), poseAt(0.0015f), poseAt(0.2f))
        val report = CorrectionReport.of(optimised) { index -> originals.getOrNull(index) }

        assertEquals(3, report.nodes)
        assertEquals(2, report.movedNodes)
    }
}
