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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.constructar.app.BuildConfig
import com.constructar.app.ProjectViewModel
import com.constructar.app.ui.ar.ArMeasureScreen
import com.constructar.app.ui.theme.SiteColors

private object Routes {
    const val HOME = "home"
    const val MEASURE = "measure"
    const val EXPORT = "export"
}

/**
 * Single-activity navigation: three destinations, no deep hierarchy.
 *
 * The AR destination is deliberately *not* restored by the system after process death — an
 * ARCore session cannot be resurrected, and pretending otherwise would silently produce a run
 * with no anchors. The operator lands back on the project list instead, which is the honest
 * behaviour.
 */
@Composable
fun AppRoot(viewModel: ProjectViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navController: NavHostController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.lastError) {
        state.lastError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = navController, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        projects = state.projects,
                        loading = state.loading,
                        onOpen = { id ->
                            viewModel.openProject(id) { project ->
                                if (project != null) navController.navigate(Routes.MEASURE)
                            }
                        },
                        onCreate = { name ->
                            viewModel.createProject(name) { navController.navigate(Routes.MEASURE) }
                        },
                    )
                }

                composable(Routes.MEASURE) {
                    val project = state.openProject
                    if (project == null) {
                        Text(
                            "No project open",
                            modifier = Modifier.fillMaxSize(),
                            color = SiteColors.danger,
                        )
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        ArMeasureScreen(
                            project = project,
                            onBack = {
                                viewModel.closeProject()
                                navController.popBackStack(Routes.HOME, inclusive = false)
                            },
                            onRouteFinished = { route -> viewModel.upsertRoute(route) },
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
                            onBack = { navController.popBackStack() },
                        )
                    } else {
                        Text("No project open", modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
