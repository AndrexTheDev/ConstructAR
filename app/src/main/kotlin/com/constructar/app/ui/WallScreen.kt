/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.data.WallPlan
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.units.LengthFormatter
import com.constructar.core.units.LengthUnit
import com.constructar.core.units.PercentText
import com.constructar.core.wall.DrywallSpec

/**
 * The room's paperwork: wall area, drywall purchase and the DIN 18015 zones.
 *
 * Three engines have existed in `:core` since phase 2 - `wallSurface` (net wall area, openings
 * subtracted as a union), `estimateDrywall` (boards, studs, track, screws) and
 * `classifyInstallations` (sockets at 30 cm, switches at 105 cm, ceiling outlets under the
 * ceiling) - and not one number from any of them had ever reached a screen. A take-off that lives
 * only in a unit test is a take-off the person buying the boards cannot use (finding 214).
 *
 * What this screen refuses to do is to look more certain than the measurement:
 *
 *  - **The assumptions are printed** under the drywall numbers. "14 boards" is a purchase, and a
 *    purchase is only as good as the board size, the stud spacing and the waste factor behind it -
 *    a fitter with 60 cm spacing reads 63 cm as an error, one with 2.60 m ceilings reads 2.50 m as
 *    one. The values come from `DrywallSpec`, the same object the estimate was computed with.
 *  - **The zones need a floor**, and the screen says so instead of assigning a socket from a
 *    height that was never measured from the finished floor (see `WallPlan` and finding 214).
 *  - **An open ring is not a room.** No walls, no boards, no zones - and a sentence that says
 *    what to do about it, because an empty screen is not an explanation.
 *
 * All digits come from `LengthFormatter` and all words from the resources: nothing here is
 * formatted with a locale of its own, and nothing here composes a sentence the six translations
 * would have to bend around.
 */
@Composable
fun WallScreen(
    projectName: String,
    plan: WallPlan,
    spec: DrywallSpec = DrywallSpec(),
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(SiteColors.gloveTouchTarget)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.paywall_back),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.wall_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    projectName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!plan.valid) {
            // Nothing measured is not the same as nothing there. The outline is the source of
            // every number on this screen, and an open ring is one gap short of being a room.
            Text(
                stringResource(R.string.wall_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        WallCard(stringResource(R.string.wall_surface_title)) {
            plan.surface.lines.forEachIndexed { index, line ->
                WallRow(
                    label = stringResource(
                        R.string.wall_row,
                        index + 1,
                        stringResource(R.string.wall_length_m, LengthFormatter.number(line.lengthM)),
                        stringResource(R.string.wall_area_m2, area(line.netAreaM2)),
                    ),
                    value = "",
                )
            }
            WallRow(
                stringResource(R.string.wall_gross),
                stringResource(R.string.wall_area_m2, area(plan.surface.grossAreaM2)),
            )
            WallRow(
                stringResource(R.string.wall_openings),
                stringResource(R.string.wall_area_m2, area(plan.surface.openingAreaM2)),
            )
            WallRow(
                stringResource(R.string.wall_net),
                stringResource(R.string.wall_area_m2, area(plan.surface.netAreaM2)),
                emphasis = true,
            )
            if (plan.openingsRecorded == 0) {
                // 0.00 m² deducted is a fact about the record, not about the room: the walls have
                // a door. Saying which one it is stops the reader from taking the gross area for
                // the wall he will actually board.
                Text(
                    stringResource(R.string.wall_openings_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val drywall = plan.drywall
        if (drywall.valid) {
            WallCard(stringResource(R.string.wall_drywall_title)) {
                WallRow(
                    stringResource(R.string.wall_boards),
                    stringResource(
                        R.string.wall_boards_value,
                        drywall.boards,
                        drywall.boardsBare,
                    ),
                    emphasis = true,
                )
                WallRow(stringResource(R.string.wall_studs), drywall.studs.toString())
                WallRow(
                    stringResource(R.string.wall_stud_profile),
                    stringResource(
                        R.string.wall_length_m,
                        LengthFormatter.number(drywall.studProfileMeters),
                    ),
                )
                WallRow(
                    stringResource(R.string.wall_track_profile),
                    stringResource(
                        R.string.wall_length_m,
                        LengthFormatter.number(drywall.trackProfileMeters),
                    ),
                )
                WallRow(stringResource(R.string.wall_screws), drywall.screws.toString())
                Text(
                    stringResource(
                        R.string.wall_assumptions,
                        LengthFormatter.number(spec.boardWidthM),
                        LengthFormatter.number(spec.boardHeightM),
                        LengthFormatter.number(spec.studSpacingM),
                        PercentText.of(spec.wasteFraction),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        WallCard(stringResource(R.string.wall_zones_title)) {
            val installations = plan.installations
            if (installations == null) {
                Text(
                    stringResource(R.string.wall_zones_no_floor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                WallRow(
                    stringResource(R.string.wall_zones_points),
                    installations.assignments.size.toString(),
                )
                WallRow(
                    stringResource(R.string.wall_zones_sockets),
                    installations.socketCount.toString(),
                )
                WallRow(
                    stringResource(R.string.wall_zones_switches),
                    installations.switchCount.toString(),
                )
                WallRow(
                    stringResource(R.string.wall_zones_ceiling),
                    installations.ceilingCount.toString(),
                )
                if (installations.rejectedCount > 0) {
                    WallRow(
                        stringResource(R.string.wall_zones_rejected),
                        installations.rejectedCount.toString(),
                    )
                }
                if (!installations.ceilingZoneAvailable) {
                    Text(
                        stringResource(R.string.wall_zones_ceiling_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.wall_zones_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One section, in the same card the rest of the app uses for a block of numbers. */
@Composable
private fun WallCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * A label on the left and its number on the right.
 *
 * The number is the thing the reader came for, so it is the part set in the heavier face; an empty
 * [value] is a row that carries its digits inside the label (a per-wall line, where `Wall 1 ·
 * 4.20 m · 10.50 m²` is one sentence rather than a key and a value).
 */
@Composable
private fun WallRow(label: String, value: String, emphasis: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = if (emphasis) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(
                value,
                style = if (emphasis) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Square metres to a tenth: a scan is good to a centimetre, a hundredth of a square metre is not. */
private fun area(squareMeters: Float): String =
    LengthFormatter.number(squareMeters, LengthUnit.METER, AREA_DECIMALS)

private const val AREA_DECIMALS = 1
