/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitSpec
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteDraft
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
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
 * The draft of a run that is still being measured, on a real filesystem.
 *
 * The trade this file guards is asymmetric: a lost draft costs the operator a wall he has to
 * measure again, while a *wrong* draft — one offered under someone else's project, or one that
 * reappears after he deliberately discarded it — costs him trust in every number on the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftStoreTest {

    private lateinit var context: Context
    private lateinit var repository: ProjectRepository

    private val spec = ConduitSpec.cable(16f, "Cable Ø16")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ProjectRepository(context)
    }

    private fun draft(projectId: String, points: Int): RouteDraft = RouteDraft(
        projectId = projectId,
        spec = spec,
        nodes = (0 until points).map { i ->
            RouteNodeRecord(
                id = "n$i",
                position = Vec3(i.toFloat(), 0f, 0f),
                normal = Vec3(0f, 0f, 1f),
                surface = SurfaceKind.WALL,
                hitSource = HitSource.PLANE_POLYGON,
            )
        },
        savedAtEpochMs = 1_793_491_200_000L,
    )

    private fun draftFile(projectId: String) = File(File(context.filesDir, "drafts"), "$projectId.json")

    @Test
    fun `a run in progress comes back exactly as it was written`() = runBlocking {
        val written = draft("p1", 3)

        assertTrue(repository.saveDraft(written))
        val loaded = assertNotNull(repository.loadDraft("p1"))

        assertEquals(written.nodes.map { it.position }, loaded.nodes.map { it.position })
        assertEquals(written.closedLoop, loaded.closedLoop)
        assertEquals(written.spec.sizeLabel, loaded.spec.sizeLabel)
        // Points at 0, 1 and 2 m: two 1 m segments.
        assertEquals(2f, loaded.lengthMeters, 0.001f)
    }

    @Test
    fun `a single tap is not stored at all`() = runBlocking {
        // Saving one point would offer a mis-tap as a measurement on the next start.
        repository.saveDraft(draft("p2", 1))

        assertFalse(draftFile("p2").exists())
        assertNull(repository.loadDraft("p2"))
    }

    @Test
    fun `a draft cannot escape the folder it owns`() = runBlocking {
        val hostile = draft("../../databases/x", 2)

        assertFalse(repository.saveDraft(hostile))
        assertNull(repository.loadDraft("../../databases/x"))
        assertFalse(File(context.filesDir.parentFile!!, "databases").exists())
    }

    @Test
    fun `a discarded draft does not come back`() = runBlocking {
        repository.saveDraft(draft("p3", 4))

        assertTrue(repository.deleteDraft("p3"))
        assertNull(repository.loadDraft("p3"))
    }

    @Test
    fun `an unreadable draft is not offered and is not deleted`() = runBlocking {
        // A half-written or truncated file must never be mistaken for a measurement - and must
        // not be thrown away either, because the operator may still want it looked at.
        val file = draftFile("p4")
        file.parentFile?.mkdirs()
        file.writeText("{ this is not a draft")

        assertNull(repository.loadDraft("p4"))
        assertTrue(file.exists())
    }

    @Test
    fun `deleting the project takes its draft with it`() = runBlocking {
        // Otherwise the next start offers a run for a room that no longer exists.
        val project = repository.create(name = "Keller", fallbackName = "Room")
        repository.saveDraft(draft(project.id, 3))

        repository.delete(project.id)

        assertNull(repository.loadDraft(project.id))
        assertFalse(draftFile(project.id).exists())
    }
}
