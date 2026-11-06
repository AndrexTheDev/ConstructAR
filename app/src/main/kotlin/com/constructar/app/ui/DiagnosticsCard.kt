/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.constructar.app.BuildConfig
import com.constructar.app.R
import com.constructar.app.diagnostics.Diagnostics
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext

/**
 * The card a beta tester uses to hand their session over (finding 256).
 *
 * Three things, and no settings: what this build is, what just happened, and one button that puts
 * the files in a folder the tester can reach. The clipboard is there because the fastest way to
 * report "it drifted again" is to paste the last twenty lines into a message.
 *
 * The preview is deliberately short - six lines, the newest. A diagnostics screen that scrolls is
 * a screen nobody reads on a phone held in one hand.
 */
@Composable
fun DiagnosticsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<Int?>(null) }
    val lines = remember { Diagnostics.lines().takeLast(PREVIEW_LINES) }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            outcome = withContext(Dispatchers.IO) {
                runCatching {
                    UriGrants.take {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    val root = DocumentFile.fromTreeUri(context, uri) ?: return@runCatching -1
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT)
                        .format(java.util.Date())
                    val folder = root.findFile("ConstructAR-diagnose-$stamp")?.takeIf { it.isDirectory }
                        ?: root.createDirectory("ConstructAR-diagnose-$stamp")
                        ?: return@runCatching -1
                    var written = 0
                    // The session the tester is in right now may not be on disk completely: the
                    // ring holds it, so it is written out first as its own file.
                    val live = "session-current.log" to Diagnostics.snapshot()
                    val others = Diagnostics.files().map { file -> file.name to runCatching { file.readText() }.getOrDefault("") }
                    (listOf(live) + others).forEach { (name, text) ->
                        val result = ExportFiles.writeOnce(
                            create = { folder.createFile("text/plain", name) },
                            open = { file -> context.contentResolver.openOutputStream(file.uri) },
                            write = { stream -> stream.use { it.write(text.toByteArray()) } },
                            delete = { file -> file.delete() },
                        )
                        if (result is ExportFiles.Outcome.Written) written += 1
                    }
                    written
                }.getOrDefault(-1)
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Jamaica.SurfaceHigh),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.diag_title),
                style = MaterialTheme.typography.titleMedium,
            )
            // A build with everything unlocked has to say so where a tester will read it.
            if (BuildConfig.BETA_BUILD) {
                Text(
                    stringResource(R.string.beta_banner),
                    style = MaterialTheme.typography.bodySmall,
                    color = SiteColors.warning,
                )
            }
            Text(
                stringResource(R.string.diag_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lines.isEmpty()) {
                Text(
                    stringResource(R.string.diag_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    lines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { folderLauncher.launch(null) }) {
                    Text(stringResource(R.string.diag_save))
                }
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("ConstructAR", Diagnostics.snapshot()))
                        outcome = COPIED
                    },
                ) {
                    Text(stringResource(R.string.diag_copy))
                }
            }
            outcome?.let { result ->
                Text(
                    when (result) {
                        COPIED -> stringResource(R.string.diag_copied)
                        0, -1 -> stringResource(R.string.diag_failed)
                        else -> stringResource(R.string.diag_saved, result)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result > 0 || result == COPIED) SiteColors.accent else SiteColors.warning,
                )
            }
        }
    }
}

/** Six lines is what fits under the buttons on a phone in one hand. */
private const val PREVIEW_LINES = 6

/** The clipboard result is not a file count, so it gets a value no count can have. */
private const val COPIED = -2
