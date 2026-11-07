/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.Aabb
import com.constructar.core.math.Vec3
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan summary as it is persisted - in particular the flag that says the capture is partial.
 *
 * The volume's block budget is spent on a large room, the mesh stops growing, and the protocol
 * has to say so (finding 100). That only works if the flag survives the file: the summary is
 * written into the project JSON when a run is finished and read back after a cold start, a
 * backup or a restore. Two things are pinned here - a file written before the field existed
 * still loads, and a limited capture survives a save and a load.
 */
class ScanSummaryTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `the summary carries only what the app measures`() {
        val descriptor = ScanSummary.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }

        assertEquals(
            "a field here becomes a line in every project file and a possible row in the " +
                "protocol; it has to arrive with a producer and a reader (finding 103)",
            listOf("meshTriangleCount", "scanLimited", "closures", "bounds"),
            names,
        )
    }

    @Test
    fun `a summary written before the flag existed loads as a whole room`() {
        val legacy = Json { ignoreUnknownKeys = true }

        val summary = legacy.decodeFromString(ScanSummary.serializer(), """{"meshTriangleCount":89000}""")

        assertEquals(89000, summary.meshTriangleCount)
        assertFalse(
            "a project file from before the field must not be read as a partial capture",
            summary.scanLimited,
        )
    }

    @Test
    fun `a limited capture survives a save and a load`() {
        val saved = json.encodeToString(
            ScanSummary.serializer(),
            ScanSummary(meshTriangleCount = 89000, scanLimited = true),
        )
        val loaded = json.decodeFromString(ScanSummary.serializer(), saved)

        assertTrue("the warning must come back with the room: $saved", loaded.scanLimited)
        assertTrue("the flag must be in the file, not only in memory: $saved", saved.contains("scanLimited"))
    }

    @Test
    fun `a summary written before closures existed has none, not zero of them`() {
        // Finding 103 removed an "accepted loop closures" counter because nothing filled it and a
        // stored 0 read as a checked room. It comes back only as a value that can be absent
        // (finding 251): the old file must load with no record, and nothing may be invented.
        val legacy = Json { ignoreUnknownKeys = true }

        val summary = legacy.decodeFromString(
            ScanSummary.serializer(),
            """{"meshTriangleCount":89000,"scanLimited":false}""",
        )

        assertEquals(89000, summary.meshTriangleCount)
        assertNull("no closure was ever recorded, so none may be reported", summary.closures)
    }

    @Test
    fun `a folded-back room carries the numbers that folded it`() {
        val saved = json.encodeToString(
            ScanSummary.serializer(),
            ScanSummary(
                meshTriangleCount = 89000,
                closures = ScanClosure(
                    closures = 1,
                    movedNodes = 42,
                    nodes = 380,
                    maxShiftMetres = 0.183f,
                ),
            ),
        )
        val loaded = json.decodeFromString(ScanSummary.serializer(), saved)

        val closure = loaded.closures
        assertTrue("the record must be in the file, not only in memory: $saved", saved.contains("maxShiftMetres"))
        assertEquals(1, closure?.closures)
        assertEquals(42, closure?.movedNodes)
        assertEquals(380, closure?.nodes)
        assertEquals(0.183f, closure?.maxShiftMetres ?: 0f, 1e-4f)
    }

    @Test
    fun `a closure whose solver stopped keeps saying so in the file`() {
        // The numbers of a half-descent are not a correction, and a file that stored only zeros
        // would tell a later reader the graph was already consistent (findings 247, 252).
        val saved = json.encodeToString(
            ScanSummary.serializer(),
            ScanSummary(
                meshTriangleCount = 12000,
                closures = ScanClosure(closures = 1, converged = false),
            ),
        )
        val loaded = json.decodeFromString(ScanSummary.serializer(), saved)

        assertEquals(false, loaded.closures?.converged)
        assertTrue("the verdict has to be in the file: $saved", saved.contains("\"converged\": false"))
    }

    @Test
    fun `a record the optimiser could have produced is plausible`() {
        val sane = ScanClosure(closures = 2, movedNodes = 42, nodes = 380, maxShiftMetres = 0.183f)

        assertTrue("a normal report must pass its own arithmetic", sane.isPlausible)
    }

    @Test
    fun `more moved nodes than compared ones is not a report`() {
        // A project file can be restored from a backup and is text in between: nothing stops
        // a record saying "moved 42 of 10" from arriving here (finding 253).
        val broken = ScanClosure(closures = 1, movedNodes = 42, nodes = 10, maxShiftMetres = 0.1f)

        assertFalse("42 of 10 nodes cannot happen", broken.isPlausible)
    }

    @Test
    fun `a distance that is not a distance is not a report`() {
        assertFalse(
            "a NaN shift must never reach a protocol",
            ScanClosure(closures = 1, movedNodes = 1, nodes = 10, maxShiftMetres = Float.NaN).isPlausible,
        )
        assertFalse(
            "a negative shift is not a movement",
            ScanClosure(closures = 1, movedNodes = 1, nodes = 10, maxShiftMetres = -0.2f).isPlausible,
        )
        assertFalse(
            "a negative count is not a count",
            ScanClosure(closures = -1).isPlausible,
        )
    }

    @Test
    fun `a run without a result may not carry numbers`() {
        // Finding 252: a non-converged run has no report, so a record that says otherwise was
        // written by something other than the pipeline.
        val contradictory = ScanClosure(
            closures = 1,
            movedNodes = 5,
            nodes = 380,
            maxShiftMetres = 0.02f,
            converged = false,
        )
        val honest = ScanClosure(closures = 1, converged = false)

        assertFalse("numbers from a solver that gave up are a contradiction", contradictory.isPlausible)
        assertTrue("the honest record of an unfinished run is a confirmation and nothing else", honest.isPlausible)
    }

    @Test
    fun `independent mesh bounds survive a project file round trip`() {
        val bounds = Aabb(Vec3(-1f, 0f, -2f), Vec3(4f, 2.7f, 5f))
        val saved = json.encodeToString(
            ScanSummary.serializer(),
            ScanSummary(meshTriangleCount = 24, bounds = bounds),
        )
        val loaded = json.decodeFromString(ScanSummary.serializer(), saved)
        assertEquals(bounds, loaded.bounds)
        assertTrue(saved.contains("bounds"))
    }

}
