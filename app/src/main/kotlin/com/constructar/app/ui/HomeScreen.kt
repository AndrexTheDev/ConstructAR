/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.data.ProjectRepository
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.licence.Entitlements
import com.constructar.core.units.LengthFormatter

/**
 * The home screen: one job — get the operator back to work in one tap.
 *
 * The order of the screen is the order of the day: *what I was doing* first (the newest room,
 * with a single "continue" button), then everything else, then the one action that starts
 * something new. A crew that has just walked into a basement should not have to read a menu.
 */
@Composable
fun HomeScreen(
    projects: List<ProjectRepository.ProjectSummary>,
    loading: Boolean,
    entitlements: Entitlements,
    trialDaysLeft: Long,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit,
    onCreate: (String) -> Unit,
    onUpgrade: () -> Unit,
    onHelp: () -> Unit,
    /**
     * Project files in the app's folder that could not be read. They are kept - nothing here
     * deletes a room it does not understand - so the list has to say they exist, or a damaged
     * project looks exactly like a deleted one.
     */
    unreadableProjects: Int = 0,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val canCreate = entitlements.allowsAnotherProject(projects.size)
    val mostRecent = projects.firstOrNull()
    // Searching pays for itself from a handful of rooms on; with two it is just another field
    // between the operator and the thing they tapped the app for.
    val needle = query.trim()
    val filtered = if (needle.isEmpty()) {
        projects
    } else {
        projects.filter { it.name.contains(needle, ignoreCase = true) || it.siteName.contains(needle, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(
            entitlements = entitlements,
            trialDaysLeft = trialDaysLeft,
            onUpgrade = onUpgrade,
            onHelp = onHelp,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (unreadableProjects > 0) {
                item {
                    Text(
                        text = pluralStringResource(
                            R.plurals.home_unreadable,
                            unreadableProjects,
                            unreadableProjects,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SiteColors.warning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (loading) {
                item { Spacer(Modifier.height(24.dp)) }
            } else if (mostRecent != null) {
                item {
                    ContinueCard(
                        project = mostRecent,
                        onContinue = { onOpen(mostRecent.id) },
                        onExport = { onExport(mostRecent.id) },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.home_all_projects),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                if (projects.size >= 5) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.home_search_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.home_search_empty, needle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }
                } else {
                    items(filtered, key = { it.id }) { project ->
                        ProjectRow(project = project, onClick = { onOpen(project.id) })
                    }
                }
            } else {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.home_empty), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.home_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (!canCreate) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Jamaica.SurfaceHigh),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onUpgrade),
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = SiteColors.gold)
                                Text(
                                    stringResource(R.string.home_limit_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            Text(
                                stringResource(R.string.home_limit_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        GloveButton(
            label = stringResource(if (canCreate) R.string.home_new_project else R.string.home_upgrade),
            icon = if (canCreate) Icons.Default.Add else Icons.Default.Star,
            onClick = { if (canCreate) showCreateDialog = true else onUpgrade() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        Text(
            text = stringResource(R.string.home_offline_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            textAlign = TextAlign.Center,
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.home_new_project)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.home_room_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCreateDialog = false
                        onCreate(newName)
                        newName = ""
                    },
                ) { Text(stringResource(R.string.home_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.home_cancel)) }
            },
        )
    }
}

@Composable
private fun Header(
    entitlements: Entitlements,
    trialDaysLeft: Long,
    onUpgrade: () -> Unit,
    onHelp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.constructar_logo),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .padding(end = 2.dp),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlanChip(entitlements = entitlements, trialDaysLeft = trialDaysLeft, onClick = onUpgrade)
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        IconButton(onClick = onHelp, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = stringResource(R.string.home_help),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The plan, as a chip the operator can tap to see what Pro would add. */
@Composable
fun PlanChip(entitlements: Entitlements, trialDaysLeft: Long, onClick: () -> Unit) {
    val (label, tint) = when {
        entitlements.isTrial -> stringResource(R.string.plan_trial_days, trialDaysLeft) to SiteColors.accent
        entitlements.isPro -> stringResource(R.string.plan_pro) to SiteColors.accent
        else -> stringResource(R.string.plan_free) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (entitlements.isPro) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = SiteColors.gold,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(start = if (entitlements.isPro) 4.dp else 0.dp),
        )
    }
}

@Composable
private fun ContinueCard(
    project: ProjectRepository.ProjectSummary,
    onContinue: () -> Unit,
    onExport: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Jamaica.SurfaceHigh),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.home_continue),
                style = MaterialTheme.typography.labelMedium,
                color = SiteColors.accent,
            )
            Text(
                project.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProjectMetaLine(project)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                GloveButton(
                    label = stringResource(R.string.project_measure),
                    icon = Icons.Default.PlayArrow,
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onExport, modifier = Modifier.size(72.dp)) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.home_export),
                        tint = SiteColors.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: ProjectRepository.ProjectSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Cable, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                project.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProjectMetaLine(project)
        }
        Text(
            LengthFormatter.format(project.totalLengthMeters),
            style = MaterialTheme.typography.titleMedium,
            color = SiteColors.gold,
        )
    }
}

@Composable
private fun ProjectMetaLine(project: ProjectRepository.ProjectSummary) {
    // Gregorian and identical in all six languages: see `formatDayLabel`.
    val updatedLabel = remember(project.updatedAtEpochMs) { formatDayLabel(project.updatedAtEpochMs) }
    // Resolved at composition: `pluralStringResource` is @Composable and must not be called
    // from inside `buildString`.
    val runsText = pluralStringResource(R.plurals.home_runs, project.routeCount, project.routeCount)
    val runningText = stringResource(R.string.field_time_open)
    val defectsText = if (project.openDefects > 0) {
        stringResource(R.string.field_open_count, project.openDefects)
    } else {
        ""
    }
    Text(
        text = buildString {
            append(runsText)
            append(" · ")
            append(LengthFormatter.format(project.totalLengthMeters))
            append(" · ")
            append(updatedLabel)
            if (project.siteName.isNotBlank()) {
                append(" · ")
                append(project.siteName)
            }
            if (project.workRunning) {
                append(" · ")
                append(runningText)
            }
            if (defectsText.isNotEmpty()) {
                append(" · ")
                append(defectsText)
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
