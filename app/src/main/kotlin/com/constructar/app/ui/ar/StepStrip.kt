/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors

/**
 * The three numbered steps of a measurement, on the glass (finding 265).
 *
 * The screen used to have no top and no bottom: a room to scan, a button to add a point, two
 * buttons that were grey, and no statement anywhere of how many things there are to do or which
 * one is being asked for now. On the device that read as "the app makes no sense" — the operator
 * could not tell a step he had not reached from a step that had failed.
 *
 * Deliberately not a `Stepper` with next/back: the steps here are not pages to navigate, they are a
 * description of one job, and none of them is skippable by tapping something else. The strip
 * therefore has no touch target of its own; it announces itself to TalkBack and nothing more.
 *
 * Since finding 267 the strip takes its chips from [MeasureGuide.strip]: a walkthrough shows the
 * scan step alone, a measurement shows the two it asks for. Which chips exist is the most visible
 * difference between the two modes — it survives colour blindness, sunlight and a gloved thumb.
 */
@Composable
internal fun StepStrip(
    chips: List<Pair<MeasureStep, StepMark>>,
    modifier: Modifier = Modifier,
) {
    val now = stringResource(R.string.step_now)
    val done = stringResource(R.string.step_done)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { (step, mark) ->
            val text = stringResource(
                when (step) {
                    MeasureStep.SCAN -> R.string.ar_step_scan
                    MeasureStep.POINTS -> R.string.ar_step_points
                    MeasureStep.FINISH -> R.string.ar_step_finish
                },
            )
            val state = when (mark) {
                StepMark.NOW -> now
                StepMark.DONE -> done
                StepMark.LATER -> ""
            }
            // Colour is never the only carrier: the word "now" or "done" is in the state
            // description, so the strip is readable with the phone held at arm's length in
            // sunlight and audible with TalkBack (the standing a11y rule).
            Surface(
                color = when (mark) {
                    StepMark.NOW -> SiteColors.accent
                    StepMark.DONE -> HudStyle.rule
                    StepMark.LATER -> HudStyle.panel
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = text
                    // "now" / "done" are the two facts a sighted hand reads from the fill colour;
                    // a step not reached yet says nothing rather than an empty string.
                    if (state.isNotEmpty()) stateDescription = state
                },
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (mark) {
                        StepMark.NOW -> Jamaica.Ink
                        else -> HudStyle.text
                    },
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}
