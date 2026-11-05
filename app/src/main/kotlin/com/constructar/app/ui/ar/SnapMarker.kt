/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.constructar.app.ui.theme.SiteColors

/**
 * The ring the operator sees when the reticle was moved onto a corner or an edge.
 *
 * A snapped point is a *different* point from the one he aimed at - up to ten centimetres away -
 * and until now the only trace of that was the number under the crosshair changing between two
 * taps. A tool that moves your point has to say so, at the place it moved it: the ring sits where
 * the reticle already samples (the centre of the AR view), in the accent colour, so "the reticle
 * jumped" stops being a mystery and starts being the app telling him it found the corner.
 *
 * Drawn only while a snap is in force - a permanent crosshair would be a control, and this is an
 * annotation. Nothing here is spoken: the HUD line above the controls is the accessible version of
 * the same fact (`SnapFeatureKind.snapMovedRes` plus `SnapReading.moved`), because a Canvas has no
 * text and a screen reader cannot read a ring.
 */
@Composable
internal fun SnapMarker(active: Boolean, modifier: Modifier = Modifier) {
    if (!active) return
    Canvas(modifier.size(30.dp)) {
        val stroke = 2.dp.toPx()
        val radius = size.minDimension / 2f - stroke
        drawCircle(
            color = SiteColors.accent,
            radius = radius,
            style = Stroke(width = stroke),
        )
        // Four ticks on the axes: a plain ring reads as a target, the ticks read as "locked on".
        val tick = 5.dp.toPx()
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawLine(SiteColors.accent, Offset(centre.x, centre.y - radius), Offset(centre.x, centre.y - radius + tick), stroke)
        drawLine(SiteColors.accent, Offset(centre.x, centre.y + radius), Offset(centre.x, centre.y + radius - tick), stroke)
        drawLine(SiteColors.accent, Offset(centre.x - radius, centre.y), Offset(centre.x - radius + tick, centre.y), stroke)
        drawLine(SiteColors.accent, Offset(centre.x + radius, centre.y), Offset(centre.x + radius - tick, centre.y), stroke)
    }
}
