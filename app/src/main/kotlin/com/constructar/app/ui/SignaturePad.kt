/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.constructar.app.ui.theme.Jamaica

/**
 * A signature the customer leaves on the handover protocol.
 *
 * Deliberately the simplest thing that works on a site: one finger, no pressure sensitivity, no
 * undo stack — just strokes in the pad's own coordinate space, converted once into a bitmap when
 * the protocol is written. A glove does not have to come off, and a smudge on a glass screen does
 * not have to be corrected: the operator clears and the customer signs again.
 *
 * The bitmap is rendered black-on-white rather than in the theme colours, because it ends up on a
 * printed page (and in a PDF that a customer may forward to a lawyer).
 */
@Stable
class SignaturePadState {
    internal var strokes by mutableStateOf<List<List<Offset>>>(emptyList())
    internal var current by mutableStateOf<List<Offset>>(emptyList())
    internal var surface by mutableStateOf(IntSize.Zero)

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun clear() {
        strokes = emptyList()
        current = emptyList()
    }

    /**
     * Renders the strokes onto a white bitmap of [width] × [height] pixels.
     *
     * The pad is measured in device pixels and the target is a fixed size, so the strokes are
     * scaled — a signature that depends on the screen it was drawn on would print at a different
     * size on every phone.
     */
    fun bitmap(width: Int = 1_000, height: Int = 360): Bitmap? {
        val source = surface
        if (source.width <= 0 || source.height <= 0 || strokes.isEmpty()) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val scaleX = width.toFloat() / source.width
        val scaleY = height.toFloat() / source.height
        strokes.forEach { stroke ->
            val path = android.graphics.Path()
            stroke.forEachIndexed { index, point ->
                val x = point.x * scaleX
                val y = point.y * scaleY
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        return bitmap
    }
}

@Composable
fun rememberSignaturePadState(): SignaturePadState = remember { SignaturePadState() }

/**
 * The drawing surface. Stateless apart from the [state] it is handed, so the export screen can
 * read the strokes without the pad knowing anything about files or PDFs.
 */
@Composable
fun SignaturePad(state: SignaturePadState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Jamaica.Chalk, RoundedCornerShape(14.dp))
            .onSizeChanged { state.surface = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> state.current = listOf(offset) },
                    onDrag = { change, _ -> state.current = state.current + change.position },
                    onDragEnd = {
                        if (state.current.isNotEmpty()) {
                            state.strokes = state.strokes + listOf(state.current)
                        }
                        state.current = emptyList()
                    },
                    onDragCancel = { state.current = emptyList() },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ink = Color(0xFF11130D)
            val stroke = Stroke(width = 5f, cap = StrokeCap.Round)
            (state.strokes + listOf(state.current)).forEach { points ->
                if (points.size < 2) {
                    points.firstOrNull()?.let { drawCircle(ink, radius = 2.5f, center = it) }
                    return@forEach
                }
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, ink, style = stroke)
            }
        }
    }
}
