/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * The ring that shows a tap arrived *here* (finding 269).
 *
 * Not to be confused with [SnapMarker], which is a permanent state ("the reticle was pulled onto
 * an edge"). This one is an event: it expands once from the point that was touched and disappears
 * in [DURATION_MS]. The camera image is the one surface in the app where a tap has no button to
 * press - a wall is not a control and does not light up - so without this the only evidence of a
 * tap was a number in the corner, which is not where the operator is looking.
 *
 * Drawn where the finger was, never at the centre: the two tap paths differ (the big button samples
 * the reticle in the middle, a free tap samples the finger), and a ring in the wrong place would be
 * the same class of lie as a "Watching" badge over a dead camera.
 *
 * Nothing here is spoken. A Canvas has no text; the accessible version of the same fact is the
 * sentence in the slot under the step strip, which is a `liveRegion` and is read out.
 *
 * @param trigger increments once per tap; the animation restarts whenever it changes.
 * @param at the touched point, in pixels inside the AR surface.
 * @param tone the colour the result earns - lime for a point, warning for a miss.
 */
@Composable
internal fun TapEchoRing(
    trigger: Int,
    at: Offset,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    if (trigger <= 0) return
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = DURATION_MS, easing = LinearOutSlowInEasing),
        )
    }
    val p = progress.value
    if (p >= 1f) return
    Canvas(
        modifier = modifier
            .size(BOX)
            // The box is centred on the touch, so the ring grows out of the fingertip.
            .offset {
                IntOffset(
                    (at.x - BOX.toPx() / 2f).toInt(),
                    (at.y - BOX.toPx() / 2f).toInt(),
                )
            },
    ) {
        val maxRadius = size.minDimension / 2f
        // Hand-rolled interpolation rather than `lerp`: two floats and no import that a Compose
        // version bump can move.
        val radius = maxRadius * (0.20f + 0.74f * p)
        val width = 5.dp.toPx() * (1f - p) + 1.dp.toPx() * p
        // Fades as it expands: a ring that keeps its weight reads as a target being acquired,
        // which is exactly what this is not.
        drawCircle(
            color = tone.copy(alpha = 1f - p),
            radius = radius,
            style = Stroke(width = width),
        )
    }
}

/** The ring's box; large enough that the outermost frame is not clipped. */
private val BOX = 120.dp

/** One expansion, long enough to see from arm's length, short enough to precede the next tap. */
private const val DURATION_MS = 420
