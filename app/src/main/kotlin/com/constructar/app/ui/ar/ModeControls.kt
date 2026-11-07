/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.units.PercentText

/**
 * The switch between the two jobs, and the gauge that shows what the walkthrough has captured
 * (finding 267).
 *
 * Both exist because the report named two things the screen never said out loud: *which* of the
 * two activities was running, and *what* had been scanned. The old `Scan room` toggle answered
 * neither — it sat inside a row of tool buttons with the same shape as "Undo" and "Torch", and its
 * only effect was to make a grey overlay appear or disappear without a word about what grey meant.
 */

/** The room's own colour, opaque, for text and swatches. The overlay is the same hue at half. */
private val captureColor: Color = Color(ModeStyle.CAPTURE_ARGB)

/**
 * Two buttons, one decision: walk the room, or set points by hand.
 *
 * Not a toggle and not a dropdown: the choice changes what every other control on the screen
 * does, so it is the first thing in the action sheet and it is readable from arm's length. The
 * selected one carries the mode's own colour — violet for the room, lime for the measuring
 * points — and the mode's sentence is its `stateDescription`, so TalkBack says what the mode means
 * rather than just that it is on.
 */
@Composable
internal fun ModePicker(
    mode: MeasureMode,
    onSelect: (MeasureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measureHint = stringResource(R.string.mode_measure_hint)
    val captureHint = stringResource(R.string.mode_capture_hint)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeChip(
            selected = mode == MeasureMode.MEASURE,
            label = stringResource(R.string.mode_measure),
            icon = Icons.Default.TouchApp,
            tint = SiteColors.accent,
            state = measureHint,
            onSelect = { onSelect(MeasureMode.MEASURE) },
            modifier = Modifier.weight(1f),
        )
        ModeChip(
            selected = mode == MeasureMode.CAPTURE,
            label = stringResource(R.string.mode_capture),
            icon = Icons.Default.ViewInAr,
            tint = captureColor,
            state = captureHint,
            onSelect = { onSelect(MeasureMode.CAPTURE) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeChip(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    tint: Color,
    state: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Locals with names of their own: inside `semantics { }` an unqualified `selected` resolves to
    // the receiver's property and announces nothing (the shape finding 204 fixed for `SpecChip`).
    val chosen = selected
    val announced = state
    Button(
        onClick = onSelect,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (chosen) tint else HudStyle.panel,
            contentColor = if (chosen) Jamaica.Ink else HudStyle.text,
        ),
        // 56 dp: this is the one control a gloved hand has to hit before anything else, and it is
        // the only place where choosing wrong costs a whole walk across the room.
        modifier = modifier
            .height(56.dp)
            .semantics {
                this.selected = chosen
                stateDescription = announced
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * What the walkthrough has captured, pinned above the scrolling text: legend, bar, state word.
 *
 * Three answers in the smallest form that carries them, in the same place every frame:
 *
 * * **whether** anything is captured — a bar at zero and the words "nothing captured yet";
 * * **what** — the violet swatch, the same colour as the surface drawn over the room, named in
 *   words for the operator who cannot tell violet from grey;
 * * **how much** — the percentage and the triangle count.
 *
 * It is drawn from [GuideLine], the same value the status block uses, so the gauge and the sentence
 * cannot disagree about which state the scan is in.
 */
@Composable
internal fun ScanGauge(
    state: GuideLine?,
    detailFraction: Float,
    triangles: Int,
    modifier: Modifier = Modifier,
) {
    if (state == null) return
    val legend = stringResource(R.string.scan_legend)
    val word = when (state) {
        GuideLine.SCAN_EMPTY -> stringResource(R.string.scan_state_none)
        GuideLine.SCAN_PROGRESS -> stringResource(
            R.string.scan_state_growing,
            PercentText.of(detailFraction),
            triangles / 1000,
        )
        GuideLine.SCAN_FULL -> stringResource(R.string.scan_state_full)
        GuideLine.SCAN_BEHIND -> stringResource(R.string.scan_state_behind)
        GuideLine.SCAN_UNAVAILABLE -> stringResource(R.string.scan_state_unavailable)
        else -> null
    } ?: return
    val alarming = state == GuideLine.SCAN_FULL ||
        state == GuideLine.SCAN_BEHIND ||
        state == GuideLine.SCAN_UNAVAILABLE
    // One announcement for the whole gauge: "violet means captured, 41 percent, 28 thousand
    // triangles" read as three separate labels is a sentence nobody finishes.
    val spoken = legend + ": " + word

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(captureColor, RoundedCornerShape(3.dp)),
            )
            Text(
                text = legend,
                style = MaterialTheme.typography.labelSmall,
                color = HudStyle.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
            Text(
                text = word,
                style = MaterialTheme.typography.labelMedium,
                color = if (alarming) SiteColors.warning else HudStyle.text,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
                overflow = TextOverflow.Ellipsis,
            )
        }
        // No bar when there is nothing to measure: an empty bar and a scanner that cannot run are
        // different answers, and a bar at zero would make them look the same.
        if (state != GuideLine.SCAN_UNAVAILABLE) {
            LinearProgressIndicator(
                progress = { detailFraction.coerceIn(0f, 1f) },
                color = captureColor,
                trackColor = HudStyle.rule,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
        }
    }
}
