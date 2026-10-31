/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.constructar.app.data.ProjectRepository
import com.constructar.core.bom.MaterialList
import com.constructar.core.bom.MaterialListBuilder
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ProjectRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-level state: which project is open, and everything derived from it.
 *
 * Deliberately kept free of ARCore: the AR screen owns a controller with its own lifecycle,
 * and this view model only persists what the operator decides to keep. That separation is
 * what allows the export screen (and the project list) to work with the camera closed.
 */
/**
 * A failure the operator must see, as a *code*.
 *
 * The view model holds no Context and produces no sentences: [AppRoot] turns these into
 * `stringResource` lookups, so the snackbar is localised like the rest of the UI.
 */
enum class UiMessage { PROJECT_OPEN_FAILED, PROJECT_SAVE_FAILED }

class ProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)

    data class UiState(
        val loading: Boolean = true,
        val projects: List<ProjectRepository.ProjectSummary> = emptyList(),
        val openProject: ProjectRecord? = null,
        val materialList: MaterialList? = null,
        val lastError: UiMessage? = null,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projects = repository.list()
            mutableState.value = mutableState.value.copy(loading = false, projects = projects)
        }
    }

    fun createProject(name: String, siteName: String = "", onCreated: (ProjectRecord) -> Unit) {
        viewModelScope.launch {
            val project = repository.create(name, siteName)
            mutableState.value = mutableState.value.copy(openProject = project, lastError = null)
            refresh()
            onCreated(project)
        }
    }

    fun openProject(id: String, onOpened: (ProjectRecord?) -> Unit = {}) {
        viewModelScope.launch {
            val project = repository.load(id)
            if (project == null) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_OPEN_FAILED)
                onOpened(null)
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                openProject = project,
                materialList = MaterialListBuilder.build(project),
                lastError = null,
            )
            onOpened(project)
        }
    }

    /** Adds or replaces a run and persists the project. */
    fun upsertRoute(route: ConduitRoute, onSaved: (ProjectRecord?) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        val updated = project.upsertRoute(route)
        mutate(updated, onSaved)
    }

    fun removeRoute(routeId: String, onSaved: (ProjectRecord?) -> Unit = {}) {
        val project = mutableState.value.openProject ?: return
        mutate(project.removeRoute(routeId), onSaved)
    }

    /**
     * Persists the scan statistics (triangle count, extents, coverage) into the open project.
     * Called when a run is finished, so the protocol describes the room as it was scanned
     * rather than whatever the last frame happened to contain.
     */
    fun updateScanSummary(summary: com.constructar.core.model.ScanSummary) {
        val project = mutableState.value.openProject ?: return
        val updated = project.copy(scan = summary)
        mutableState.value = mutableState.value.copy(openProject = updated)
        viewModelScope.launch {
            repository.updateScanSummary(updated.id, summary)
            refresh()
        }
    }

    fun closeProject() {
        mutableState.value = mutableState.value.copy(openProject = null, materialList = null)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(lastError = null)
    }

    fun repository(): ProjectRepository = repository

    private fun mutate(updated: ProjectRecord, onSaved: (ProjectRecord?) -> Unit) {
        mutableState.value = mutableState.value.copy(
            openProject = updated,
            materialList = MaterialListBuilder.build(updated),
        )
        viewModelScope.launch {
            val saved = repository.save(updated)
            if (!saved) {
                mutableState.value = mutableState.value.copy(lastError = UiMessage.PROJECT_SAVE_FAILED)
            }
            refresh()
            onSaved(if (saved) updated else null)
        }
    }
}
