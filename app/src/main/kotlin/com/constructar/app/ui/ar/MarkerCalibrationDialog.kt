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
import com.constructar.app.ui.shownLengthWithUnit
import com.constructar.app.ui.shownPercent
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.calib.MarkerStatusLine
import com.constructar.core.calib.MarkerSweepState
import com.constructar.core.calib.MarkerWatchingPhase
import com.constructar.core.calib.ReferenceCalibration

/**
 * The marker calibration dialog: one instruction at a time, and every number with its evidence.
 *
 * Extracted from the AR screen (finding 242) because it is now a small state machine of its own -
 * aim, walk, sideways, measured - and reading that inside a 1200-line screen is how a dialog
 * grows a fourth sentence that contradicts the first.
 *
 * The three rules it encodes, in the order they matter:
 *
 *  * **One instruction per phase.** `AIMING` says aim, `WALKING` says walk; they are different
 *    actions and printing "walk" to a phone that is not pointing at the marker is advice that
 *    cannot work. Before this the dialog printed a sighting count in both cases.
 *  * **A wrong walk is named.** `axialOnly` means the operator has walked - a metre of it - and
 *    all of it straight at the wall, where the geometry is weakest. The walking sentence turns
 *    into a warning colour rather than being replaced by "not enough evidence", which is what it
 *    used to say after the operator had already done what was asked.
 *  * **No factor without its evidence.** `MarkerStatusLine` decides what may be printed: the
 *    pairs that agreed, the baseline they span and how tightly they agreed, and `Apply` only for
 *    a `Measured` line, whose window was asked again rather than trusted (findings 237 and 238).
 */
@Composable
fun MarkerCalibrationDialog(
    state: MarkerSweepState,
    onApply: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val line = MarkerStatusLine.of(state)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.marker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.marker_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // A live region on the phase line: the sentence changes while the operator walks
                // and a screen reader would otherwise read the first instruction once and never
                // the next one.
                when (line) {
                    MarkerStatusLine.Off -> Unit
                    is MarkerStatusLine.Watching -> {
                        Text(
                            text = stringResource(
                                if (line.phase == MarkerWatchingPhase.AIMING) {
                                    R.string.marker_state_aiming
                                } else {
                                    R.string.marker_state_walking
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (line.axialOnly) SiteColors.danger else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        // The counts are evidence, not an instruction: shown once something has
                        // been seen, and never as the answer to "what do I do now".
                        if (line.phase == MarkerWatchingPhase.WALKING) {
                            Text(
                                text = stringResource(
                                    R.string.marker_watching,
                                    line.sightings,
                                    line.markerIds,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                    is MarkerStatusLine.Measured -> Text(
                        text = stringResource(
                            R.string.marker_measured,
                            ReferenceCalibration.formatScale(line.factor),
                            line.pairs,
                            shownLengthWithUnit(line.baselineMetres),
                            shownPercent(line.spread),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Jamaica.Lime,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    is MarkerStatusLine.OutOfWindow -> Text(
                        text = stringResource(
                            R.string.calib_rejected_toast,
                            ReferenceCalibration.formatScale(line.factor),
                            ReferenceCalibration.formatScale(line.minScale),
                            ReferenceCalibration.formatScale(line.maxScale),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SiteColors.danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            // Only a factor the window takes may be applied - the same window the two-tap sweep
            // beside it uses, asked again here rather than trusted from the proposal's own flag.
            val applied = (line as? MarkerStatusLine.Measured)?.factor
            TextButton(
                enabled = applied != null,
                onClick = { applied?.let(onApply) },
            ) { Text(stringResource(R.string.calib_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) }
        },
    )
}
