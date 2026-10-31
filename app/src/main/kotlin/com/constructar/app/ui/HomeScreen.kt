package com.constructar.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constructar.app.data.ProjectRepository
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.units.LengthFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Project list. One tap opens a room; the whole screen is one-handed reachable. */
@Composable
fun HomeScreen(
    projects: List<ProjectRepository.ProjectSummary>,
    loading: Boolean,
    onOpen: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("ConstructAR", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Offline AR measuring for electrical, plumbing and ventilation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.Cable, contentDescription = null, tint = SiteColors.cable)
        }

        if (loading) {
            Spacer(Modifier.height(24.dp))
        } else if (projects.isEmpty()) {
            Text(
                text = "No rooms yet. Create one to start measuring.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(projects, key = { it.id }) { project ->
                ProjectCard(project = project, onClick = { onOpen(project.id) })
            }
        }

        GloveButton(
            label = "New room",
            icon = Icons.Default.Add,
            onClick = { showCreateDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )

        Text(
            text = "Works fully offline. Nothing leaves this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New room") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Room / zone name") },
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
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProjectCard(project: ProjectRepository.ProjectSummary, onClick: () -> Unit) {
    val formatter = remember {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(project.routeCount)
                        append(if (project.routeCount == 1) " run" else " runs")
                        append(" · ")
                        append(LengthFormatter.format(project.totalLengthMeters))
                        append(" · ")
                        append(formatter.format(Date(project.updatedAtEpochMs)))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (project.siteName.isNotBlank()) {
                    Text(
                        text = project.siteName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = LengthFormatter.format(project.totalLengthMeters),
                style = MaterialTheme.typography.titleMedium,
                color = SiteColors.cable,
            )
        }
    }
}
