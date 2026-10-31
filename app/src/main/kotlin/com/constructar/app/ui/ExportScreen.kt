/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.constructar.app.BuildConfig
import com.constructar.app.R
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.licence.Entitlements
import com.constructar.core.bom.MaterialList
import com.constructar.core.model.ProjectRecord
import com.constructar.core.units.LengthFormatter
import com.constructar.export.ExportService
import com.constructar.export.csv.MaterialCsvWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export screen.
 *
 * The operator picks a folder once; every requested file is written there through the Storage
 * Access Framework. No `WRITE_EXTERNAL_STORAGE` permission, no media scanner, no cloud — and
 * the files land exactly where a site laptop expects them.
 */
@Composable
fun ExportScreen(
    project: ProjectRecord,
    materialList: MaterialList?,
    appVersion: String,
    operatorName: String,
    entitlements: Entitlements,
    watermark: String,
    photo: android.graphics.Bitmap? = null,
    onBack: () -> Unit,
    onUpgrade: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportService = remember { ExportService() }

    var includePdf by remember { mutableStateOf(true) }
    var includeCsv by remember { mutableStateOf(true) }
    // A free build cannot write the BIM formats, so the switches start off rather than promise
    // a file that the licence check would refuse to produce.
    var includeGlb by remember(entitlements.isPro) { mutableStateOf(entitlements.canExportMesh) }
    var includePly by remember(entitlements.isPro) { mutableStateOf(false) }
    var status by remember { mutableStateOf<ExportStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    val signature = rememberSignaturePadState()
    // Resolved at composition so the export coroutine never touches a resource lookup.
    val pdfLabels = rememberPdfLabels()
    // Decoded once per screen: the protocol carries the badge so the document is recognisable
    // after it leaves the phone.
    val logo = remember(context) {
        android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.constructar_logo)
    }
    val signatureTitle = stringResource(R.string.signature_pdf_title)
    val signatureCaption = stringResource(R.string.signature_pdf_caption)

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    val root = DocumentFile.fromTreeUri(context, uri)
                        ?: error(context.getString(R.string.export_folder_inaccessible))
                    val stem = exportService.fileStem(project)
                    val target = root.findFile(stem)?.takeIf { it.isDirectory }
                        ?: root.createDirectory(stem)
                        ?: error(context.getString(R.string.export_folder_inaccessible))

                    val artifacts = exportService.build(
                        ExportService.Request(
                            project = project,
                            materialList = materialList ?: MaterialListBuilderFallback.build(project),
                            routeMeshes = ExportService.routeMeshes(project),
                            screenshot = photo,
                            operatorName = operatorName,
                            appVersion = appVersion,
                            includePdf = includePdf,
                            includeCsv = includeCsv,
                            includeGlb = includeGlb,
                            includePly = includePly,
                            watermark = if (entitlements.pdfWatermark) watermark else "",
                            signature = signature.bitmap(),
                            signatureTitle = signatureTitle,
                            signatureCaption = signatureCaption,
                            labels = pdfLabels,
                            logo = logo,
                        ),
                    )
                    var written = 0
                    artifacts.forEach { artifact ->
                        val file = target.createFile(artifact.mimeType, artifact.fileName)
                        val output = file?.uri?.let { context.contentResolver.openOutputStream(it) }
                        if (output != null) {
                            output.use { artifact.write(it) }
                            written++
                        } else {
                            file?.delete()
                        }
                    }
                    written to stem
                }
            }
            busy = false
            status = outcome.fold(
                onSuccess = { (count, stem) -> ExportStatus.Saved(count = count, folder = stem) },
                onFailure = { error -> ExportStatus.Failed(error.message ?: error.javaClass.simpleName) },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.export_back))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    project.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.export_measurement), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.export_runs_total,
                        project.routes.size,
                        LengthFormatter.format(project.totalLengthMeters),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                materialList?.let { list ->
                    Text(
                        stringResource(
                            R.string.export_order_length,
                            LengthFormatter.format(list.totalWithWasteMeters),
                        ),
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
                    )
                }
                project.scan?.let { scan ->
                    if (scan.meshTriangleCount > 0) {
                        Text(
                            stringResource(R.string.export_room_scan, scan.meshTriangleCount / 1000),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        FormatRow(
            label = stringResource(R.string.export_pdf),
            description = stringResource(R.string.export_pdf_desc),
            checked = includePdf,
        ) { includePdf = it }
        FormatRow(
            label = stringResource(R.string.export_csv),
            description = stringResource(R.string.export_csv_desc),
            checked = includeCsv,
        ) { includeCsv = it }
        if (entitlements.canExportMesh) {
            FormatRow(
                label = stringResource(R.string.export_glb),
                description = stringResource(R.string.export_glb_desc),
                checked = includeGlb,
            ) { includeGlb = it }
            FormatRow(
                label = stringResource(R.string.export_ply),
                description = stringResource(R.string.export_ply_desc),
                checked = includePly,
            ) { includePly = it }
        } else {
            LockedRow(
                label = stringResource(R.string.export_glb),
                description = stringResource(R.string.export_pro_locked_body),
                onUpgrade = onUpgrade,
            )
        }

        if (entitlements.pdfWatermark) {
            Text(
                stringResource(R.string.export_watermark_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Handover signature ──────────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.signature_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.signature_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { signature.clear() }) {
                        Text(stringResource(R.string.signature_clear))
                    }
                }
                SignaturePad(
                    state = signature,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                )
            }
        }

        GloveButton(
            label = stringResource(if (busy) R.string.export_saving else R.string.export_choose_folder),
            icon = Icons.Default.CreateNewFolder,
            onClick = { folderLauncher.launch(null) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        status?.let { current ->
            val text = when (current) {
                is ExportStatus.Saved -> stringResource(R.string.export_success, current.count, current.folder)
                is ExportStatus.Failed -> stringResource(R.string.export_failed, current.detail)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }

        // Ownership line: the operator always sees whose build this is.
        val appName = stringResource(R.string.app_name)
        Text(
            stringResource(
                R.string.export_about,
                appName,
                stringResource(R.string.developer_name),
                BuildConfig.DEVELOPER_EMAIL,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            stringResource(R.string.export_offline_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A paid feature, shown rather than hidden.
 *
 * Hiding the BIM formats would leave the operator wondering whether the app can do it at all;
 * showing them locked answers both questions at once and turns the limitation into one tap.
 */
@Composable
private fun LockedRow(label: String, description: String, onUpgrade: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUpgrade)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = SiteColors.gold)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.export_unlock),
            style = MaterialTheme.typography.labelMedium,
            color = SiteColors.accent,
        )
    }
}

@Composable
private fun FormatRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Outcome of one export, kept as data so the sentence is rendered by Compose (and therefore
 * localised) instead of being formatted inside the IO coroutine.
 */
private sealed interface ExportStatus {
    data class Saved(val count: Int, val folder: String) : ExportStatus
    data class Failed(val detail: String) : ExportStatus
}

/** Fallback when the view model has no cached list (e.g. deep link straight into Export). */
private object MaterialListBuilderFallback {
    fun build(project: ProjectRecord) = com.constructar.core.bom.MaterialListBuilder.build(project)
}

/** Exposed for tests/diagnostics: the CSV the operator will get for this project. */
fun previewCsv(project: ProjectRecord): String =
    MaterialCsvWriter.write(
        materialList = com.constructar.core.bom.MaterialListBuilder.build(project),
        options = MaterialCsvWriter.Options(includeBom = false),
    )
