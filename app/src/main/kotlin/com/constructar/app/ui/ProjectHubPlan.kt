/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.workflow.ProjectWorkflowState
import com.constructar.core.workflow.WorkflowAction

/** Every way out of the hub, including the one a system Back gesture takes. */
internal enum class ProjectExit { BACK, MEASURE, EXPORT, MATERIALS, WALLS, FIELD_BOOK }

/**
 * Presentation rules for the project hub. Measurement and workflow decisions stay in `:core`;
 * this only prevents the screen from saying the same next step twice or claiming an early export
 * finished the complete workflow.
 */
internal object ProjectHubPlan {

    sealed interface ExitDecision {
        val destination: ProjectExit

        data class Navigate(override val destination: ProjectExit) : ExitDecision
        data class Confirm(override val destination: ProjectExit) : ExitDecision
    }

    /** Dirty drafts ask first, whichever button or gesture requested the exit. */
    fun planExit(destination: ProjectExit, hasUnsavedEdits: Boolean): ExitDecision =
        if (hasUnsavedEdits) ExitDecision.Confirm(destination) else ExitDecision.Navigate(destination)

    /** Keep every destination, except the one already offered as the primary next action. */
    fun showsSecondaryDestination(next: WorkflowAction, destination: WorkflowAction): Boolean =
        next != destination

    /** Only the reconciled terminal state means the full job reached export, not an early PDF. */
    fun exportCompleted(state: ProjectWorkflowState): Boolean =
        state == ProjectWorkflowState.EXPORTED
}
