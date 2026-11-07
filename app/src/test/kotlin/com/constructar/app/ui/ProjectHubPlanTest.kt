/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.workflow.ProjectWorkflowState
import com.constructar.core.workflow.WorkflowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectHubPlanTest {

    @Test
    fun `every exit keeps its destination and asks exactly when drafts are dirty`() {
        ProjectExit.entries.forEach { destination ->
            assertEquals(
                ProjectHubPlan.ExitDecision.Confirm(destination),
                ProjectHubPlan.planExit(destination, hasUnsavedEdits = true),
            )
            assertEquals(
                ProjectHubPlan.ExitDecision.Navigate(destination),
                ProjectHubPlan.planExit(destination, hasUnsavedEdits = false),
            )
        }
    }

    @Test
    fun `only the primary destination is removed from secondary navigation`() {
        val destinations = listOf(
            WorkflowAction.EXPORT,
            WorkflowAction.REVIEW_BOM,
            WorkflowAction.CLOSE_PERIMETER,
            WorkflowAction.COMPLETE_DOSSIER,
        )

        WorkflowAction.entries.forEach { next ->
            destinations.forEach { destination ->
                if (next == destination) {
                    assertFalse(
                        "$next appeared twice",
                        ProjectHubPlan.showsSecondaryDestination(next, destination),
                    )
                } else {
                    assertTrue(
                        "$destination must remain available while next is $next",
                        ProjectHubPlan.showsSecondaryDestination(next, destination),
                    )
                }
            }
        }
    }

    @Test
    fun `export progress follows durable completion rather than an early export visit`() {
        ProjectWorkflowState.entries.forEach { state ->
            val completed = ProjectHubPlan.exportCompleted(state)
            if (state == ProjectWorkflowState.EXPORTED) {
                assertTrue(state.name, completed)
            } else {
                assertFalse(state.name, completed)
            }
        }
    }
}
