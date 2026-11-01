/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.constructar.core.io.SafeFiles
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitPresets
import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import com.constructar.core.model.WorkSession
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The storage layer, on a real filesystem, under Robolectric.
 *
 * Everything on site is a room measured over an hour and typed into a phone. The failure this
 * file exists to prevent is not a crash — it is a room that quietly disappears. So the tests
 * below are about *what survives*: a round trip through the JSON file, a restore that must not
 * overwrite the copy already on the phone, and a corrupt file that must not be deleted just
 * because it could not be parsed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: ProjectRepository

    private val projectsDirectory: File
        get() = File(context.filesDir, "projects")

    private val photoDirectory: File
        get() = File(context.filesDir, "photos")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Every test starts on an empty phone: Robolectric keeps `filesDir` per test, but a
        // leftover from a previous run in the same JVM would make the list assertions lie.
        File(context.filesDir, "projects").deleteRecursively()
        File(context.filesDir, "photos").deleteRecursively()
        repository = ProjectRepository(context)
    }

    private fun node(index: Int, x: Float) = RouteNodeRecord(
        id = "n$index",
        position = Vec3(x, 1.1f, -1.3f),
        normal = Vec3(0f, 0f, 1f),
        surface = SurfaceKind.WALL,
        hitSource = HitSource.PLANE_POLYGON,
        confidence = 0.9f,
    )

    private fun room() = ProjectRecord.empty(id = "room-1", name = "Küche").copy(
        siteName = "Seilerweg 14",
        technician = "A. Rodríguez",
        hourlyRateCents = 4_800,
        routes = listOf(
            ConduitRoute(
                id = "r1",
                label = "Kabel",
                spec = ConduitPresets.all[0],
                nodes = listOf(node(1, 0f), node(2, 2.5f)),
                createdAtEpochMs = 1_000L,
            ),
        ),
        sessions = listOf(WorkSession(id = "s1", startEpochMs = 1_000L, endEpochMs = 2_000L)),
    )

    @Test
    fun `a room survives a save and a load`(): Unit = runBlocking {
        val room = room()
        assertTrue(repository.save(room))

        val loaded = assertNotNull(repository.load(room.id))
        assertEquals("Küche", loaded.name)
        assertEquals("Seilerweg 14", loaded.siteName)
        assertEquals("A. Rodríguez", loaded.technician)
        assertEquals(4_800, loaded.hourlyRateCents)
        assertEquals(1, loaded.routes.size)
        assertEquals(2, loaded.routes.first().nodes.size)
        assertEquals(2.5f, loaded.routes.first().nodes[1].position.x, 1e-4f)
        assertEquals(1, loaded.sessions.size)
        assertEquals(2_000L, loaded.sessions.first().endEpochMs)
        assertTrue(loaded.updatedAtEpochMs >= room.createdAtEpochMs)
    }

    @Test
    fun `the list carries what the home screen shows`(): Unit = runBlocking {
        repository.save(room())

        val summary = repository.list().projects.single()
        assertEquals("room-1", summary.id)
        assertEquals("Küche", summary.name)
        assertEquals("Seilerweg 14", summary.siteName)
        assertEquals(1, summary.routeCount)
        assertEquals(2.5f, summary.totalLengthMeters, 1e-3f)
    }

    @Test
    fun `a blank room name falls back instead of producing an unnamed room`(): Unit = runBlocking {
        val created = repository.create(name = "   ", fallbackName = "Raum")
        assertEquals("Raum", created.name)
        assertNotNull(repository.load(created.id))
    }

    @Test
    fun `restoring a backup of a room that is still on the phone keeps both`(): Unit = runBlocking {
        val original = repository.save(room())
        assertTrue(original)

        val backupText = repository.backupEntries().single().text
        val restored = assertNotNull(repository.importProject(backupText))

        assertTrue(restored.id != "room-1", "the restored copy must get a fresh id")
        assertEquals(2, repository.list().projects.size)
        assertEquals("Küche", repository.load("room-1")?.name)
        assertEquals("Küche", repository.load(restored.id)?.name)
    }

    @Test
    fun `a re-keyed restore reports the id the file carried`(): Unit = runBlocking {
        // The room is still on the phone, so the restore stores a copy under a fresh id - and it
        // has to say which id the *file* had, because that is the name its photo was saved under.
        repository.save(room())
        val backupText = repository.backupEntries().single().text

        val restored = assertNotNull(repository.importProjectDetailed(backupText))

        assertEquals("room-1", restored.originalId)
        assertTrue(restored.project.id != "room-1", "the copy must get a fresh id")
        assertEquals(restored.project.id, repository.list().projects.map { it.id }.firstOrNull { it != "room-1" })
    }

    @Test
    fun `a restore onto an empty phone keeps the file's own id`(): Unit = runBlocking {
        repository.save(room())
        val backupText = repository.backupEntries().single().text
        File(projectsDirectory, "room-1.json").delete()

        val restored = assertNotNull(repository.importProjectDetailed(backupText))

        // Nothing to collide with: the photo in the zip is named room-1 and stays correct.
        assertEquals("room-1", restored.originalId)
        assertEquals("room-1", restored.project.id)
    }

    @Test
    fun `an empty file is unreadable, not an empty room`(): Unit = runBlocking {
        // A write interrupted at the wrong moment can leave a zero-byte file; listing it as a
        // room called "" would be worse than saying it could not be read.
        repository.save(room())
        File(projectsDirectory, "room-2.json").writeText("")

        val listing = repository.list()

        assertEquals(1, listing.projects.size)
        assertEquals(1, listing.unreadable)
    }

    @Test
    fun `text that is not a project is refused, and nothing is written`(): Unit = runBlocking {
        assertNull(repository.importProject("{\"not\": \"a project\"}"))
        assertNull(repository.importProject(""))
        assertTrue(repository.list().projects.isEmpty())
    }

    @Test
    fun `a corrupt file is reported as unreadable but is never deleted`(): Unit = runBlocking {
        repository.save(room())
        val file = File(projectsDirectory, "room-1.json")
        file.writeText("{ this is not json")

        assertNull(repository.load("room-1"))
        val listing = repository.list()
        assertTrue(listing.projects.isEmpty(), "an unreadable room cannot be listed")
        assertEquals(1, listing.unreadable, "and it is counted, so the home screen can say so")
        assertTrue(file.exists(), "the bytes are the operator's only copy - keep them")
        assertTrue(file.readText().startsWith("{ this is not json"))
    }

    @Test
    fun `a temporary file left behind by a kill is swept on first use, the room is not`(): Unit = runBlocking {
        repository.save(room())
        val stale = File(projectsDirectory, "room-2.json${SafeFiles.TEMPORARY_SUFFIX}")
        stale.writeText("half written")

        // A new instance is what the app does after a restart. The sweep is part of the first
        // touch of the projects directory - deliberately not of the constructor, so no file I/O
        // happens on whatever thread built the repository.
        val reopened = ProjectRepository(context)
        assertNotNull(reopened.list(), "the first read initialises the directory")
        assertFalse(stale.exists())
        assertNotNull(reopened.load("room-1"))
    }

    @Test
    fun `deleting a room deletes its photos and nothing else`(): Unit = runBlocking {
        repository.save(room())
        repository.save(room().copy(id = "room-2", name = "Bad"))
        photoDirectory.mkdirs()
        val sitePhoto = File(photoDirectory, "room-1.jpg").apply { writeText("jpg") }
        val diaryPhoto = File(photoDirectory, "room-1__diary_entry-1.jpg").apply { writeText("jpg") }
        val otherPhoto = File(photoDirectory, "room-2.jpg").apply { writeText("jpg") }

        assertTrue(repository.delete("room-1"))

        assertFalse(sitePhoto.exists())
        assertFalse(diaryPhoto.exists())
        assertTrue(otherPhoto.exists(), "deleting one room must not touch another")
        assertNotNull(repository.load("room-2"))
        assertNull(repository.load("room-1"))
    }

    @Test
    fun `the backup is a byte-for-byte copy of what the app wrote`(): Unit = runBlocking {
        repository.save(room())
        val onDisk = File(projectsDirectory, "room-1.json").readText()

        val entry = repository.backupEntries().single()
        assertEquals("room-1.json", entry.fileName)
        assertEquals(onDisk, entry.text)
    }

    @Test
    fun `an id in a restored file never becomes a path`(): Unit = runBlocking {
        repository.save(room())
        val original = repository.backupEntries().single().text
        val hostile = original.replace("\"room-1\"", "\"../../escape\"")

        val restored = assertNotNull(repository.importProject(hostile))

        // The original stays, and the restored copy lands under a fresh, safe id.
        assertEquals(2, repository.list().projects.size)
        assertEquals(1, projectsDirectory.listFiles()!!.count { it.name == "room-1.json" })
        assertTrue(restored.id != "../../escape", "the hostile id was kept")
        assertTrue(repository.isSafeToken(restored.id), "the stored id is not a plain token")
        assertEquals(2, projectsDirectory.listFiles()!!.size)
        assertFalse(File(context.filesDir, "escape.json").exists())
        assertFalse(File(context.filesDir.parentFile!!, "escape.json").exists())
    }

    @Test
    fun `only plain tokens are accepted as file names`() {
        assertTrue(repository.isSafeToken("3f2a8c1e-9b4d-4f6a-8c2e-1d5b7a9c0e3f"))
        assertTrue(repository.isSafeToken("room-1__diary_entry-1"))
        for (hostile in listOf("", "../escape", "a/b", "a\\b", "a.b", "..", "x".repeat(121))) {
            assertFalse(repository.isSafeToken(hostile), "$hostile was accepted")
        }
    }

    @Test
    fun `a picked photo whose name is a path is refused`(): Unit = runBlocking {
        photoDirectory.mkdirs()
        assertFalse(repository.savePhotoAs("../../escape", android.net.Uri.EMPTY))
        assertFalse(repository.deletePhotoAs("../../projects/room-1"))
        assertNull(repository.loadPhotoAs("../../escape"))
        assertEquals(0, context.filesDir.walkTopDown().count { it.isFile })
    }

    @Test
    fun `a picked file bigger than a project can be is refused`() {
        val real = "{\"id\":\"room-1\"}".toByteArray()
        assertEquals("{\"id\":\"room-1\"}", repository.readProjectText(real.inputStream()))

        val tooBig = ByteArray(ProjectRepository.MAX_PROJECT_BYTES + 1) { ' '.code.toByte() }
        assertNull(repository.readProjectText(tooBig.inputStream()), "an 8 MB project file is not a project")
    }
}
