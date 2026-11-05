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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.ui.theme.Jamaica

/**
 * A signature the customer leaves on the handover protocol.
 *
 * Deliberately the simplest thing that works on a site: one finger, no pressure sensitivity, no
 * undo stack — just strokes, converted once into a bitmap when the protocol is written. A glove
 * does not have to come off, and a smudge on a glass screen does not have to be corrected: the
 * operator clears and the customer signs again.
 *
 * Points are stored **normalised to the pad** (0..1 on both axes) rather than in pixels, for two
 * reasons that are the same reason: the phone is turned while the customer is signing, and the
 * system may recreate the screen while her name is on it. In pixels the strokes would survive
 * neither - they would be squashed into the new width, or gone entirely (finding 77). Normalised
 * points are also what makes the pad a `rememberSaveable` (see the saver below).
 *
 * The bitmap is rendered black-on-white rather than in the theme colours, because it ends up on a
 * printed page (and in a PDF that a customer may forward to a lawyer).
 */
@Stable
class SignaturePadState {
    /** Finished strokes, each point normalised to 0..1 of the pad's width and height. */
    internal var strokes by mutableStateOf<List<List<Offset>>>(emptyList())
    internal var current by mutableStateOf<List<Offset>>(emptyList())
    internal var surface by mutableStateOf(IntSize.Zero)

    val isEmpty: Boolean get() = strokes.isEmpty()

    /**
     * Commits a finished stroke - and a stroke of one point is a finished stroke.
     *
     * One function for both input paths, because the pad has two: a drag and a tap. The renderers
     * have drawn a dot for a one-point stroke since finding 41, but the only path that produced
     * strokes was `detectDragGestures`, which reports nothing until the finger has moved past the
     * touch slop - so a customer who put a finger on the glass and lifted it again signed nothing,
     * the pad stayed empty, and the protocol went out with no signature page at all (finding 82).
     */
    internal fun commit(points: List<Offset>) {
        if (points.isEmpty()) return
        strokes = strokes + listOf(points)
    }

    fun clear() {
        strokes = emptyList()
        current = emptyList()
    }

    /**
     * A touch in pad pixels as a point of the pad, so it no longer depends on how large the pad is.
     *
     * A zero-sized pad has no meaningful fraction - it cannot be touched before it is measured -
     * so anything arriving then is anchored at the origin rather than divided by zero.
     */
    internal fun normalise(touch: Offset): Offset =
        if (surface.width <= 0 || surface.height <= 0) {
            Offset.Zero
        } else {
            Offset(touch.x / surface.width, touch.y / surface.height)
        }

