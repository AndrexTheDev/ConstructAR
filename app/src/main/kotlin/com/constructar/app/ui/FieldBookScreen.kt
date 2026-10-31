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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.model.DefectItem
import com.constructar.core.model.DefectSeverity
import com.constructar.core.model.DiaryEntry
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.WorkSession
import com.constructar.core.model.WorkTime
import kotlinx.coroutines.delay

/**
 * The site file: working time, the diary and the defect list, on one scrolling screen.
 *
 * This is the screen the research says every tradesman actually keeps on paper today — the
 * hours on the back of a delivery note, the diary in a notebook that gets wet, the punch list
 * on a photo printed at the office. Three cards, in the order the day happens: clock on, write
 * down what happened, note what is broken.
 *
 * Everything is local, everything is in the project JSON, everything reaches the PDF. No sync
 * queue, because there is no server to sync to — the export is the handover.
 */
@Composable
fun FieldBookScreen(
    project: ProjectRecord,
    onBack: () -> Unit,
    onStartWork: () -> Unit,
    onStopWork: () -> Unit,
    onAddDiary: (String) -> Unit,
    onDeleteDiary: (String) -> Unit,
    onAddDefect: (title: String, location: String, severity: DefectSeverity) -> Unit,
    onToggleDefect: (String) -> Unit,
    onDeleteDefect: (String) -> Unit,
) {
    val running = WorkTime.isRunning(project.sessions)

    // A running clock has to move on its own; half a minute is enough to keep "running since"
    // honest without waking the screen every second on a phone whose battery is the shift.
    var nowMs by remember(project.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    var diaryDraft by remember(project.id) { mutableStateOf("") }
    var defectTitle by remember(project.id) { mutableStateOf("") }
    var defectLocation by remember(project.id) { mutableStateOf("") }
    var defectSeverity by remember(project.id) { mutableStateOf(DefectSeverity.MEDIUM) }

    val totalMillis = WorkTime.totalMillis(project.sessions, nowMs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.paywall_back),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.field_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    project.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            stringResource(R.string.field_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Working time ────────────────────────────────────────────────────────────────
        FieldCard(title = stringResource(R.string.field_times_title)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.field_time_total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        durationLabel(totalMillis),
                        style = MaterialTheme.typography.displaySmall,
                        color = SiteColors.accent,
                    )
                }
                val session = WorkTime.runningSession(project.sessions)
                if (session != null) {
                    Text(
                        stringResource(R.string.field_time_running, formatTimeLabel(session.startEpochMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Jamaica.Lime,
                    )
                }
            }
            GloveButton(
                label = stringResource(if (running) R.string.field_time_stop else R.string.field_time_start),
                icon = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                tone = if (running) ActionTone.DANGER else ActionTone.PRIMARY,
                onClick = { if (running) onStopWork() else onStartWork() },
                modifier = Modifier.fillMaxWidth(),
            )
            if (project.sessions.isEmpty()) {
                Text(
                    stringResource(R.string.field_time_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Newest first on screen; the JSON keeps them oldest first.
                project.sessions.asReversed().take(12).forEach { session ->
                    SessionRow(session = session, nowMs = nowMs)
                }
            }
        }

        // ── Site diary ──────────────────────────────────────────────────────────────────
        FieldCard(title = stringResource(R.string.field_diary_title)) {
            OutlinedTextField(
                value = diaryDraft,
                onValueChange = { diaryDraft = it },
                placeholder = { Text(stringResource(R.string.field_diary_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            GloveButton(
                label = stringResource(R.string.field_diary_add),
                icon = Icons.Default.Add,
                tone = ActionTone.SECONDARY,
                enabled = diaryDraft.isNotBlank(),
                onClick = {
                    onAddDiary(diaryDraft)
                    diaryDraft = ""
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (project.diary.isEmpty()) {
                Text(
                    stringResource(R.string.field_diary_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                project.diary.take(20).forEach { entry ->
                    DiaryRow(entry = entry, onDelete = { onDeleteDiary(entry.id) })
                }
            }
        }

        // ── Defects ─────────────────────────────────────────────────────────────────────
        FieldCard(title = stringResource(R.string.field_defects_title)) {
            val open = project.openDefectCount
            if (project.defects.isNotEmpty()) {
                Text(
                    stringResource(R.string.field_open_count, open) + " · " +
                        stringResource(R.string.field_resolved_count, project.defects.size - open),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (open > 0) SiteColors.warning else SiteColors.accent,
                )
            }
            OutlinedTextField(
                value = defectTitle,
                onValueChange = { defectTitle = it },
                placeholder = { Text(stringResource(R.string.field_defect_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = defectLocation,
                onValueChange = { defectLocation = it },
                placeholder = { Text(stringResource(R.string.field_defect_location_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SeverityChip(
                    label = stringResource(R.string.severity_low),
                    selected = defectSeverity == DefectSeverity.LOW,
                    accent = Jamaica.Ash,
                    onClick = { defectSeverity = DefectSeverity.LOW },
                    modifier = Modifier.weight(1f),
                )
                SeverityChip(
                    label = stringResource(R.string.severity_medium),
                    selected = defectSeverity == DefectSeverity.MEDIUM,
                    accent = SiteColors.warning,
                    onClick = { defectSeverity = DefectSeverity.MEDIUM },
                    modifier = Modifier.weight(1f),
                )
                SeverityChip(
                    label = stringResource(R.string.severity_high),
                    selected = defectSeverity == DefectSeverity.HIGH,
                    accent = SiteColors.danger,
                    onClick = { defectSeverity = DefectSeverity.HIGH },
                    modifier = Modifier.weight(1f),
                )
            }
            GloveButton(
                label = stringResource(R.string.field_defect_add),
                icon = Icons.Default.Add,
                tone = ActionTone.SECONDARY,
                enabled = defectTitle.isNotBlank(),
                onClick = {
                    onAddDefect(defectTitle, defectLocation, defectSeverity)
                    defectTitle = ""
                    defectLocation = ""
                    defectSeverity = DefectSeverity.MEDIUM
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (project.defects.isEmpty()) {
                Text(
                    stringResource(R.string.field_defect_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                project.defects.take(20).forEach { defect ->
                    DefectRow(
                        defect = defect,
                        onToggle = { onToggleDefect(defect.id) },
                        onDelete = { onDeleteDefect(defect.id) },
                    )
                }
            }
        }
    }
}

/** One card of the site file, so the three sections cannot drift apart visually. */
@Composable
private fun FieldCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** `2 h 15 min`, or `45 min` when there are no hours — the way a timesheet is written. */
@Composable
private fun durationLabel(millis: Long): String {
    val hours = WorkTime.hours(millis)
    val minutes = WorkTime.minutes(millis)
    return if (hours > 0) {
        stringResource(R.string.hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.minutes_only, minutes)
    }
}

/** Clock-off time to clock-on time, in the phone's own 12/24-hour convention. */
@Composable
private fun sessionLabel(session: WorkSession, nowMs: Long): String {
    val start = formatTimeLabel(session.startEpochMs)
    val end = session.endEpochMs?.let { formatTimeLabel(it) } ?: stringResource(R.string.field_time_open)
    return formatDateLabel(session.startEpochMs) + " · " +
        stringResource(R.string.field_time_range, start, end) + " · " +
        durationLabel(WorkTime.sessionMillis(session, nowMs))
}

@Composable
private fun SessionRow(session: WorkSession, nowMs: Long) {
    Text(
        sessionLabel(session, nowMs),
        style = MaterialTheme.typography.bodySmall,
        color = if (session.endEpochMs == null) Jamaica.Lime else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DiaryRow(entry: DiaryEntry, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                formatDateTimeLabel(entry.createdAtEpochMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.field_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DefectRow(defect: DefectItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    val accent = when {
        defect.resolved -> Jamaica.LimeDim
        defect.severity == DefectSeverity.HIGH -> SiteColors.danger
        defect.severity == DefectSeverity.MEDIUM -> SiteColors.warning
        else -> Jamaica.Ash
    }
    val severity = severityLabel(defect.severity)
    val resolvedLabel = stringResource(R.string.pdf_status_resolved)
    val detail = listOfNotNull(
        defect.location.takeIf { it.isNotBlank() },
        severity,
        resolvedLabel.takeIf { defect.resolved },
    ).joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onToggle, modifier = Modifier.size(52.dp)) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.field_defect_toggle),
                tint = accent,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                defect.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (defect.resolved) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(detail, style = MaterialTheme.typography.labelSmall, color = accent)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.field_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun severityLabel(severity: DefectSeverity): String = when (severity) {
    DefectSeverity.LOW -> stringResource(R.string.severity_low)
    DefectSeverity.MEDIUM -> stringResource(R.string.severity_medium)
    DefectSeverity.HIGH -> stringResource(R.string.severity_high)
}

/** A severity choice big enough for a glove; the selected one is filled with its own colour. */
@Composable
private fun SeverityChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Jamaica.Ink else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 18.dp),
    )
}
