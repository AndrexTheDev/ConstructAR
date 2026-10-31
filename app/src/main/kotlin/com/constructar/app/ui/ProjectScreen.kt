/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.constructar.app.R
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.bom.MaterialList
import com.constructar.core.model.ProjectRecord
import com.constructar.core.units.LengthFormatter

/**
 * The project hub — the screen the operator returns to after every run.
 *
 * It answers the only three questions that exist on site: *what have I got* (runs and metres),
 * *what is missing* (the step trail), and *what do I do next* (one primary button, chosen for
 * them). Everything else — renaming, the note for the office, deleting — is secondary and one
 * tap deeper.
 */
@Composable
fun ProjectScreen(
    project: ProjectRecord,
    materialList: MaterialList?,
    onBack: () -> Unit,
    onMeasure: () -> Unit,
    onExport: () -> Unit,
    onRename: (String) -> Unit,
    onSaveNotes: (String) -> Unit,
    onSaveTechnician: (String) -> Unit,
    onRemoveRoute: (String) -> Unit,
    onMaterials: () -> Unit,
    photo: Bitmap?,
    onPhoto: (Uri) -> Unit,
    onDelete: () -> Unit,
) {
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var nameDraft by remember(project.id) { mutableStateOf(project.name) }
    var notesDraft by remember(project.id) { mutableStateOf(project.notes) }
    var notesSaved by remember(project.id) { mutableStateOf(false) }
    var technicianDraft by remember(project.id) { mutableStateOf(project.technician) }
    val context = LocalContext.current
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val target = pendingCapture
        if (captured && target != null) onPhoto(target)
    }

    val scanned = (project.scan?.meshTriangleCount ?: 0) > 0
    val measured = project.routes.isNotEmpty()

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
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (project.siteName.isNotBlank()) {
                    Text(
                        project.siteName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { renameOpen = true }, modifier = Modifier.size(52.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.action_rename),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { deleteOpen = true }, modifier = Modifier.size(52.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = SiteColors.danger,
                )
            }
        }

        // ── Where the job stands ────────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StepChip(stringResource(R.string.step_scan), number = 1, done = scanned, modifier = Modifier.weight(1f))
            StepChip(stringResource(R.string.step_measure), number = 2, done = measured, modifier = Modifier.weight(1f))
            StepChip(stringResource(R.string.step_export), number = 3, done = false, modifier = Modifier.weight(1f))
        }

        // ── Documentation photo ─────────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.photo_title), style = MaterialTheme.typography.titleMedium)
                if (photo != null) {
                    Image(
                        bitmap = photo.asImageBitmap(),
                        contentDescription = stringResource(R.string.photo_title),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                    )
                } else {
                    Text(
                        stringResource(R.string.photo_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GloveButton(
                    label = stringResource(if (photo == null) R.string.photo_take else R.string.photo_replace),
                    icon = Icons.Default.PhotoCamera,
                    tone = ActionTone.SECONDARY,
                    onClick = {
                        // The camera app writes straight into our cache through the FileProvider,
                        // so no storage permission and no copy of the operator's gallery is needed.
                        val directory = java.io.File(context.cacheDir, "capture").apply { mkdirs() }
                        val target = java.io.File(directory, "photo-" + System.currentTimeMillis() + ".jpg")
                        val uri = FileProvider.getUriForFile(
                            context,
                            context.packageName + ".fileprovider",
                            target,
                        )
                        pendingCapture = uri
                        captureLauncher.launch(uri)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── What has been measured ──────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = Jamaica.SurfaceHigh),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    LengthFormatter.format(project.totalLengthMeters),
                    style = MaterialTheme.typography.displaySmall,
                    color = SiteColors.accent,
                )
                Text(
                    stringResource(R.string.export_runs_total, project.routes.size, LengthFormatter.format(project.totalLengthMeters)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                materialList?.let { list ->
                    Text(
                        stringResource(R.string.export_order_length, LengthFormatter.format(list.totalWithWasteMeters)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.export_fittings,
                            list.totalClips,
                            list.totalCouplings,
                            list.totalElbows,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (project.scan == null) {
                    Text(
                        stringResource(R.string.project_scan_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = SiteColors.warning,
                    )
                }
            }
        }

        // ── The runs, individually ──────────────────────────────────────────────────
        if (project.routes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.runs_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
                project.routes.forEach { route ->
                    RouteRow(
                        kind = route.spec.kind.routingLabel(),
                        detail = stringResource(
                            R.string.run_detail,
                            route.spec.label,
                            route.spec.sizeLabel,
                        ),
                        length = LengthFormatter.format(route.polylineLengthMeters),
                        onDelete = { onRemoveRoute(route.id) },
                    )
                }
            }
        }

        // ── Next action, chosen for the operator ────────────────────────────────────
        GloveButton(
            label = stringResource(if (measured) R.string.project_measure_again else R.string.project_measure),
            icon = Icons.Default.PlayArrow,
            onClick = onMeasure,
            modifier = Modifier.fillMaxWidth(),
        )
        GloveButton(
            label = stringResource(R.string.export_title),
            icon = Icons.Default.Share,
            tone = ActionTone.SECONDARY,
            enabled = measured,
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
        )
        GloveButton(
            label = stringResource(R.string.materials_button),
            icon = Icons.Default.ShoppingCart,
            tone = ActionTone.SECONDARY,
            enabled = measured,
            onClick = onMaterials,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Who signs the protocol ──────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.technician_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = technicianDraft,
                    onValueChange = { technicianDraft = it },
                    placeholder = { Text(stringResource(R.string.technician_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                GloveButton(
                    label = stringResource(R.string.action_save),
                    icon = Icons.Default.Check,
                    tone = ActionTone.SECONDARY,
                    enabled = technicianDraft.trim() != project.technician,
                    onClick = { onSaveTechnician(technicianDraft) },
                )
            }
        }

        // ── The note for the office ─────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.notes_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = notesDraft,
                    onValueChange = {
                        notesDraft = it
                        notesSaved = false
                    },
                    placeholder = { Text(stringResource(R.string.notes_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GloveButton(
                        label = stringResource(R.string.action_save),
                        icon = Icons.Default.Check,
                        tone = ActionTone.SECONDARY,
                        enabled = notesDraft != project.notes,
                        onClick = {
                            onSaveNotes(notesDraft)
                            notesSaved = true
                        },
                    )
                    if (notesSaved) {
                        Text(
                            stringResource(R.string.notes_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = SiteColors.accent,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(stringResource(R.string.dialog_rename_title)) },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text(stringResource(R.string.home_room_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameOpen = false
                        onRename(nameDraft)
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text(stringResource(R.string.home_cancel)) }
            },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteOpen = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.action_delete), color = SiteColors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text(stringResource(R.string.home_cancel)) }
            },
        )
    }
}

/** One measured run: what it is, where it runs, how long it is, and a way to remove it. */
@Composable
private fun RouteRow(kind: String, detail: String, length: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(kind, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(length, style = MaterialTheme.typography.titleSmall, color = SiteColors.gold)
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.run_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One of the three numbered chips: scan, measure, export. */
@Composable
private fun StepChip(label: String, number: Int, done: Boolean, modifier: Modifier = Modifier) {
    val background = if (done) Jamaica.GreenDim.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (done) SiteColors.accent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (done) "✓" else number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
