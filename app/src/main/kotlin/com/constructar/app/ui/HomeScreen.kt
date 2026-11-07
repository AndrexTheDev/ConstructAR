/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.constructar.app.data.RoomSizeText
import com.constructar.app.data.RoomThumbnail
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.licence.Entitlements
import com.constructar.core.model.FloorplanSketch

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
     * Closes the open ring of a room by hand, by project id.
     *
     * A scan that never closed its ring (a doorway, a glass wall) leaves the card with an icon and
     * no size. The corners are stored, so the operator can say "this gap is a wall" instead of
     * re-walking the room - and the sketch records that the closing wall was drawn, not measured.
     */
    onCloseRoom: (String) -> Unit = {},
    /**
     * Project files in the app's folder that could not be read. They are kept - nothing here
     * deletes a room it does not understand - so the list has to say they exist, or a damaged
     * project looks exactly like a deleted one.
     */
    unreadableProjects: Int = 0,
    /**
     * The same files by name, so each one can be deleted.
     *
     * A count told the operator that his data was still on the phone and gave him nothing to do
     * about it: the file takes the free room (finding 126), the create gate counts it (finding
     * 149) and no other screen can show it. Deleting is the one action that frees the slot, and
     * it is offered here, next to the sentence that says the file was kept (finding 150).
     */
    unreadableFiles: List<String> = emptyList(),
    onDeleteUnreadable: (String) -> Unit = {},
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    // The file the operator has asked to delete, pending the question that names it.
    var damagedToDelete by rememberSaveable { mutableStateOf("") }
    // A file this build cannot read is still a room on the phone: it occupies the one free
    // slot just like a healthy one (finding 126). Asked through `ProjectSlots` because the gate
    // behind this button has to answer with the same count (finding 149).
    val canCreate = ProjectSlots.allowsAnother(
        entitlements = entitlements,
        readable = projects.size,
        unreadable = unreadableProjects,
    )
    val mostRecent = projects.firstOrNull()
    // Searching pays for itself from a handful of rooms on; with two it is just another field
    // between the operator and the thing they tapped the app for.
    val needle = query.trim()
    // Name, site **and customer**: he remembers who the job was for, not what he called the room
    // (finding 144).
    val filtered = ProjectFilter.listRows(projects, needle)

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
            // First, because "it crashes constantly" cannot be answered by a file three taps away
            // on the help screen (finding 264). Draws nothing when there is no crash on disk.
            item { CrashReportCard() }
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
            if (unreadableFiles.isNotEmpty()) {
                item {
                    DamagedFilesCard(
                        names = unreadableFiles,
                        onDelete = { name -> damagedToDelete = name },
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
                if (projects.size >= 5 || query.isNotBlank()) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            // `label`, not `placeholder`: a placeholder is decoration inside the
                            // box and is not the field's name, so a screen reader met an unnamed
                            // edit box. Material's label sits in exactly that spot when the field
                            // is empty, so nothing moves on screen (finding 211).
                            label = { Text(stringResource(R.string.home_search_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (filtered.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(
                                if (needle.isEmpty()) R.string.home_other_projects else R.string.home_all_projects,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                        )
                    }
                }
                if (filtered.isEmpty() && needle.isNotEmpty()) {
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
                        ProjectRow(
                            project = project,
                            onClick = { onOpen(project.id) },
                            onCloseRoom = { onCloseRoom(project.id) },
                        )
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
                            // A card that opens the paywall is a button (finding 211).
                            .clickable(role = Role.Button, onClick = onUpgrade),
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
            onDismissRequest = {
                showCreateDialog = false
                newName = ""
            },
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
                TextButton(
                    onClick = {
                        // An abandoned name is not a suggestion for the next room (finding 125).
                        showCreateDialog = false
                        newName = ""
                    },
                ) { Text(stringResource(R.string.home_cancel)) }
            },
        )
    }

    // Deleting a file the app cannot read is deleting the operator's own data: the question names
    // the file, says what this version could and could not read, and says what becomes free.
    if (damagedToDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { damagedToDelete = "" },
            title = { Text(stringResource(R.string.home_damaged_title)) },
            text = { Text(stringResource(R.string.home_damaged_body, damagedToDelete)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = damagedToDelete
                        damagedToDelete = ""
                        onDeleteUnreadable(name)
                    },
                ) { Text(stringResource(R.string.home_damaged_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { damagedToDelete = "" }) {
                    Text(stringResource(R.string.home_cancel))
                }
            },
        )
    }
}

/**
 * The files this version cannot read, each with the one action that helps.
 *
 * Not a row in the room list: that list is built from rooms the app could parse, and a file that
 * cannot be parsed is exactly the one it cannot draw. A separate card says what these files are,
 * keeps them visible, and lets the operator free the room they occupy (finding 150).
 */
@Composable
private fun DamagedFilesCard(names: List<String>, onDelete: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Jamaica.SurfaceHigh),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.home_damaged_title),
                style = MaterialTheme.typography.labelMedium,
                color = SiteColors.warning,
            )
            names.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onDelete(name) }, modifier = Modifier.size(SiteColors.gloveTouchTarget)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.home_damaged_delete),
                        )
                    }
                }
            }
        }
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
        IconButton(onClick = onHelp, modifier = Modifier.size(SiteColors.gloveTouchTarget)) {
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
        // A count of one is not "1 days": the chip on the list can show the trial's last day
        // (finding 135).
        entitlements.isTrial -> pluralStringResource(
            R.plurals.plan_trial_days,
            trialDaysLeft.toInt().coerceAtLeast(0),
            trialDaysLeft,
        ) to SiteColors.accent
        entitlements.isPro -> stringResource(R.string.plan_pro) to SiteColors.accent
        else -> stringResource(R.string.plan_free) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            // The chip is the app's plan status *and* the way to the paywall: a button.
            .clickable(role = Role.Button, onClick = onClick)
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
private fun ProjectRow(
    project: ProjectRepository.ProjectSummary,
    onClick: () -> Unit,
    onCloseRoom: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            // A card opens a room: saying so to the screen reader turns "double tap to activate"
            // into "button", which is what the row is.
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomPicture(project.sketch)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                project.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProjectMetaLine(project)
            RoomSizeBadges(project.sketch, onCloseRoom)
        }
        Text(
            shownLengthWithUnit(project.totalLengthMeters),
            style = MaterialTheme.typography.titleMedium,
            color = SiteColors.gold,
        )
    }
}

