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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.constructar.core.price.MoneyFormatter
import com.constructar.core.price.Pricing
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
    onFieldBook: () -> Unit,
    onSaveCustomer: (name: String, address: String, phone: String) -> Unit,
    /** Effective rate for this job - the project's own, or the firm default behind it. */
    hourlyRateCents: Int,
    onSavePricing: (hourlyRateCents: Int?, materialLumpSumCents: Long) -> Unit,
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

    // ── Handover readiness ──────────────────────────────────────────────────────────────
    // Six facts, each of them something the protocol or the bill needs. Shown as chips rather
    // than a checklist to tick, because all of it is already recorded — this only reflects the
    // project back at the operator before they leave the site.
    val readyCustomer = project.customerName.isNotBlank()
    val readyTechnician = project.technician.isNotBlank()
    val readyRuns = project.routes.isNotEmpty()
    val readyPhoto = photo != null
    val readyTime = project.sessions.isNotEmpty()
    val missing: List<String> = listOf(
        stringResource(R.string.ready_customer) to readyCustomer,
        stringResource(R.string.ready_technician) to readyTechnician,
        stringResource(R.string.ready_scan) to scanned,
        stringResource(R.string.ready_runs) to readyRuns,
        stringResource(R.string.ready_photo) to readyPhoto,
        stringResource(R.string.ready_time) to readyTime,
    ).filterNot { it.second }.map { it.first }
    var customerName by remember(project.id) { mutableStateOf(project.customerName) }
    var customerAddress by remember(project.id) { mutableStateOf(project.customerAddress) }
    var customerPhone by remember(project.id) { mutableStateOf(project.customerPhone) }
    // Blank means "use the firm rate", which is why the fields are strings and not numbers: an
    // empty field has to be distinguishable from a typed zero.
    var rateDraft by remember(project.id) {
        mutableStateOf(project.hourlyRateCents?.let { Pricing.formatForInput(it.toLong()) } ?: "")
    }
    var materialDraft by remember(project.id) {
        mutableStateOf(
            project.materialLumpSumCents.takeIf { it > 0L }?.let(Pricing::formatForInput) ?: "",
        )
    }
    val parsedRate = Pricing.parseCents(rateDraft)?.toInt()?.takeIf { it > 0 }
    val parsedMaterial = Pricing.parseCents(materialDraft) ?: 0L
    val effectiveRate = parsedRate ?: hourlyRateCents
    val priceDirty = parsedRate != project.hourlyRateCents || parsedMaterial != project.materialLumpSumCents
    val workMillis = remember(project.sessions, project.id) { project.totalWorkMillis() }
    val labourPreview = Pricing.labourCents(workMillis, effectiveRate)
    val totalPreview = Pricing.totalCents(labourPreview, parsedMaterial)
    val customerDirty = customerName != project.customerName ||
        customerAddress != project.customerAddress ||
        customerPhone != project.customerPhone

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

        // ── What is still missing before the customer signs ────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.project_ready_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ReadinessChip(stringResource(R.string.ready_customer), readyCustomer, Modifier.weight(1f))
                ReadinessChip(stringResource(R.string.ready_technician), readyTechnician, Modifier.weight(1f))
                ReadinessChip(stringResource(R.string.ready_scan), scanned, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ReadinessChip(stringResource(R.string.ready_runs), readyRuns, Modifier.weight(1f))
                ReadinessChip(stringResource(R.string.ready_photo), readyPhoto, Modifier.weight(1f))
                ReadinessChip(stringResource(R.string.ready_time), readyTime, Modifier.weight(1f))
            }
            Text(
                text = if (missing.isEmpty()) {
                    stringResource(R.string.project_ready_ok)
                } else {
                    stringResource(R.string.project_ready_missing, missing.joinToString(", "))
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (missing.isEmpty()) SiteColors.accent else SiteColors.warning,
                modifier = Modifier.padding(start = 4.dp),
            )
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
                        // A run recovered after an interruption counts in every length and every
                        // fitting, but its 3D geometry is not in the model. Saying so is the
                        // difference between a documented limitation and a surprise in a viewer.
                        badge = if (route.recovered) stringResource(R.string.run_recovered) else null,
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
        GloveButton(
            label = stringResource(R.string.project_field_button) +
                if (project.openDefectCount > 0) {
                    "  ·  " + stringResource(R.string.field_open_count, project.openDefectCount)
                } else {
                    ""
                },
            icon = Icons.Default.Timer,
            tone = ActionTone.SECONDARY,
            onClick = onFieldBook,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Who the protocol is for ────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.project_customer_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = customerName,
                    onValueChange = { customerName = it },
                    label = { Text(stringResource(R.string.project_customer_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = customerAddress,
                    onValueChange = { customerAddress = it },
                    label = { Text(stringResource(R.string.project_customer_address)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = customerPhone,
                    onValueChange = { customerPhone = it },
                    label = { Text(stringResource(R.string.project_customer_phone)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                GloveButton(
                    label = stringResource(R.string.action_save),
                    icon = Icons.Default.Check,
                    tone = ActionTone.SECONDARY,
                    enabled = customerDirty,
                    onClick = { onSaveCustomer(customerName, customerAddress, customerPhone) },
                )
            }
        }

        // ── What the job is worth ───────────────────────────────────────────────────────
        // The two numbers a tradesman would otherwise type into a spreadsheet at ten in the
        // evening. The labour line updates as he types, so the rate is never entered blind.
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.project_costs_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = rateDraft,
                    onValueChange = { rateDraft = it },
                    label = { Text(stringResource(R.string.project_costs_rate)) },
                    placeholder = { Text(Pricing.formatForInput(hourlyRateCents.toLong())) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = materialDraft,
                    onValueChange = { materialDraft = it },
                    label = { Text(stringResource(R.string.project_costs_material)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                PriceLine(
                    label = stringResource(R.string.project_costs_labour),
                    value = MoneyFormatter.format(labourPreview),
                )
                if (parsedMaterial > 0L) {
                    PriceLine(
                        label = stringResource(R.string.project_costs_material),
                        value = MoneyFormatter.format(parsedMaterial),
                    )
                }
                PriceLine(
                    label = stringResource(R.string.project_costs_total),
                    value = MoneyFormatter.format(totalPreview),
                    emphasis = true,
                )
                GloveButton(
                    label = stringResource(R.string.action_save),
                    icon = Icons.Default.Check,
                    tone = ActionTone.SECONDARY,
                    enabled = priceDirty,
                    onClick = { onSavePricing(parsedRate, parsedMaterial) },
                )
            }
        }

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
private fun PriceLine(label: String, value: String, emphasis: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (emphasis) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (emphasis) Jamaica.Lime else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RouteRow(
    kind: String,
    detail: String,
    length: String,
    badge: String? = null,
    onDelete: () -> Unit,
) {
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
            badge?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = SiteColors.warning,
                )
            }
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

/** One "is it there yet" chip: filled green when the fact is recorded, outlined when not. */
@Composable
private fun ReadinessChip(label: String, done: Boolean, modifier: Modifier = Modifier) {
    val background = if (done) Jamaica.GreenDim.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (done) SiteColors.accent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (done) "\u2713" else "\u00b7",
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
