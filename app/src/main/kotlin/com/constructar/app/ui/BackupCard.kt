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
    // Resolved at composition: the copy loop must not depend on a resource lookup mid-write.
    val nothingText = stringResource(R.string.backup_nothing)
    val failedText = stringResource(R.string.backup_failed)

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            outcome = withContext(Dispatchers.IO) {
                runCatching {
                    // Best effort: a provider that cannot grant permanently must not end the
                    // backup (see UriGrants).
                    UriGrants.take {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    val root = DocumentFile.fromTreeUri(context, uri)
                        ?: error(context.getString(R.string.backup_failed))
                    // Asked before the folder is created: a backup of nothing used to leave an
                    // empty `ConstructAR-backup-…` folder on the card and call it a success.
                    val entries = repository.backupEntries()
                    if (entries.isEmpty()) error(nothingText)
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                    val folder = root.findFile("ConstructAR-backup-$stamp")?.takeIf { it.isDirectory }
                        ?: root.createDirectory("ConstructAR-backup-$stamp")
                        ?: error(context.getString(R.string.backup_failed))
                    var written = 0
                    entries.forEach { entry ->
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
                    // on a new phone gets the documentation too, not just the metres. They are
                    // counted, because a backup that silently drops them is worse than one that
                    // says it did.
                    val photos = repository.photoFiles()
                    var photosWritten = 0
                    photos.forEach { photo ->
                        val file = folder.createFile("image/jpeg", photo.name)
                        val output = file?.uri?.let { context.contentResolver.openOutputStream(it) }
                        if (output != null) {
                            output.use { stream -> photo.inputStream().use { it.copyTo(stream) } }
                            photosWritten++
                        } else {
                            file?.delete()
                        }
                    }
                    BackupOutcome.of(
                        report = BackupOutcome.Report(
                            available = entries.size,
                            written = written,
                            photos = photos.size,
                            photosWritten = photosWritten,
                        ),
                        nothingToDo = nothingText,
                        folderUnreadable = failedText,
                    )
                }.getOrElse { error ->
                    // Never "Backup failed: " with nothing after the colon.
                    BackupOutcome.Failed(error.message?.takeIf { it.isNotBlank() } ?: failedText)
                }
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
                        readText(context, uri, repository)?.let { projectTexts += it }
                    } else if (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)) {
                        // The file name is the project id, and it ends up in a path: a picture
                        // called "../../databases/x.jpg" is skipped, not copied somewhere else.
                        val token = name.substringBeforeLast('.').trim()
                        if (repository.isSafeToken(token)) photoAttachments += token to uri
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
                    is BackupOutcome.Saved -> stringResource(
                        R.string.backup_saved,
                        current.projects,
                        current.photos,
                    )
                    is BackupOutcome.Restored -> stringResource(R.string.backup_imported, current.count)
                    // The detail is the localised sentence that explains *this* failure: blaming
                    // the folder for an empty app is the wrong advice.
                    is BackupOutcome.Failed -> current.detail
                }
                val partial = current is BackupOutcome.Saved && current.photosSkipped > 0
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        current is BackupOutcome.Failed -> SiteColors.danger
                        partial -> SiteColors.warning
                        else -> SiteColors.accent
                    },
                )
                if (current is BackupOutcome.Saved && current.photosSkipped > 0) {
                    Text(
                        text = stringResource(
                            R.string.backup_photos_skipped,
                            current.photosSkipped,
                            current.photos,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = SiteColors.warning,
                    )
                }
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

/** Reads a picked file through the repository, which caps it at [ProjectRepository.MAX_PROJECT_BYTES]. */
private fun readText(
    context: android.content.Context,
    uri: Uri,
    repository: ProjectRepository,
): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream -> repository.readProjectText(stream) }
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

/**
 * Kept as data so the sentences are rendered by Compose (and therefore localised).
 *
 * The same lesson as the export screen (finding 28), in a second copy of the same code: a write
 * loop that counts only its successes cannot tell "nothing exists", "nothing could be written"
 * and "everything was written" apart. The backup card answered all three with the success colour
 * and a number - `0 Projekte gesichert`.
 */
internal sealed interface BackupOutcome {
    data class Saved(val projects: Int, val photos: Int, val photosSkipped: Int = 0) : BackupOutcome

    data class Restored(val count: Int, val heldBack: Int = 0) : BackupOutcome

    data class Failed(val detail: String) : BackupOutcome

    /** What there was, what was written, and what could not be. */
    data class Report(
        val available: Int,
        val written: Int,
        val photos: Int,
        val photosWritten: Int,
    )

    companion object {
        fun of(report: Report, nothingToDo: String, folderUnreadable: String): BackupOutcome = when {
            report.available == 0 -> Failed(detail = nothingToDo)
            report.written == 0 -> Failed(detail = folderUnreadable)
            else -> Saved(
                projects = report.written,
                photos = report.photosWritten,
                photosSkipped = report.photos - report.photosWritten,
            )
        }
    }
}
