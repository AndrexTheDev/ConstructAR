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
import com.constructar.core.model.FloorplanSketch
import com.constructar.core.model.HitSource
import com.constructar.core.model.ProjectRecord
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SketchPoint
import com.constructar.core.model.SurfaceKind
import com.constructar.core.model.WorkSession
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
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
    fun `a room written by a newer app is refused instead of half-read`(): Unit = runBlocking {
        repository.save(room())
        val text = repository.backupEntries().single().bytes!!.decodeToString()
        val newer = text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertTrue(newer != text, "the fixture must carry a bumped schema version")

        assertNull(repository.parseProject(newer), "a newer file must not be parsed")
        assertNull(repository.importProject(newer), "a newer file must not be imported")
        assertEquals(
            1,
            repository.list().projects.size,
            "a refused file must leave the phone exactly as it was",
        )

        assertNotNull(repository.parseProject(text), "the same room at this version is readable")
        assertNotNull(repository.importProject(text), "and still restorable")
        assertEquals(2, repository.list().projects.size)
    }

    @Test
    fun `restoring a backup of a room that is still on the phone keeps both`(): Unit = runBlocking {
        val original = repository.save(room())
        assertTrue(original)

        val backupText = repository.backupEntries().single().bytes!!.decodeToString()
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
        val backupText = repository.backupEntries().single().bytes!!.decodeToString()

        val restored = assertNotNull(repository.importProjectDetailed(backupText))

        assertEquals("room-1", restored.originalId)
        assertTrue(restored.project.id != "room-1", "the copy must get a fresh id")
        assertEquals(restored.project.id, repository.list().projects.map { it.id }.firstOrNull { it != "room-1" })
    }

    @Test
    fun `a restore onto an empty phone keeps the file's own id`(): Unit = runBlocking {
        repository.save(room())
        val backupText = repository.backupEntries().single().bytes!!.decodeToString()
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
    fun `the outline survives a save and a load, and reaches the list`(): Unit = runBlocking {
        val outline = FloorplanSketch(
            listOf(
                SketchPoint(0f, 0f),
                SketchPoint(4f, 0f),
                SketchPoint(4f, 3f),
                SketchPoint(0f, 3f),
            ),
        )
        val project = repository.create("Hall").let { repository.save(it.copy(sketch = outline)); it }

        val loaded = repository.load(project.id)

        assertNotNull(loaded)
        assertEquals(outline, loaded?.sketch)
        // The home screen draws from the summary, so the outline has to reach it through `list()`.
        assertEquals(outline, repository.list().projects.single().sketch)
    }

    @Test
    fun `a room without an outline writes no outline key and loads without one`(): Unit = runBlocking {
        val project = repository.create("Old room")

        val text = File(projectsDirectory, "${project.id}.json").readText()

        // `explicitNulls = false`, so a room nobody has scanned yet carries no key at all - which is
        // exactly the shape a project file written before the field existed has.
        assertFalse(text.contains("sketch"), "a null outline must not be written")
        assertNull(repository.load(project.id)?.sketch)
    }

    @Test
    fun `the card picture lands in the room's own folder`(): Unit = runBlocking {
        val project = repository.create("Kitchen")

        val saved = repository.saveThumbnail(project.id, "<svg/>")

        assertTrue(saved, "the write must land")
        val file = File(File(projectsDirectory, project.id), RoomThumbnail.FILE_NAME)
        assertTrue(file.isFile, "expected ${file.path}")
        assertEquals("<svg/>", file.readText())
        // The list reads the projects folder, so a per-room folder must not look like a room.
        assertEquals(1, repository.list().projects.size)
        assertEquals(0, repository.list().unreadable)
    }

    @Test
    fun `a thumbnail id that could reach another folder is refused`(): Unit = runBlocking {
        assertFalse(repository.saveThumbnail("../escape", "<svg/>"))
        assertFalse(File(context.filesDir, "escape").exists())
        assertFalse(File(context.filesDir, "projects/../escape").exists())
    }

    @Test
    fun `an oversized picture is refused rather than written`(): Unit = runBlocking {
        val project = repository.create("Workshop")
        val huge = "x".repeat(ProjectRepository.MAX_THUMBNAIL_BYTES + 1)

        assertFalse(repository.saveThumbnail(project.id, huge))
        assertFalse(repository.thumbnailFileFor(project.id).exists())
    }

    @Test
    fun `deleting a room takes its picture with it`(): Unit = runBlocking {
        val project = repository.create("Attic")
        repository.saveThumbnail(project.id, "<svg/>")
        val file = repository.thumbnailFileFor(project.id)
        assertTrue(file.isFile)

        repository.delete(project.id)

        assertFalse(file.exists(), "an orphaned picture is a picture of a room that is gone")
        assertFalse(File(projectsDirectory, project.id).exists())
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
    fun `the files that cannot be read are named, not only counted`(): Unit = runBlocking {
        // A count told the operator something was wrong and gave him nothing to do about it; on
        // the free plan a damaged file holds the only room, so its name is the way out
        // (finding 150).
        repository.save(room())
        File(projectsDirectory, "broken-a.json").writeText("{ not json")
        File(projectsDirectory, "broken-b.json").writeText("")

        val listing = repository.list()

        assertEquals(listOf("broken-a.json", "broken-b.json"), listing.unreadableNames)
        assertEquals(2, listing.unreadable)
    }

    @Test
    fun `deleting a damaged file frees its room, and the list says so`(): Unit = runBlocking {
        // The folder is created by the repository's first touch, not by the constructor.
        projectsDirectory.mkdirs()
        File(projectsDirectory, "broken.json").writeText("{ not json")

        assertTrue(repository.deleteUnreadable("broken.json"))
        assertFalse(File(projectsDirectory, "broken.json").exists())
        assertEquals(0, repository.list().unreadable)
        assertTrue(repository.list().unreadableNames.isEmpty())
    }

    @Test
    fun `a file that can be read is not deleted through the damaged-file path`(): Unit = runBlocking {
        // A room is deleted from the screen that names the room, its photos and its hours
        // (finding 125). This path exists for the file that screen cannot show.
        repository.save(room())

        assertFalse(repository.deleteUnreadable("room-1.json"))
        assertTrue(File(projectsDirectory, "room-1.json").exists())
        assertEquals(1, repository.list().projects.size)
    }

    @Test
    fun `a name that is not a plain file name in this folder is refused`(): Unit = runBlocking {
        val outside = File(context.filesDir, "outside.json").apply { writeText("{ not json") }

        assertFalse(repository.deleteUnreadable("../outside.json"))
        assertFalse(repository.deleteUnreadable("outside.json"))
        assertFalse(repository.deleteUnreadable("broken.txt"))
        assertFalse(repository.deleteUnreadable(""))
        assertTrue(outside.exists(), "nothing outside the projects folder is touched")
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
        assertEquals(onDisk, entry.bytes!!.decodeToString())
    }

    @Test
    fun `the backup keeps a damaged byte exactly as it is`(): Unit = runBlocking {
        // A room file with one byte that is not valid UTF-8 - the shape a half-written file or a
        // filesystem repair leaves behind, and the reason the list refuses to parse it while
        // keeping the bytes (findings 150, 153).
        projectsDirectory.mkdirs()
        val damaged = byteArrayOf(
            '{'.code.toByte(), '"'.code.toByte(), 'x'.code.toByte(), '"'.code.toByte(),
            0xC3.toByte(), 0x28.toByte(),
        )
        File(projectsDirectory, "damaged.json").writeBytes(damaged)

        val entry = repository.backupEntries().single()

        // The backup is the file: same bytes, same length - not a decode and a re-encode.
        assertContentEquals(damaged, entry.bytes!!)
        // And the difference is not academic: decoding would have produced three bytes where the
        // file had two, and the operator's only copy would come back altered.
        assertFalse(
            String(entry.bytes, Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(damaged),
            "a text round trip must not be mistaken for a copy",
        )
    }

    @Test
    fun `a project file that cannot be read is reported, not dropped`(): Unit = runBlocking {
        // A directory where a project file should be: `listFiles` sees it, `readBytes` cannot read
        // it. The entry stays in the list with no bytes, so the card's `available - written`
        // counts it as a room that is not in the folder.
        repository.save(room())
        val unreadable = File(projectsDirectory, "a-broken.json")
        assertTrue(unreadable.mkdirs())

        val entries = repository.backupEntries()

        assertEquals(listOf("a-broken.json", "room-1.json"), entries.map { it.fileName })
        assertNull(entries.first { it.fileName == "a-broken.json" }.bytes)
        assertNotNull(entries.first { it.fileName == "room-1.json" }.bytes)
    }

    @Test
    fun `an id in a restored file never becomes a path`(): Unit = runBlocking {
        repository.save(room())
        val original = repository.backupEntries().single().bytes!!.decodeToString()
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