/**
 * The room's own shape where the list used to show the same cable icon on every row.
 *
 * Drawn from the outline stored in the project (`FloorplanSketch`), not from the exported
 * `thumbnail.svg`: the platform has no SVG renderer, and a second parser for a format this app only
 * writes would be a liability. The projection is [RoomThumbnail.pathPoints] - the same single scale
 * and the same "world z runs down the screen" as the exported file, so the card and the file show
 * one room.
 *
 * A room without a closed plan (nobody measured one yet, or the scan had a doorway in it) keeps the
 * icon: a placeholder outline would be a shape the operator never walked. The picture is decoration
 * — the row already prints the name, the runs, the metres and the date as text, which is what a
 * screen reader announces — so it carries no `contentDescription` of its own.
 */
@Composable
private fun RoomPicture(sketch: FloorplanSketch?) {
    if (sketch == null || !sketch.isDrawable) {
        Icon(Icons.Default.Cable, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val strokeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fillColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.size(40.dp)) {
        val points = RoomThumbnail.pathPoints(
            sketch = sketch,
            sizePx = size.minDimension,
            paddingPx = 2.dp.toPx(),
        )
        if (points.size < 3) return@Canvas
        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        for (index in 1 until points.size) path.lineTo(points[index].first, points[index].second)
        path.close()
        drawPath(path = path, color = fillColor, style = Fill)
        // A miter joint, so a right-angled room reads as a right-angled room.
        drawPath(path = path, color = strokeColor, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Miter))
    }
}

/**
 * The room's area and perimeter, as two small badges under the row's name.
 *
 * Read from the outline stored in the project and from nothing else: the same ring the picture
 * beside them draws, so the shape and the numbers cannot describe two different rooms. Both are
 * absent together - a room without a closed plan keeps its icon and states no size, because
 * "0.0 m²" would be a claim about the scan rather than about the room.
 *
 * The numbers are digits from [RoomSizeText] (the `Locale.ROOT` ones the length column of this same
 * row prints) and the units come from the resources, which is why nothing here is a Kotlin string
 * literal: `:app` owns every word a screen shows, in all six locales.
 */
