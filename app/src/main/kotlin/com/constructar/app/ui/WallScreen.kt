/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.units.LengthUnit
import com.constructar.core.units.PercentText
import com.constructar.core.wall.DOOR_WIDTH_M
import com.constructar.core.wall.DrywallSpec
import com.constructar.core.wall.OpeningKind
import com.constructar.core.wall.WINDOW_WIDTH_M
import com.constructar.core.wall.WallOpening
import com.constructar.core.wall.WallPlan
import com.constructar.core.wall.openingOffsetForTap
import com.constructar.core.wall.openingsByWall
import com.constructar.core.wall.rectOn
import com.constructar.core.wall.stepOpeningFraction

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
    /**
     * Places a door or a window at [offsetM] along wall [wallIndex].
     *
     * The offset is the one `:core` computed (`openingOffsetForTap`) from the tap's fraction and
     * the wall's length - the screen finds out where the finger landed and the wall's length from
     * the plan, and hands both to the function that owns the clamping. A screen that did the
     * division itself would be a second definition of where the opening is, and the picture would
     * eventually disagree with the purchase order.
     */
    onAddOpening: (wallIndex: Int, kind: OpeningKind, offsetM: Float) -> Unit = { _, _, _ -> },
    /** Removes the last opening placed on a wall. */
    onRemoveOpening: (wallIndex: Int) -> Unit = {},
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

        WallCard(stringResource(R.string.wall_elevation_title)) {
            // One elevation per wall, drawn from the same rectangles `wallSurface` subtracts:
            // a hole in the picture is a hole in the take-off, always.
            val byWall = remember(plan.openings) { openingsByWall(plan.openings) }
            plan.surface.lines.forEachIndexed { index, line ->
                val openings = byWall[index].orEmpty()
                Text(
                    stringResource(
                        R.string.wall_row,
                        index + 1,
                        stringResource(R.string.wall_length_m, shownLength(line.lengthM)),
                        stringResource(R.string.wall_area_m2, area(line.netAreaM2)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                WallElevation(
                    wallIndex = index,
                    lengthM = line.lengthM,
                    heightM = line.heightM,
                    openings = openings,
                    onPlace = { fraction, kind ->
                        // The trade width of the thing being placed decides how far it has to be
                        // pulled back from the end of the wall, so `:core` needs to know which.
                        val widthM = when (kind) {
                            OpeningKind.WINDOW -> WINDOW_WIDTH_M
                            OpeningKind.DOOR, OpeningKind.PASSAGE -> DOOR_WIDTH_M
                        }
                        onAddOpening(
                            index,
                            kind,
                            openingOffsetForTap(fraction, line.lengthM, widthM),
                        )
                    },
                )
                if (openings.isEmpty()) {
                    Text(
                        stringResource(R.string.wall_elevation_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GloveButton(
                            label = stringResource(R.string.wall_opening_remove),
                            icon = Icons.Default.Delete,
                            onClick = { onRemoveOpening(index) },
                            modifier = Modifier.weight(1f),
                            tone = ActionTone.SECONDARY,
                        )
                    }
                }
            }
        }

        WallCard(stringResource(R.string.wall_surface_title)) {
            plan.surface.lines.forEachIndexed { index, line ->
                WallRow(
                    label = stringResource(
                        R.string.wall_row,
                        index + 1,
                        stringResource(R.string.wall_length_m, shownLength(line.lengthM)),
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
            if (plan.openingsUnplaced > 0) {
                // A door on a wall that no longer exists: still in the file, no longer in the
                // area. Saying so is the difference between a take-off that is wrong and one that
                // is wrong and admits it (finding 225).
                Text(
                    stringResource(R.string.wall_openings_unplaced, plan.openingsUnplaced),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
                        shownLength(drywall.studProfileMeters),
                    ),
                )
                WallRow(
                    stringResource(R.string.wall_track_profile),
                    stringResource(
                        R.string.wall_length_m,
                        shownLength(drywall.trackProfileMeters),
                    ),
                )
                WallRow(stringResource(R.string.wall_screws), drywall.screws.toString())
                Text(
                    stringResource(
                        R.string.wall_assumptions,
                        shownLength(spec.boardWidthM),
                        shownLength(spec.boardHeightM),
                        shownLength(spec.studSpacingM),
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
                if (plan.noFloorPoints > 0) {
                    Text(
                        stringResource(R.string.wall_zones_no_floor_points, plan.noFloorPoints),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                if (plan.foreignSessionPoints > 0) {
                    // A height is the distance between two points in *one* ARCore world, and a
                    // new session is a new world. These points are in the room and in the totals;
                    // what they cannot have is a mounting height measured from this floor
                    // (finding 217), so the count is printed instead of a zone that would be a
                    // guess dressed as a measurement.
                    Text(
                        stringResource(R.string.wall_zones_foreign, plan.foreignSessionPoints),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (plan.noFloorPoints > 0) {
                    Text(
                        stringResource(R.string.wall_zones_no_floor_points, plan.noFloorPoints),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!plan.zonesCoverEveryPoint && plan.pointCount > 0) {
                    Text(
                        stringResource(
                            R.string.wall_zones_partial,
                            plan.pointCount,
                            plan.pointCount + plan.foreignSessionPoints + plan.noFloorPoints +
                                plan.invalidPoints,
                        ),
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

/**
 * One wall as an elevation, with its holes, and the two ways to make a hole.
 *
 * The drawing is a rectangle and a few rectangles inside it - the numbers behind it come from
 * `rectOn`, which clips exactly the way `wallSurface` subtracts, so the picture cannot promise a
 * hole the purchase order does not take off. A tap on the elevation places an opening where the
 * finger landed; the two buttons say which kind.
 */
@Composable
private fun WallElevation(
    wallIndex: Int,
    lengthM: Float,
    heightM: Float,
    openings: List<WallOpening>,
    onPlace: (fraction: Float, kind: OpeningKind) -> Unit,
) {
    // Where the *next* opening goes. Kept in the composition, not in the drawing: a marker the
    // operator cannot see is the same as no marker, and a spot a screen reader cannot move is the
    // same as no spot (finding 223).
    var spot by remember { mutableStateOf(0.5f) }
    val wallColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val holeColor = MaterialTheme.colorScheme.error.copy(alpha = 0.40f)
    val markerColor = MaterialTheme.colorScheme.primary
    // Read at composition, because a semantics lambda is not composable.
    val spotLabel = stringResource(R.string.wall_elevation_a11y, wallIndex + 1, PercentText.of(spot))
    val leftLabel = stringResource(R.string.wall_elevation_spot_left)
    val rightLabel = stringResource(R.string.wall_elevation_spot_right)
    val rects = remember(openings, lengthM, heightM) {
        openings.mapNotNull { it.rectOn(lengthM, heightM) }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(ELEVATION_HEIGHT)
            // A canvas is a picture to a screen reader unless it is given a name, and a tap target
            // it cannot reach unless the choice is offered as an action. Both, and the actions
            // step a tenth of the wall at a time (`stepOpeningFraction`, which owns the clamp).
            .semantics {
                contentDescription = spotLabel
                customActions = listOf(
                    CustomAccessibilityAction(leftLabel) {
                        spot = stepOpeningFraction(spot, -1)
                        true
                    },
                    CustomAccessibilityAction(rightLabel) {
                        spot = stepOpeningFraction(spot, 1)
                        true
                    },
                )
            }
            .pointerInput(lengthM) {
                detectTapGestures { point ->
                    if (size.width > 0) spot = (point.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        drawRect(color = wallColor, size = size)
        rects.forEach { rect ->
            // Canvas y grows downwards and `rectOn` measures from the floor upwards: the flip is
            // the one piece of arithmetic this screen owns.
            val left = rect.leftFraction * size.width
            val right = rect.rightFraction * size.width
            val top = (1f - rect.topFraction) * size.height
            val bottom = (1f - rect.bottomFraction) * size.height
            drawRect(
                color = holeColor,
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
            )
        }
        // The spot the buttons will use, drawn: without it the operator taps, sees nothing, and
        // has no way of knowing whether the tap landed or where.
        val markerX = spot * size.width
        drawLine(
            color = markerColor,
            start = Offset(markerX, 0f),
            end = Offset(markerX, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GloveButton(
            label = stringResource(R.string.wall_opening_door),
            icon = Icons.Default.Add,
            onClick = { onPlace(spot, OpeningKind.DOOR) },
            modifier = Modifier.weight(1f),
            tone = ActionTone.SECONDARY,
        )
        GloveButton(
            label = stringResource(R.string.wall_opening_window),
            icon = Icons.Default.Add,
            onClick = { onPlace(spot, OpeningKind.WINDOW) },
            modifier = Modifier.weight(1f),
            tone = ActionTone.SECONDARY,
        )
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

/**
 * Square metres to a tenth: a scan is good to a centimetre, a hundredth of a square metre is not.
 *
 * A composable because the decimal mark is the reader's: the same wall reads `10.5 m²` in English
 * and `10,5 m²` in German, and the mark comes from the composition, not from a global.
 */
@Composable
private fun area(squareMeters: Float): String =
    shownLength(squareMeters, LengthUnit.METER, AREA_DECIMALS)

private const val AREA_DECIMALS = 1

/** An elevation is a sketch beside its numbers, not a drawing: 88 dp is a storey at a glance. */
private val ELEVATION_HEIGHT = 88.dp
