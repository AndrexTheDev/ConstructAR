/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
    /**
     * @param fallbackName name to use when [name] is blank. The caller passes the *localised*
     *   default (`home_default_room_name`); the hard-coded "Room" is only the last resort for a
     *   caller with no resources, and exists so a project can never be stored nameless.
     */
    suspend fun create(
        name: String,
        siteName: String = "",
        fallbackName: String = "Room",
    ): ProjectRecord = withContext(Dispatchers.IO) {
        val project = ProjectRecord.empty(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { fallbackName.ifBlank { "Room" } },
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

    /** One project file, verbatim — the unit of a backup. */
    data class BackupEntry(val fileName: String, val text: String)

    /**
     * Every project as its raw JSON, for the operator's own backup.
     *
     * The files are copied verbatim rather than re-serialised: a backup must be a byte-for-byte
     * copy of what the app wrote, so a restore of an older app version stays possible and so a
     * corrupted-but-readable file is not silently "fixed" into something else.
     */
    suspend fun backupEntries(): List<BackupEntry> = withContext(Dispatchers.IO) {
        directory.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching { BackupEntry(file.name, file.readText()) }.getOrNull()
            }
            .orEmpty()
    }

    /**
     * Imports one project file.
     *
     * A project whose id is already present gets a fresh id instead of overwriting: restoring a
     * backup onto a phone that still has the original must not destroy either copy.
     * Returns the stored project, or `null` when the text is not a project.
     */
    suspend fun importProject(text: String): ProjectRecord? = withContext(Dispatchers.IO) {
        val parsed = runCatching {
            json.decodeFromString(ProjectRecord.serializer(), text)
        }.getOrNull() ?: return@withContext null
        val target = if (fileFor(parsed.id).exists()) {
            parsed.copy(id = UUID.randomUUID().toString())
        } else {
            parsed
        }
        if (save(target)) target else null
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        photoFile(id).delete()
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

    /**
     * The documentation photo of a project, if the operator took one.
     *
     * Stored by convention (`photos/<project id>.jpg`) rather than as a path inside the project
     * JSON: the file is a rendering of the site, not data the protocol depends on, and a project
     * that lost its photo must still open, export and bill correctly.
     */
    fun photoFile(id: String): File = File(photoDirectory, "$id.jpg")

    /** Copies a picture the camera app wrote into our cache; the project keeps one photo. */
    suspend fun savePhoto(projectId: String, source: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = photoFile(projectId)
            val copied = context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) target.delete()
            copied
        }.getOrDefault(false)
    }

    /**
     * Decodes the photo at a size fit for a screen and an A4 page.
     *
     * A modern phone camera writes 12 MP; decoding that at full size would be ~48 MB twice over
     * (thumbnail + PDF). Sampling keeps the working set at a few megabytes, and a protocol page
     * cannot use more detail than this anyway.
     */
    suspend fun loadPhoto(projectId: String, maxDimensionPx: Int = 1_200): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = photoFile(projectId)
            if (!file.exists() || file.length() == 0L) return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxDimensionPx || bounds.outHeight / (sample * 2) >= maxDimensionPx) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }

    /** Where exports are staged before the operator picks a destination folder. */
    fun exportStagingDirectory(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun fileFor(id: String) = File(directory, "$id.json")

    private val photoDirectory: File by lazy {
        File(context.filesDir, "photos").apply { mkdirs() }
    }

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
