/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app

import android.graphics.Bitmap
import com.constructar.core.licence.Entitlements
import com.constructar.app.data.ProjectRepository
import com.constructar.core.bom.MaterialList
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteDraft
import com.constructar.core.price.Pricing
import com.constructar.core.workflow.ProjectWorkflowEvent

/**
 * The single readable application state. Screens observe it; they never own a second copy of
 * project, licence or scan data. Writes enter [ProjectViewModel] as intents and return as a new
 * StateFlow value, so rendering always follows the same Model -> View -> Intent direction.
 */
data class AppState(
    val loading: Boolean = true,
    val projects: List<ProjectRepository.ProjectSummary> = emptyList(),
    val unreadableProjects: Int = 0,
    val unreadableFiles: List<String> = emptyList(),
    val openProject: ProjectRecord? = null,
    val materialList: MaterialList? = null,
    val lastError: UiMessage? = null,
    val entitlements: Entitlements = Entitlements.FREE,
    val deviceTag: String = "",
    val trialUsed: Boolean = false,
    val trialDaysLeft: Long = 0L,
    val trialAvailable: Boolean = true,
    val demoSigningKey: Boolean = false,
    val clockRolledBack: Boolean = false,
    val integrityFailed: Boolean = false,
    val localStateTampered: Boolean = false,
    val onboardingDone: Boolean = false,
    val wasteOverride: Float? = null,
    val clipSpacingOverride: Float? = null,
    val photo: Bitmap? = null,
    val firmHourlyRateCents: Int = Pricing.DEFAULT_HOURLY_RATE_CENTS,
    val fieldPhotos: Map<String, Bitmap> = emptyMap(),
    val pendingDraft: RouteDraft? = null,
    val resumeReady: Boolean = false,
)

/** Intents whose effect must update the durable workflow and the central state together. */
sealed interface AppIntent {
    data class Workflow(val projectId: String, val event: ProjectWorkflowEvent) : AppIntent
    data class ExportCommitted(val projectId: String, val hasSignature: Boolean, val atEpochMs: Long) : AppIntent
    data object Refresh : AppIntent
}