@Composable
private fun RoomSizeBadges(sketch: FloorplanSketch?, onCloseRoom: () -> Unit) {
    if (sketch == null) return
    if (sketch.closure == FloorplanSketch.Closure.Open) {
        // The scan walked the room but never closed the ring - a doorway, a glazed partition. The
        // corners are stored, so one tap can state that the gap is a wall; the card then has a
        // picture and a size, and the sketch remembers the wall was drawn rather than measured.
        CloseRoomChip(onCloseRoom)
        return
    }
    // A card on a phone is prose: the reader's mark. The PDF's copy of the same two numbers is a
    // protocol and keeps the point (`ExportScreen` deliberately calls `RoomSizeText.of` without it).
    val size = RoomSizeText.of(sketch, LocalDecimalMark.current) ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
        SizeBadge(stringResource(R.string.card_area, size.area))
        SizeBadge(stringResource(R.string.card_perimeter, size.perimeter))
        if (sketch.closure == FloorplanSketch.Closure.DrawnByHand) {
            SizeBadge(stringResource(R.string.card_closed_by_hand))
        }
    }
}

/**
 * The one action an open ring offers: say the gap is a wall.
 *
 * A full `gloveTouchTarget` tall, because the operator is standing in the room with gloves on when
 * he decides this - and because it sits inside a row that is itself clickable, so a miss would open
 * the room instead of closing it.
 */
@Composable
private fun CloseRoomChip(onCloseRoom: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    TextButton(
        onClick = { asking = true },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.heightIn(min = SiteColors.gloveTouchTarget),
    ) {
        Text(
            stringResource(R.string.card_close_room),
            style = MaterialTheme.typography.labelLarge,
            color = SiteColors.accent,
        )
    }
    // One tap must not rewrite a room: the question says what the gap is, what counting it changes
    // and that the closing wall is marked as drawn rather than measured. The chip sits inside a
    // clickable row, so the dialog is also what keeps a mis-tap from opening the room instead.
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.card_close_title)) },
            text = { Text(stringResource(R.string.card_close_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onCloseRoom()
                    },
                ) { Text(stringResource(R.string.card_close_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) {
                    Text(stringResource(R.string.home_cancel))
                }
            },
        )
    }
}

/** One badge. Decoration for the eye: the same numbers are in the row's text for a screen reader. */
@Composable
private fun SizeBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
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
    // What always fits: runs, metres, date. Everything else used to be appended here as well, in a
    // single ellipsized line - so on a normal phone the string was cut off after the date and the
    // site name, the defect count *and the running clock* were never shown at all. The one line on
    // the home screen that says money is burning was the first thing the ellipsis ate.
    // Read outside the builder: `buildString`'s lambda is not a composable scope, and a
    // `@Composable` formatter called from inside it would not compile - which is the honest
    // version of the same mistake a `remember`-less call makes silently everywhere else.
    val totalText = shownLengthWithUnit(project.totalLengthMeters)
    Text(
        text = buildString {
            append(runsText)
            append(" · ")
            append(totalText)
            append(" · ")
            append(updatedLabel)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    // The customer and the site share one line: the row has to stay three lines tall on a phone
    // held in a glove, and both facts are the same kind of quiet grey detail. One ellipsized Text
    // keeps the running-clock and defect chips on the right (finding 129's lesson), where two
    // weighted Texts would have squeezed them off (finding 144).
    val placeAndCustomer = listOf(project.customerName.trim(), project.siteName.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
    if (placeAndCustomer.isNotEmpty() || project.workRunning || defectsText.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            if (placeAndCustomer.isNotEmpty()) {
                Text(
                    placeAndCustomer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            if (project.workRunning) {
                Text(
                    runningText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Jamaica.Lime,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (defectsText.isNotEmpty()) {
                Text(
                    defectsText,
                    style = MaterialTheme.typography.labelSmall,
                    color = SiteColors.warning,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
