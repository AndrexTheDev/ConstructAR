/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.workflow

import com.constructar.core.model.ProjectRecord
import kotlinx.serialization.Serializable

/** The durable milestones of one room, in the order the field workflow owns them. */
@Serializable
enum class ProjectWorkflowState {
    IDLE,
    PROJECT_ACTIVE,
    SPATIAL_MAPPED,
    TRASSE_MEASURED,
    PERIMETER_CLOSED,
    BOM_CALCULATED,
    DOSSIER_COMPLETE,
    EXPORTED,
}

/** Events are intents, not screen navigation: the same event can arrive from AR, restore or a test. */
sealed interface ProjectWorkflowEvent {
    data object ProjectActivated : ProjectWorkflowEvent
    data object SpatialMapped : ProjectWorkflowEvent
    data object RouteMeasured : ProjectWorkflowEvent
    data object PerimeterClosed : ProjectWorkflowEvent
    data class BomCalculated(val atEpochMs: Long) : ProjectWorkflowEvent
    data class FieldBookCompleted(val atEpochMs: Long) : ProjectWorkflowEvent
    data class SignatureCaptured(val atEpochMs: Long) : ProjectWorkflowEvent
    data class ExportCompleted(val atEpochMs: Long) : ProjectWorkflowEvent
}

/** The one primary action the project hub offers for a durable milestone. */
enum class WorkflowAction {
    NONE,
    CAPTURE_ROOM,
    MEASURE_ROUTE,
    CLOSE_PERIMETER,
    REVIEW_BOM,
    COMPLETE_DOSSIER,
    EXPORT,
}

/**
 * Unidirectional project workflow reducer.
 *
 * The seven forward transitions are explicit, but the durable state is also *reconciled* with the
 * assets on every write. That second rule matters on a phone: deleting the last route, restoring an
 * older JSON file, or removing a scan must not leave a screen saying EXPORTED for a dossier whose
 * inputs no longer exist. An export remains possible at every stage (paperwork can be useful before
 * a room is complete); it only reaches EXPORTED when the complete chain has actually happened.
 */
object ProjectWorkflowEngine {

    private val forward = mapOf(
        ProjectWorkflowState.IDLE to ProjectWorkflowState.PROJECT_ACTIVE,
        ProjectWorkflowState.PROJECT_ACTIVE to ProjectWorkflowState.SPATIAL_MAPPED,
        ProjectWorkflowState.SPATIAL_MAPPED to ProjectWorkflowState.TRASSE_MEASURED,
        ProjectWorkflowState.TRASSE_MEASURED to ProjectWorkflowState.PERIMETER_CLOSED,
        ProjectWorkflowState.PERIMETER_CLOSED to ProjectWorkflowState.BOM_CALCULATED,
        ProjectWorkflowState.BOM_CALCULATED to ProjectWorkflowState.DOSSIER_COMPLETE,
        ProjectWorkflowState.DOSSIER_COMPLETE to ProjectWorkflowState.EXPORTED,
    )

    fun canTransition(from: ProjectWorkflowState, to: ProjectWorkflowState): Boolean =
        from == to || forward[from] == to

    fun reduce(project: ProjectRecord, event: ProjectWorkflowEvent): ProjectRecord {
        val stamped = when (event) {
            ProjectWorkflowEvent.ProjectActivated -> project
            ProjectWorkflowEvent.SpatialMapped -> project
            ProjectWorkflowEvent.RouteMeasured -> project
            ProjectWorkflowEvent.PerimeterClosed -> project
            is ProjectWorkflowEvent.BomCalculated -> project.copy(bomCalculatedAtEpochMs = event.atEpochMs)
            is ProjectWorkflowEvent.FieldBookCompleted -> project.copy(fieldBookCompletedAtEpochMs = event.atEpochMs)
            is ProjectWorkflowEvent.SignatureCaptured -> project.copy(signatureCapturedAtEpochMs = event.atEpochMs)
            is ProjectWorkflowEvent.ExportCompleted -> project.copy(lastExportAtEpochMs = event.atEpochMs)
        }
        return reconcile(stamped, activate = event == ProjectWorkflowEvent.ProjectActivated)
    }

    /**
     * Recomputes the furthest state supported by the current project assets and event timestamps.
     * [activate] is the explicit IDLE -> PROJECT_ACTIVE edge used when a room is created or opened.
     */
    fun reconcile(project: ProjectRecord, activate: Boolean = false): ProjectRecord {
        if (project.workflow == ProjectWorkflowState.IDLE && !activate) return project
        var state = ProjectWorkflowState.PROJECT_ACTIVE
        val mapped = (project.scan?.meshTriangleCount ?: 0) > 0 ||
            project.scan?.bounds != null ||
            !project.sketch?.points.isNullOrEmpty()
        if (!mapped) return project.copy(workflow = state)
        state = ProjectWorkflowState.SPATIAL_MAPPED
        if (project.routes.none { it.isRoutable }) return project.copy(workflow = state)
        state = ProjectWorkflowState.TRASSE_MEASURED
        if (project.sketch?.isDrawable != true) return project.copy(workflow = state)
        state = ProjectWorkflowState.PERIMETER_CLOSED
        if (project.bomCalculatedAtEpochMs == null) return project.copy(workflow = state)
        state = ProjectWorkflowState.BOM_CALCULATED
        if (project.fieldBookCompletedAtEpochMs == null || project.signatureCapturedAtEpochMs == null) {
            return project.copy(workflow = state)
        }
        state = ProjectWorkflowState.DOSSIER_COMPLETE
        if (project.lastExportAtEpochMs == null) return project.copy(workflow = state)
        return project.copy(workflow = ProjectWorkflowState.EXPORTED)
    }

    fun nextAction(state: ProjectWorkflowState): WorkflowAction = when (state) {
        ProjectWorkflowState.IDLE -> WorkflowAction.NONE
        ProjectWorkflowState.PROJECT_ACTIVE -> WorkflowAction.CAPTURE_ROOM
        ProjectWorkflowState.SPATIAL_MAPPED -> WorkflowAction.MEASURE_ROUTE
        ProjectWorkflowState.TRASSE_MEASURED -> WorkflowAction.CLOSE_PERIMETER
        ProjectWorkflowState.PERIMETER_CLOSED -> WorkflowAction.REVIEW_BOM
        ProjectWorkflowState.BOM_CALCULATED -> WorkflowAction.COMPLETE_DOSSIER
        ProjectWorkflowState.DOSSIER_COMPLETE,
        ProjectWorkflowState.EXPORTED,
        -> WorkflowAction.EXPORT
    }
}
