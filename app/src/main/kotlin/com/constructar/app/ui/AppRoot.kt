/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.constructar.app.BuildConfig
import com.constructar.app.ProjectViewModel
import com.constructar.app.R
import com.constructar.app.UiMessage
import com.constructar.app.ui.ar.ArMeasureScreen
import com.constructar.app.ui.theme.SiteColors

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val PROJECT = "project"
    const val MEASURE = "measure"
    const val EXPORT = "export"
    const val MATERIALS = "materials"
    const val PAYWALL = "paywall"
    const val HELP = "help"
}

/**
 * Single-activity navigation.
 *
 * The hierarchy mirrors the job: **project → measure → export**, with the licence and help
 * screens hanging off the edge where they cannot interrupt a measurement. Every screen except
 * the AR viewfinder is restorable, and the AR destination is deliberately *not* — an ARCore
 * session cannot be resurrected after process death, so the operator lands back on the project
 * screen instead of on a black viewfinder with no anchors.
 */
@Composable
fun AppRoot(viewModel: ProjectViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val defaultRoomName = stringResource(R.string.home_default_room_name)
    val navController: NavHostController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = when (state.lastError) {
        UiMessage.PROJECT_OPEN_FAILED -> stringResource(R.string.error_project_open)
        UiMessage.PROJECT_SAVE_FAILED -> stringResource(R.string.error_project_save)
        UiMessage.PROJECT_LIMIT_REACHED -> stringResource(R.string.home_limit_title)
        null -> null
    }
    LaunchedEffect(state.lastError) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = if (state.onboardingDone) Routes.HOME else Routes.ONBOARDING,
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onDone = {
                            viewModel.completeOnboarding()
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.HOME) {
                    HomeScreen(
                        projects = state.projects,
                        loading = state.loading,
                        entitlements = state.entitlements,
                        trialDaysLeft = state.trialDaysLeft,
                        onOpen = { id ->
                            viewModel.openProject(id) { project ->
                                if (project != null) navController.navigate(Routes.PROJECT)
                            }
                        },
                        onExport = { id ->
                            viewModel.openProject(id) { project ->
                                if (project != null) navController.navigate(Routes.EXPORT)
                            }
                        },
                        onCreate = { name ->
                            // Blank names fall back to a localised room name rather than the
                            // repository's English default.
                            val safeName = name.ifBlank { defaultRoomName }
                            viewModel.createProject(safeName) { navController.navigate(Routes.PROJECT) }
                        },
                        onUpgrade = { navController.navigate(Routes.PAYWALL) },
                        onHelp = { navController.navigate(Routes.HELP) },
                    )
                }

                composable(Routes.PROJECT) {
                    val project = state.openProject
                    if (project == null) {
                        Text(
                            stringResource(R.string.project_none),
                            modifier = Modifier.fillMaxSize(),
                            color = SiteColors.danger,
                        )
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        ProjectScreen(
                            project = project,
                            materialList = state.materialList,
                            onBack = {
                                viewModel.closeProject()
                                navController.popBackStack(Routes.HOME, inclusive = false)
                            },
                            onMeasure = { navController.navigate(Routes.MEASURE) },
                            onExport = { navController.navigate(Routes.EXPORT) },
                            onRename = { viewModel.renameProject(it) },
                            onSaveNotes = { viewModel.updateNotes(it) },
                            onSaveTechnician = { viewModel.updateTechnician(it) },
                            onRemoveRoute = { routeId -> viewModel.removeRoute(routeId) },
                            onMaterials = { navController.navigate(Routes.MATERIALS) },
                            photo = state.photo,
                            onPhoto = { uri -> viewModel.attachPhoto(uri) },
                            onDelete = {
                                viewModel.deleteProject(project.id) {
                                    navController.popBackStack(Routes.HOME, inclusive = false)
                                }
                            },
                        )
                    }
                }

                composable(Routes.MEASURE) {
                    val project = state.openProject
                    if (project == null) {
                        Text(
                            stringResource(R.string.project_none),
                            modifier = Modifier.fillMaxSize(),
                            color = SiteColors.danger,
                        )
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        ArMeasureScreen(
                            project = project,
                            onBack = { navController.popBackStack() },
                            onRouteFinished = { route -> viewModel.upsertRoute(route) },
                            onScanSummary = { summary -> viewModel.updateScanSummary(summary) },
                            onOpenExport = { navController.navigate(Routes.EXPORT) },
                        )
                    }
                }

                composable(Routes.EXPORT) {
                    val project = state.openProject
                    if (project != null) {
                        ExportScreen(
                            project = project,
                            materialList = state.materialList,
                            appVersion = BuildConfig.VERSION_NAME,
                            operatorName = project.technician,
                            entitlements = state.entitlements,
                            watermark = stringResource(R.string.pdf_watermark),
                            photo = state.photo,
                            onBack = { navController.popBackStack() },
                            onUpgrade = { navController.navigate(Routes.PAYWALL) },
                        )
                    } else {
                        Text(stringResource(R.string.project_none), modifier = Modifier.fillMaxSize())
                    }
                }

                composable(Routes.MATERIALS) {
                    val project = state.openProject
                    if (project != null) {
                        MaterialsScreen(
                            projectName = project.name,
                            materialList = state.materialList,
                            wasteOverride = state.wasteOverride,
                            onWasteChange = { viewModel.setWasteOverride(it) },
                            onBack = { navController.popBackStack() },
                        )
                    } else {
                        Text(stringResource(R.string.project_none), modifier = Modifier.fillMaxSize())
                    }
                }

                composable(Routes.PAYWALL) {
                    PaywallScreen(
                        entitlements = state.entitlements,
                        deviceTag = state.deviceTag,
                        trialUsed = state.trialUsed,
                        trialDaysLeft = state.trialDaysLeft,
                        onBack = { navController.popBackStack() },
                        onActivate = { key -> viewModel.activateLicence(key) },
                        onStartTrial = {
                            viewModel.startTrial()
                            navController.popBackStack()
                        },
                    )
                }

                composable(Routes.HELP) {
                    HelpScreen(
                        entitlements = state.entitlements,
                        trialDaysLeft = state.trialDaysLeft,
                        deviceTag = state.deviceTag,
                        repository = viewModel.repository(),
                        onBack = { navController.popBackStack() },
                        onUpgrade = { navController.navigate(Routes.PAYWALL) },
                        onRestore = { texts, photos -> viewModel.importBackup(texts, photos) },
                    )
                }
            }
        }
    }
}
