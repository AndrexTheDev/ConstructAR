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
import com.constructar.core.io.SafeFiles
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteDraft
import com.constructar.core.model.WorkTime
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
        File(context.filesDir, "projects").apply {
            mkdirs()
            // A `.tmp` file here means a previous write was killed mid-flight; the target it
            // would have replaced is still intact, so the leftover is simply removed.
            SafeFiles.clearStaleTemporaries(this)
        }
    }

    /**
     * Drafts of runs that are still being measured, one file per project.
     *
     * A separate directory from the projects on purpose: a draft is not a project and must never
     * be listed as one, and a backup must not carry half a measurement as if it were a room.
     */
    private val draftDirectory: File by lazy {
        File(context.filesDir, "drafts").apply {
            mkdirs()
            SafeFiles.clearStaleTemporaries(this)
        }
    }

    data class ProjectSummary(
        val id: String,
        val name: String,
        val siteName: String,
        val routeCount: Int,
        val totalLengthMeters: Float,
        val updatedAtEpochMs: Long,
        /** Defects still open — the list shows the count so a punch list cannot be forgotten. */
        val openDefects: Int = 0,
        /** True while this room's clock is still running. */
        val workRunning: Boolean = false,
    )

    /**
     * What the folder holds: the rooms that can be read, and how many files could not.
     *
     * The count is the point. A file is kept when it cannot be parsed - nothing here ever deletes
     * a room it does not understand - but until now it was simply *absent* from the list, so a
     * damaged project looked exactly like a deleted one and the operator had no way to know that
     * his data was still on the phone.
     */
    data class Listing(
        val projects: List<ProjectSummary> = emptyList(),
        /** Files in the projects folder that could not be read as a project. */
        val unreadable: Int = 0,
    )

    /** Lists projects, newest first. Corrupt files are reported, never fatal. */
    suspend fun list(): Listing = withContext(Dispatchers.IO) {
        val files = directory.listFiles { file -> file.extension == "json" }.orEmpty()
        val summaries = ArrayList<ProjectSummary>(files.size)
        var unreadable = 0
        for (file in files) {
            val project = readProject(file)
            if (project == null) {
                unreadable++
            } else {
                summaries += ProjectSummary(
                    id = project.id,
                    name = project.name,
                    siteName = project.siteName,
                    routeCount = project.routes.size,
                    totalLengthMeters = project.totalLengthMeters,
                    updatedAtEpochMs = project.updatedAtEpochMs,
                    openDefects = project.openDefectCount,
                    workRunning = WorkTime.isRunning(project.sessions),
                )
            }
        }
        Listing(projects = summaries.sortedByDescending { it.updatedAtEpochMs }, unreadable = unreadable)
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

    /**
     * Saves one project.
     *
     * The write itself is [SafeFiles.writeAtomically]: temporary file, `fsync`, then an atomic
     * rename. The previous version deleted the target before renaming, which left a window in
     * which a kill - or a battery that gave up - lost a whole room.
     */
    suspend fun save(project: ProjectRecord): Boolean = withContext(Dispatchers.IO) {
        val updated = project.copy(updatedAtEpochMs = System.currentTimeMillis())
        val text = runCatching {
            json.encodeToString(ProjectRecord.serializer(), updated)
        }.getOrNull() ?: return@withContext false
        val saved = SafeFiles.writeAtomically(fileFor(updated.id), text)
        if (!saved) Log.e(TAG, "saving project ${project.id} failed")
        saved
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

    /** The stored site photos, for the backup. Named `<project id>.jpg`. */
    suspend fun photoFiles(): List<File> = withContext(Dispatchers.IO) {
        photoDirectory.listFiles()?.filter { it.isFile && it.length() > 0L }?.sortedBy { it.name }.orEmpty()
    }

    /** Whether a project with [id] exists — used to re-attach a restored photo to its project. */
    suspend fun hasProject(id: String): Boolean = withContext(Dispatchers.IO) { fileFor(id).exists() }

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
        // The id becomes a file name, and this text came from a file somebody handed over.
        // An id of "../../databases/x" must never reach `File(directory, "$id.json")`, so an
        // id that is not a plain token is replaced - the project is still imported, under a
        // fresh identity, exactly like the duplicate case below.
        val target = if (!isSafeToken(parsed.id) || fileFor(parsed.id).exists()) {
            parsed.copy(id = UUID.randomUUID().toString())
        } else {
            parsed
        }
        if (save(target)) target else null
    }

    /**
     * Whether a stored name can be used as a file name.
     *
     * Ids and photo tokens both come from outside: a project id from a restored JSON file, a
     * photo token from the *file name* of a picked picture. Both are appended to a directory
     * path, so a value like `../databases/app` would write - or delete - outside the folder
     * this repository owns. Letters, digits, dash and underscore only; no dots, no separators.
     */
    fun isSafeToken(value: String): Boolean =
        value.isNotEmpty() && value.length <= 120 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * Text of a picked project file, or `null` when it is too large to be one.
     *
     * A restore reads whatever the operator picked, and the picker accepts every MIME type
     * (a `.json` file is reported as three different types depending on the file manager).
     * Reading a 2 GB video into a `String` is an out-of-memory crash in the middle of a
     * restore; a project with a scan summary is a few hundred kilobytes, so the cap is far
     * above anything real and far below anything dangerous.
     */
    fun readProjectText(input: java.io.InputStream): String? = runCatching {
        val buffer = java.io.ByteArrayOutputStream(64 * 1024)
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            total += read
            if (total > MAX_PROJECT_BYTES) return null
            buffer.write(chunk, 0, read)
        }
        String(buffer.toByteArray(), Charsets.UTF_8)
    }.getOrNull()

    // ── Drafts of runs in progress ──────────────────────────────────────────────────────────

    /**
     * Writes the run that is being measured, so a killed process does not take it.
     *
     * Called on every committed point, so the write has to be cheap and atomic - it is the same
     * `SafeFiles.writeAtomically` path a project uses (temporary file, `fsync`, rename), which
     * means a kill in the middle leaves the previous draft intact rather than a half-written one.
     * A draft with fewer than two points is a mis-tap, not a measurement: it is deleted instead.
     */
    suspend fun saveDraft(draft: RouteDraft): Boolean = withContext(Dispatchers.IO) {
        if (!isSafeToken(draft.projectId)) return@withContext false
        if (!draft.isRecoverable) return@withContext deleteDraft(draft.projectId)
        val text = runCatching {
            json.encodeToString(RouteDraft.serializer(), draft)
        }.getOrNull() ?: return@withContext false
        SafeFiles.writeAtomically(draftFileFor(draft.projectId), text)
    }

    /** The stored draft for [projectId], or `null` when there is none (or it is unreadable). */
    suspend fun loadDraft(projectId: String): RouteDraft? = withContext(Dispatchers.IO) {
        if (!isSafeToken(projectId)) return@withContext null
        val file = draftFileFor(projectId)
        if (!file.isFile) return@withContext null
        runCatching {
            json.decodeFromString(RouteDraft.serializer(), file.readText())
        }.getOrNull()?.takeIf { it.isRecoverable }
    }

    suspend fun deleteDraft(projectId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isSafeToken(projectId)) return@withContext false
        val file = draftFileFor(projectId)
        !file.exists() || file.delete()
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!isSafeToken(id)) return@withContext false
        // The site photo is `<id>.jpg`, an entry photo is `<id>__diary_<entry>.jpg`; deleting a
        // project must not leave either of them orphaned in private storage.
        photoDirectory.listFiles()
            ?.filter { it.name == "$id.jpg" || it.name.startsWith("${id}__") }
            ?.forEach { it.delete() }
        val removed = fileFor(id).delete()
        // A draft belongs to exactly one project; deleting the room must not leave it behind to
        // be offered again on the next start (with nothing to attach it to). The result of *that*
        // delete is not the answer to "was the room deleted": with no draft it is `false`.
        draftFileFor(id).delete()
        removed
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

    /**
     * Any stored photo by its token.
     *
     * A token is `<project id>` for the documentation photo of the room, or
     * `<project id>__diary_<entry id>` / `<project id>__defect_<entry id>` for an entry photo.
     * The project id is always the part before `__`, which is what makes a backup restorable:
     * every photo can be matched back to the room it belongs to from its file name alone.
     */
    fun photoFileByName(token: String): File = File(photoDirectory, "$token.jpg")

    /** Copies a picked picture into private storage under [token]; refused for an unsafe one. */
    suspend fun savePhotoAs(token: String, source: Uri): Boolean = withContext(Dispatchers.IO) {
        if (!isSafeToken(token)) return@withContext false
        runCatching {
            val target = photoFileByName(token)
            val copied = context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) target.delete()
            copied
        }.getOrDefault(false)
    }

    fun deletePhotoAs(token: String): Boolean =
        isSafeToken(token) && photoFileByName(token).delete()

    /**
     * Decodes a stored photo at a size fit for its purpose.
     *
     * Thumbnails in the site file are 640 px; the export re-reads the same file larger for the
     * appendix. Sampling is power-of-two, so the working set stays a few megabytes either way.
     */
    suspend fun loadPhotoAs(token: String, maxDimensionPx: Int = 640): Bitmap? =
        withContext(Dispatchers.IO) {
            if (!isSafeToken(token)) return@withContext null
            decodeSampled(photoFileByName(token), maxDimensionPx)
        }

    private fun decodeSampled(file: File, maxDimensionPx: Int): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDimensionPx || bounds.outHeight / (sample * 2) >= maxDimensionPx) {
                sample *= 2
            }
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    /** Copies a picture the camera app wrote into our cache; the project keeps one photo. */
    suspend fun savePhoto(projectId: String, source: Uri): Boolean = savePhotoAs(projectId, source)

    /**
     * Decodes the photo at a size fit for a screen and an A4 page.
     *
     * A modern phone camera writes 12 MP; decoding that at full size would be ~48 MB twice over
     * (thumbnail + PDF). Sampling keeps the working set at a few megabytes, and a protocol page
     * cannot use more detail than this anyway.
     */
    suspend fun loadPhoto(projectId: String, maxDimensionPx: Int = 1_200): Bitmap? =
        loadPhotoAs(projectId, maxDimensionPx)

    /** Where exports are staged before the operator picks a destination folder. */
    fun exportStagingDirectory(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun fileFor(id: String) = File(directory, "$id.json")

    private fun draftFileFor(id: String) = File(draftDirectory, "$id.json")

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

        /** Generous for a project file, small enough that a wrong pick cannot exhaust the heap. */
        const val MAX_PROJECT_BYTES: Int = 8 * 1024 * 1024
    }
}
