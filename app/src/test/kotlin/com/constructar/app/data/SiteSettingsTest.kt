/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.constructar.core.geo.PolylineMetrics
import com.constructar.core.math.Vec3
import com.constructar.core.model.ConduitPresets
import com.constructar.core.model.HitSource
import com.constructar.core.model.RouteNodeRecord
import com.constructar.core.model.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The firm's two estimating numbers, stored once and used on every job.
 *
 * `null` is not a value here, it is the instruction "use the trade default", so the storage has to
 * keep the difference between "never set" and "set to something". A sentinel that could collide
 * with a real factor would silently turn a shop's choice into the default (findings 43, 151).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiteSettingsTest {

    private lateinit var settings: SiteSettings

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("constructar.settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = SiteSettings(context)
    }

    @Test
    fun `neither number is set on a fresh install`() {
        assertNull(settings.wasteFactorOverride())
        assertNull(settings.clipSpacingMetersOverride())
    }

    @Test
    fun `the clip spacing survives a new instance, and can be cleared again`() {
        settings.setClipSpacingOverride(0.30f)

        val reopened = SiteSettings(ApplicationProvider.getApplicationContext())
        assertEquals(0.30f, reopened.clipSpacingMetersOverride()!!, 0.0001f)

        reopened.setClipSpacingOverride(null)
        assertNull(SiteSettings(ApplicationProvider.getApplicationContext()).clipSpacingMetersOverride())
    }

    @Test
    fun `the waste factor survives a new instance, and can be cleared again`() {
        settings.setWasteFactorOverride(0.12f)

        val reopened = SiteSettings(ApplicationProvider.getApplicationContext())
        assertEquals(0.12f, reopened.wasteFactorOverride()!!, 0.0001f)

        reopened.setWasteFactorOverride(null)
        assertNull(SiteSettings(ApplicationProvider.getApplicationContext()).wasteFactorOverride())
    }

    @Test
    fun `a stored clip spacing is the spacing the engine counts with`() {
        // The point of the setting: 10 m of cable on a ceiling grid, clipped every metre, is
        // eleven clips - not the seventeen the 60 cm cable default would have ordered
        // (finding 151).
        settings.setClipSpacingOverride(1.0f)
        fun node(id: String, x: Float) = RouteNodeRecord(
            id = id,
            position = Vec3(x, 0f, 0f),
            normal = Vec3(0f, 0f, 1f),
            surface = SurfaceKind.WALL,
            hitSource = HitSource.PLANE_POLYGON,
        )

        val clips = PolylineMetrics.measure(
            nodes = listOf(node("n1", 0f), node("n2", 10f)),
            spec = ConduitPresets.byLabel("Cable Ø16")!!,
            clipSpacingMeters = settings.clipSpacingMetersOverride()!!,
        ).clipCount

        assertEquals(11, clips)
    }
}
