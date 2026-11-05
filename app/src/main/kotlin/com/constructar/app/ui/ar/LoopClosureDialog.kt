/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.ui.LocalDecimalMark
import com.constructar.app.ui.shownLengthWithUnit
import com.constructar.app.ui.theme.SiteColors
import com.constructar.arengine.keyframe.ClosureState
import com.constructar.core.units.NumberText

/**
 * The loop-closure question, asked once and answered by a person.
 *
 * This is the only dialog in the AR screen whose *confirm* changes the graph, so it says what the
 * confirmation means rather than what it does: the app cannot know whether two visits half a metre
 * apart were the same place, and a wrong closure does not look wrong afterwards - the optimiser
 * spreads the error round the loop and every wall in the room moves a little. "The same place" is
 * therefore the button, not "OK".
 *
 * Every other state is a sentence and one way out: nothing found, the graph could not be
 * optimised, or the residual before and after. The numbers are printed because a closure that
 * changed nothing and one that moved the whole room by half a metre are the same three characters
 * otherwise (the defect findings 237 and 238 removed from the marker dialog).
 */
@Composable
fun LoopClosureDialog(
    state: ClosureState,
    onConfirm: () -> Unit,
    onRefuse: () -> Unit,
    onClose: () -> Unit,
) {
    if (state is ClosureState.Idle) return

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.loop_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    ClosureState.Idle -> Unit
                    ClosureState.NothingFound -> Text(
                        text = stringResource(R.string.loop_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is ClosureState.Offered -> Text(
                        text = stringResource(
                            R.string.loop_offer,
                            shownLengthWithUnit(state.offer.distanceMetres),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    is ClosureState.Closed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(
                                R.string.loop_closed,
                                NumberText.digits(state.optimisation.initialCost, 2, LocalDecimalMark.current),
                                NumberText.digits(state.optimisation.finalCost, 2, LocalDecimalMark.current),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        // How far the graph actually moved, because a residual that fell proves the
                        // optimiser ran and says nothing about what it did to the room (finding
                        // 246). Silence when nothing moved: a "0,000 m" line teaches the operator
                        // that closures are theatre.
                        if (state.correction.moved) {
                            Text(
                                text = stringResource(
                                    R.string.loop_shift,
                                    state.correction.nodes,
                                    shownLengthWithUnit(state.correction.maxShiftMetres),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = SiteColors.accent,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                    ClosureState.Failed -> Text(
                        text = stringResource(R.string.loop_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SiteColors.danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            if (state is ClosureState.Offered) {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.loop_confirm)) }
            } else {
                TextButton(onClick = onClose) { Text(stringResource(R.string.home_cancel)) }
            }
        },
        dismissButton = {
            if (state is ClosureState.Offered) {
                TextButton(onClick = onRefuse) { Text(stringResource(R.string.home_cancel)) }
            }
        },
    )
}
