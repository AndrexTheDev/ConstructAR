/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import androidx.compose.ui.graphics.Color
import com.constructar.app.ui.theme.Jamaica
import com.constructar.arengine.render.SceneViewMaterialProvider
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dots in the wall are drawn in the same lime the screen uses for "this is a measurement"
 * (finding 269).
 *
 * The colour lives twice - as a Compose [Color] for the glass and as an ARGB integer for the
 * renderer - because the two layers cannot share a type. That is exactly how the scan overlay and
 * its legend drifted apart before `MeasureModeTest` pinned them, and a node marker in a colour the
 * HUD never uses is a dot the operator cannot connect to the number he just read.
 *
 * Compared channel by channel rather than through `toArgb()`: this is a plain JVM test with no
 * Android runtime, and the conversion helper is an `androidMain` function.
 */
class NodeMarkerColourTest {

    @Test
    fun `the node markers are the accent lime`() {
        assertSameColour(Jamaica.Lime, SceneViewMaterialProvider.NODE_MARKER_ARGB)
    }

    @Test
    fun `the markers are opaque, so a dark corner cannot swallow a point`() {
        assertEquals(255, (SceneViewMaterialProvider.NODE_MARKER_ARGB ushr 24) and 0xFF)
    }

    private fun assertSameColour(expected: Color, argb: Int) {
        assertEquals((expected.alpha * 255f).roundToInt(), (argb ushr 24) and 0xFF)
        assertEquals((expected.red * 255f).roundToInt(), (argb ushr 16) and 0xFF)
        assertEquals((expected.green * 255f).roundToInt(), (argb ushr 8) and 0xFF)
        assertEquals((expected.blue * 255f).roundToInt(), argb and 0xFF)
    }
}
