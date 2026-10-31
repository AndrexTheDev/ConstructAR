/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import android.content.Context
import android.util.Log
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.ScanSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Local project storage: one JSON file per room, in app-private storage.
 *
 * Why not Room/SQLite: a project is a handful of routes (a few kilobytes), always read and
 * written whole, and must remain inspectable and recoverable after a crash. JSON files give
 * atomic writes (`tmp` + `renameTo`), trivial schema migration and zero extra dependencies.
 * The scan mesh is *not* stored here — it can be 10 MB and is regenerated or exported instead.
 *
 * Everything is on [Dispatchers.IO]; no file access ever happens on the main thread.
 */
class ProjectRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val directory: File by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }
    }

    data class ProjectSummary(
        val id: String,
        val name: String,
        val siteName: String,
        val routeCount: Int,
        val totalLengthMeters: Float,
        val updatedAtEpochMs: Long,
    )

    /** Lists projects, newest first. Corrupt files are skipped, never fatal. */
    suspend fun list(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { readProject(it) }
            ?.map { project ->
                ProjectSummary(
                    id = project.id,
                    name = project.name,
                    siteName = project.siteName,
                    routeCount = project.routes.size,
                    totalLengthMeters = project.totalLengthMeters,
                    updatedAtEpochMs = project.updatedAtEpochMs,
                )
            }
            ?.sortedByDescending { it.updatedAtEpochMs }
            ?: emptyList()
    }

    suspend fun load(id: String): ProjectRecord? = withContext(Dispatchers.IO) {
        readProject(fileFor(id))
    }

    /** Creates a project and returns it; the name is used verbatim (it is user content). */
    suspend fun create(name: String, siteName: String = ""): ProjectRecord = withContext(Dispatchers.IO) {
        val project = ProjectRecord.empty(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Room" },
        ).copy(siteName = siteName.trim())
        save(project)
        project
    }

    suspend fun save(project: ProjectRecord): Boolean = withContext(Dispatchers.IO) {
        val updated = project.copy(updatedAtEpochMs = System.currentTimeMillis())
        val target = fileFor(updated.id)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            temporary.writeText(json.encodeToString(ProjectRecord.serializer(), updated))
            // Atomic replace: a crash mid-write must never leave a truncated project.
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "could not remove old project file ${target.name}")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "saving project ${project.id} failed", error)
            temporary.delete()
            false
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        fileFor(id).delete()
    }

    /** Merges a finished scan summary into the stored project. */
    suspend fun updateScanSummary(id: String, summary: ScanSummary): ProjectRecord? =
        withContext(Dispatchers.IO) {
            val project = readProject(fileFor(id)) ?: return@withContext null
            val updated = project.copy(scan = summary)
            save(updated)
            updated
        }

    /** Where exports are staged before the operator picks a destination folder. */
    fun exportStagingDirectory(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun fileFor(id: String) = File(directory, "$id.json")

    private fun readProject(file: File): ProjectRecord? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            json.decodeFromString(ProjectRecord.serializer(), file.readText())
        } catch (error: Throwable) {
            Log.e(TAG, "cannot read ${file.name}", error)
            null
        }
    }

    companion object {
        private const val TAG = "ProjectRepository"
    }
}