    /**
     * Renders the strokes onto a white bitmap of [width] × [height] pixels.
     *
     * The strokes are fractions of the pad, so a fixed page size is all the scaling there is — a
     * signature that depended on the screen it was drawn on would print at a different size on
     * every phone, and one drawn before the phone was turned would print squashed.
     */
    fun bitmap(width: Int = 1_000, height: Int = 360): Bitmap? {
        if (strokes.isEmpty()) return null
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
        // The points are fractions of the pad, so the page size is the whole transform - the pad
        // it was signed on, and whether the phone was turned in the meantime, do not matter.
        val scaleX = width.toFloat()
        val scaleY = height.toFloat()
        // The pad draws a dot for a one-point stroke, so the page has to: a customer who touches
        // the glass instead of dragging signed something the app accepted and the protocol
        // printed blank (finding 41). The dot is as thick as a stroke, so a tap reads as ink
        // rather than as a smudge - and it no longer depends on the pad's pixel size.
        val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.FILL
        }
        val dotRadius = 3f
        strokes.forEach { stroke ->
            val path = android.graphics.Path()
            stroke.forEachIndexed { index, point ->
                val x = point.x * scaleX
                val y = point.y * scaleY
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (stroke.size < 2) {
                stroke.firstOrNull()?.let { point ->
                    canvas.drawCircle(point.x * scaleX, point.y * scaleY, dotRadius, dot)
                }
            } else {
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }
}

/**
 * Packs the strokes into a `FloatArray` a `Bundle` can carry: per stroke its point count, then the
 * x/y pairs.
 *
 * A signature is drawn while the phone is in the customer's hand, so the system can recreate the
 * screen at any moment (a turn, a font-size change, the process being trimmed). Keeping the strokes
 * only in `remember` meant the page came back blank and the protocol went out unsigned without a
 * word.
 */
internal fun encodeStrokes(strokes: List<List<Offset>>): FloatArray {
    val out = ArrayList<Float>(strokes.size * 8)
    strokes.forEach { stroke ->
        out += stroke.size.toFloat()
        stroke.forEach { point ->
            out += point.x
            out += point.y
        }
    }
    return out.toFloatArray()
}

/** Inverse of [encodeStrokes]; anything malformed is dropped rather than half-restored. */
internal fun decodeStrokes(values: FloatArray?): List<List<Offset>> {
    if (values == null || values.isEmpty()) return emptyList()
    val strokes = ArrayList<List<Offset>>()
    var index = 0
    while (index < values.size) {
        val count = values[index].toInt()
        index += 1
        if (count <= 0 || index + count * 2 > values.size) return strokes
        val stroke = ArrayList<Offset>(count)
        repeat(count) {
            stroke += Offset(values[index], values[index + 1])
            index += 2
        }
        strokes += stroke
    }
    return strokes
}

private val SignaturePadSaver: Saver<SignaturePadState, FloatArray> = Saver(
    save = { state -> encodeStrokes(state.strokes) },
    restore = { values -> SignaturePadState().apply { strokes = decodeStrokes(values) } },
)

@Composable
fun rememberSignaturePadState(): SignaturePadState =
    rememberSaveable(saver = SignaturePadSaver) { SignaturePadState() }

/**
 * The drawing surface. Stateless apart from the [state] it is handed, so the export screen can
 * read the strokes without the pad knowing anything about files or PDFs.
 */
@Composable
fun SignaturePad(state: SignaturePadState, modifier: Modifier = Modifier) {
    // Read at composition, because a semantics lambda is not composable: the four sentences the
    // pad announces are the app's strings, in the app's language (finding 211).
    val padLabel = stringResource(R.string.a11y_signature_pad)
    val signedLabel = stringResource(R.string.a11y_signature_signed)
    val emptyLabel = stringResource(R.string.a11y_signature_empty)
    val clearLabel = stringResource(R.string.a11y_signature_clear)
    val isEmpty = state.isEmpty
    Box(
        modifier = modifier
            .background(Jamaica.Chalk, RoundedCornerShape(14.dp))
            .onSizeChanged { state.surface = it }
            // A surface drawn on with a finger says nothing to a screen reader: no name, no state,
            // no way to undo. It has all three now - the field is named, it says whether the page
            // will carry a signature, and the clear action is reachable as an accessibility
            // action instead of only through the button beside the pad.
            .semantics {
                contentDescription = padLabel
                stateDescription = if (isEmpty) emptyLabel else signedLabel
                if (!isEmpty) {
                    customActions = listOf(
                        CustomAccessibilityAction(clearLabel) {
                            state.clear()
                            true
                        },
                    )
                }
            }
            // A tap on its own, because a drag gesture reports nothing until the finger has moved
            // past the touch slop: a hurried signature that starts with a touch and a lift was
            // silently dropped (finding 82).
            .pointerInput(Unit) {
                detectTapGestures { offset -> state.commit(listOf(state.normalise(offset))) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> state.current = listOf(state.normalise(offset)) },
                    onDrag = { change, _ -> state.current = state.current + state.normalise(change.position) },
                    onDragEnd = {
                        state.commit(state.current)
                        state.current = emptyList()
                    },
                    onDragCancel = { state.current = emptyList() },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ink = Color(0xFF11130D)
            val stroke = Stroke(width = 5f, cap = StrokeCap.Round)
            val padWidth = size.width
            val padHeight = size.height
            // Stored as fractions of the pad; multiplied back into whatever the pad is now.
            fun onPad(point: Offset) = Offset(point.x * padWidth, point.y * padHeight)
            (state.strokes + listOf(state.current)).forEach { points ->
                if (points.size < 2) {
                    points.firstOrNull()?.let { drawCircle(ink, radius = 2.5f, center = onPad(it)) }
                    return@forEach
                }
                val path = Path().apply {
                    moveTo(onPad(points.first()).x, onPad(points.first()).y)
                    points.drop(1).forEach { lineTo(onPad(it).x, onPad(it).y) }
                }
                drawPath(path, ink, style = stroke)
            }
        }
    }
}
