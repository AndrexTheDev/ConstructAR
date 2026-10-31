package com.constructar.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportService = remember { ExportService() }

    var includePdf by remember { mutableStateOf(true) }
    var includeCsv by remember { mutableStateOf(true) }
    var includeGlb by remember { mutableStateOf(true) }
    var includePly by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

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
                        ?: error("Folder is not accessible")
                    val stem = exportService.fileStem(project)
                    val target = root.findFile(stem)?.takeIf { it.isDirectory }
                        ?: root.createDirectory(stem)
                        ?: error("Cannot create the export folder")

                    val artifacts = exportService.build(
                        ExportService.Request(
                            project = project,
                            materialList = materialList ?: MaterialListBuilderFallback.build(project),
                            routeMeshes = ExportService.routeMeshes(project),
                            screenshot = null,
                            operatorName = operatorName,
                            appVersion = appVersion,
                            includePdf = includePdf,
                            includeCsv = includeCsv,
                            includeGlb = includeGlb,
                            includePly = includePly,
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
                onSuccess = { (count, stem) -> "Saved $count files to “$stem”." },
                onFailure = { "Export failed: ${it.message ?: it.javaClass.simpleName}" },
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
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Export", style = MaterialTheme.typography.headlineSmall)
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
                Text("Measurement", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${project.routes.size} runs · ${LengthFormatter.format(project.totalLengthMeters)} total",
                    style = MaterialTheme.typography.bodyLarge,
                )
                materialList?.let { list ->
                    Text(
                        "Order length incl. waste: ${LengthFormatter.format(list.totalWithWasteMeters)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Clips ${list.totalClips} · couplings ${list.totalCouplings} · bends ${list.totalElbows}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                project.scan?.let { scan ->
                    if (scan.meshTriangleCount > 0) {
                        Text(
                            "Room scan: ${scan.meshTriangleCount / 1000} k triangles",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        FormatRow("PDF protocol (screenshot, lengths, bill of materials)", includePdf) { includePdf = it }
        FormatRow("CSV material list (Excel-ready, semicolon separated)", includeCsv) { includeCsv = it }
        FormatRow("GLB model for BIM (Revit / Navisworks / Blender)", includeGlb) { includeGlb = it }
        FormatRow("PLY point cloud / mesh (CloudCompare, MeshLab)", includePly) { includePly = it }

        GloveButton(
            label = if (busy) "Saving…" else "Save to folder…",
            icon = Icons.Default.CreateNewFolder,
            onClick = { folderLauncher.launch(null) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }

        Text(
            "Files are written to the folder you pick. Nothing is uploaded; export works in " +
                "airplane mode and in a basement.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FormatRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
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
