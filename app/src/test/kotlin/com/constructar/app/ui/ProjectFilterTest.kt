/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.app.data.ProjectRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the search field on the room list can find.
 *
 * The list knew three names for a job and searched two of them. The one it skipped is the one the
 * operator remembers - who the job is for - so typing "Schmidt" beside the customer's front door
 * answered "Nothing matches", and the row did not carry his name for the match to be seen
 * (finding 144).
 */
class ProjectFilterTest {

    private fun summary(
        name: String = "Wohnzimmer OG",
        siteName: String = "Hauptstraße 12",
        customerName: String = "Schmidt",
    ) = ProjectRepository.ProjectSummary(
        id = "p1",
        name = name,
        siteName = siteName,
        routeCount = 4,
        totalLengthMeters = 22.5f,
        updatedAtEpochMs = 0L,
        customerName = customerName,
    )

    @Test
    fun `a room is found by the name it was given`() {
        assertTrue(ProjectFilter.matches("wohnzimmer", summary()))
    }

    @Test
    fun `a room is found by its site`() {
        assertTrue(ProjectFilter.matches("Hauptstraße", summary()))
    }

    @Test
    fun `a room is found by the customer it is for`() {
        // Typed in a hurry, in the case the operator's thumb happens to produce.
        assertTrue(ProjectFilter.matches("schm", summary()))
        assertTrue(ProjectFilter.matches("SCHMIDT", summary()))
    }

    @Test
    fun `an empty field shows everything, and a stranger matches nothing`() {
        assertTrue(ProjectFilter.matches("", summary()))
        assertTrue(ProjectFilter.matches("   ", summary()))
        assertFalse(ProjectFilter.matches("Meier", summary()))
    }
}
