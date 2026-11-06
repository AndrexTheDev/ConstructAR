/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

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
            listOf("meshTriangleCount", "scanLimited", "closures"),
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
}
