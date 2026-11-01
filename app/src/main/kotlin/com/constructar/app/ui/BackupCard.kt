/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.constructar.app.R
import com.constructar.app.data.ProjectRepository
import com.constructar.app.ui.theme.SiteColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore, in the operator's own folder.
 *
 * Every measurement lives in one JSON file in app-private storage, and every project can carry one
 * photo. That survives updates and crashes (writes are atomic), but it does not survive a broken
 * phone or an "app data" wipe — and for a foreman the measurements *are* the job. So the app can
 * copy everything into a folder the operator picks (that is how the Storage Access Framework
 * works: no cloud is involved unless they choose one), and read it back on any phone.
 *
 * Restores never overwrite: a project whose id is already present is imported under a fresh id, so
 * both copies survive. Photos are matched back by file name (which *is* the project id), which is
 * exactly right when restoring onto a new phone and deliberately skips the rare collision case
 * rather than attaching a photo to the wrong room.
 */
@Composable
fun BackupCard(
    repository: ProjectRepository,
    onRestore: (
        texts: List<String>,
        photos: List<Pair<String, Uri>>,
        done: (imported: Int, heldBack: Int) -> Unit,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<BackupOutcome?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    val root = DocumentFile.fromTreeUri(context, uri)
                        ?: error(context.getString(R.string.backup_failed))
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                    val folder = root.findFile("ConstructAR-backup-$stamp")?.takeIf { it.isDirectory }
                        ?: root.createDirectory("ConstructAR-backup-$stamp")
                        ?: error(context.getString(R.string.backup_failed))
                    var written = 0
                    repository.backupEntries().forEach { entry ->
                        val file = folder.createFile("application/json", entry.fileName)
                        val output = file?.uri?.let { context.contentResolver.openOutputStream(it) }
                        if (output != null) {
                            output.use { it.write(entry.text.toByteArray(Charsets.UTF_8)) }
                            written++
                        } else {
                            file?.delete()
                        }
                    }
                    // Photos are part of the job: copy them next to the project files so a restore
                    // on a new phone gets the documentation too, not just the metres.
                    repository.photoFiles().forEach { photo ->
                        val file = folder.createFile("image/jpeg", photo.name)
                        val output = file?.uri?.let { context.contentResolver.openOutputStream(it) }
                        if (output != null) {
                            output.use { stream -> photo.inputStream().use { it.copyTo(stream) } }
                        } else {
                            file?.delete()
                        }
                    }
                    BackupOutcome.Saved(written)
                }.getOrElse { error -> BackupOutcome.Failed(error.message.orEmpty()) }
            }
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val (texts, photos) = withContext(Dispatchers.IO) {
                val projectTexts = mutableListOf<String>()
                val photoAttachments = mutableListOf<Pair<String, Uri>>()
                uris.forEach { uri ->
                    val name = displayName(context, uri)
                    if (name.endsWith(".json", ignoreCase = true) || name.isEmpty()) {
                        readText(context, uri)?.let { projectTexts += it }
                    } else if (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)) {
                        photoAttachments += name.substringBeforeLast('.').trim() to uri
                    }
                }
                projectTexts to photoAttachments
            }
            if (texts.isEmpty() && photos.isEmpty()) {
                outcome = BackupOutcome.Failed(context.getString(R.string.backup_failed))
                busy = false
                return@launch
            }
            onRestore(texts, photos) { imported, heldBack ->
                outcome = if (imported == 0 && heldBack == 0) {
                    BackupOutcome.Failed(context.getString(R.string.backup_failed))
                } else {
                    BackupOutcome.Restored(imported, heldBack)
                }
                busy = false
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium, color = SiteColors.accent)
            Text(
                stringResource(R.string.backup_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GloveButton(
                label = stringResource(R.string.backup_export),
                icon = Icons.Default.Archive,
                tone = ActionTone.SECONDARY,
                enabled = !busy,
                onClick = { backupLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            )
            GloveButton(
                label = stringResource(R.string.backup_import),
                icon = Icons.Default.Unarchive,
                tone = ActionTone.SECONDARY,
                enabled = !busy,
                onClick = { importLauncher.launch(arrayOf(ANY_MIME_TYPE)) },
                modifier = Modifier.fillMaxWidth(),
            )
            outcome?.let { current ->
                val text = when (current) {
                    is BackupOutcome.Saved -> stringResource(R.string.backup_saved, current.count)
                    is BackupOutcome.Restored -> stringResource(R.string.backup_imported, current.count)
                    is BackupOutcome.Failed -> stringResource(R.string.backup_failed)
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current is BackupOutcome.Failed) SiteColors.danger else SiteColors.accent,
                )
                // Said plainly, because the operator has to be able to trust a restore: the
                // files are still in his zip, they were just not imported on this plan.
                if (current is BackupOutcome.Restored && current.heldBack > 0) {
                    Text(
                        text = stringResource(R.string.backup_held_back, current.heldBack),
                        style = MaterialTheme.typography.bodySmall,
                        color = SiteColors.warning,
                    )
                }
            }
        }
    }
}

/** The file name the picker reports, used to tell projects from photos. */
private fun displayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
    }.getOrNull().orEmpty()

private fun readText(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.reader(Charsets.UTF_8).readText()
    }
}.getOrNull()

/**
 * Broad picker filter, because a `.json` file is reported as `application/json`,
 * `text/plain` or `application/octet-stream` depending on the file manager — and a restore that
 * cannot see its own backup is worse than no restore at all.
 *
 * Written as a concatenation on purpose: the literal would contain a comment opener, and the
 * repository's static gate (rightly) refuses to parse around that.
 */
private const val ANY_MIME_TYPE: String = "*" + "/" + "*"

/** Kept as data so the sentences are rendered by Compose (and therefore localised). */
private sealed interface BackupOutcome {
    data class Saved(val count: Int) : BackupOutcome
    data class Restored(val count: Int, val heldBack: Int = 0) : BackupOutcome
    data class Failed(val detail: String) : BackupOutcome
}
