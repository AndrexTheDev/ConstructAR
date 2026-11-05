/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.bom.MaterialLine
import com.constructar.core.bom.MaterialList
import com.constructar.core.geo.PolylineMetrics
import com.constructar.core.model.ConduitKind
import com.constructar.core.price.Pricing
import com.constructar.core.units.LengthFormatter
import com.constructar.core.units.PercentText

/**
 * The shopping list for one room.
 *
 * Until now the bill of materials only existed in the CSV and the PDF — which is exactly the
 * wrong place for it: the person who drives to the wholesaler is standing in the room holding
 * this phone. Every line is aggregated per conduit type (that is how it is bought), shows the
 * net metres *and* the metres to order with the site's waste factor, and the fittings the run
 * actually needs.
 *
 * The numbers come from `MaterialListBuilder` in `:core` — the same object the CSV and the PDF
 * use, so the phone cannot disagree with the paperwork.
 */
@Composable
fun MaterialsScreen(
    projectName: String,
    materialList: MaterialList?,
    wasteOverride: Float?,
    onWasteChange: (Float?) -> Unit,
    /**
     * The firm's clip spacing in metres - the second half of the same firm-level number.
     *
     * `MaterialListOptions` has documented it since finding 113 and only the waste factor ever
     * reached this screen, so a shop that clips a fire-alarm cable every 30 cm had no way to say
     * so and ordered half the clips it needs (finding 151).
     */
    clipSpacingOverride: Float? = null,
    onClipSpacingChange: (Float?) -> Unit = {},
    firmHourlyRateCents: Int,
    onHourlyRateChange: (Int?) -> Unit,
    onBack: () -> Unit,
) {
    // Blank means "the built-in default", so the field is a string and not a number.
    //
    // Deliberately **not** keyed on `firmHourlyRateCents`: this field writes that value on every
    // keystroke, and a key that changes with it re-initialises the text from the stored cents -
    // so the field rewrites itself under the operator's finger. Typing "4" stored 4,00 € and
    // re-printed the field as "4,00"; the next keystroke appended to that ("4,005"), which parses
    // as 4.005,00 € and is a perfectly valid rate, so nothing complained and every job after it
    // billed at a hundred times the intended rate (finding 84). The text is restored across a
    // rotation by `rememberSaveable` and initialised from the store when the screen is opened.
    var rateDraft by rememberSaveable {
        mutableStateOf(Pricing.formatForInput(firmHourlyRateCents.toLong()))
    }
    val rateInvalid = PricingInput.rate(rateDraft).invalid
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
                Text(stringResource(R.string.materials_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    projectName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ── The firm's hourly rate ──────────────────────────────────────────────────
        // Sits with the waste factor because it is the same kind of number: entered once, used
        // on every job. A project can still override it on its own card.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.firm_rate_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            OutlinedTextField(
                value = rateDraft,
                onValueChange = { text ->
                    rateDraft = text
                    // Only a readable amount is stored. `48,` is the state of every keypress on
                    // the way to `48,50`, and it used to be written to the settings as "no rate",
                    // resetting the firm's rate to the built-in default (finding 43).
                    val typed = PricingInput.rate(text)
                    if (!typed.invalid) onHourlyRateChange(typed.cents)
                },
                label = { Text(stringResource(R.string.firm_rate_label)) },
                singleLine = true,
                isError = rateInvalid,
                supportingText = if (rateInvalid) {
                    { Text(stringResource(R.string.project_costs_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.firm_rate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // ── The firm's two estimating numbers ──────────────────────────────────────
        // Above the empty-list return on purpose: these are firm-wide settings, not room data.
        // A room whose runs are all single points has no list to show, and its screen used to end
        // before the one section that is about the shop rather than the job (finding 151).
        //
        // The waste row says what "the trade default" stands for before it is chosen. It is three
        // different numbers - 7 % of cable, 8 % of pipe, 10 % of duct - and the only place they
        // ever appeared was a material card, one line at a time, *after* the choice, so "Default"
        // and "10 %" looked like the same offer to a shop working in duct and to one working in
        // cable (finding 156). The numbers come from the engine's own function, never from here.
        val tradeDefaultsText = stringResource(
            R.string.waste_hint,
            LengthFormatter.formatWasteFactor(PolylineMetrics.defaultWasteFactor(ConduitKind.CABLE)),
            LengthFormatter.formatWasteFactor(PolylineMetrics.defaultWasteFactor(ConduitKind.PIPE)),
            LengthFormatter.formatWasteFactor(PolylineMetrics.defaultWasteFactor(ConduitKind.DUCT)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.waste_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WasteChip(
                    label = stringResource(R.string.waste_default),
                    selected = wasteOverride == null,
                    onClick = { onWasteChange(null) },
                )
                listOf(0.05f, 0.10f, 0.15f).forEach { factor ->
                    WasteChip(
                        label = stringResource(R.string.waste_value, PercentText.of(factor)),
                        selected = wasteOverride == factor,
                        onClick = { onWasteChange(factor) },
                    )
                }
            }
            Text(
                tradeDefaultsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // The same treatment as the row above, for the row that was left behind: "the trade
        // default" is 0.60 m of cable, 0.90 m of pipe and 0.50 m of duct, none of which was on the
        // screen before the choice - and 0.50 m is not even one of the chips, so on a duct job the
        // selected value appeared nowhere at all (finding 180).
        val clipDefaultsText = stringResource(
            R.string.clips_hint,
            shownLengthWithUnit(PolylineMetrics.defaultClipSpacing(ConduitKind.CABLE)),
            shownLengthWithUnit(PolylineMetrics.defaultClipSpacing(ConduitKind.PIPE)),
            shownLengthWithUnit(PolylineMetrics.defaultClipSpacing(ConduitKind.DUCT)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.clips_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WasteChip(
                    label = stringResource(R.string.clips_default),
                    selected = clipSpacingOverride == null,
                    onClick = { onClipSpacingChange(null) },
                )
                // Trade spacings, not a slider: 30 cm is fire-alarm practice, 60 cm and 90 cm are
                // the cable and pipe defaults the app already assumes, 100 cm is a ceiling grid.
                listOf(0.30f, 0.60f, 0.90f, 1.00f).forEach { spacing ->
                    WasteChip(
                        label = stringResource(R.string.clips_value, shownLengthWithUnit(spacing)),
                        selected = clipSpacingOverride == spacing,
                        onClick = { onClipSpacingChange(spacing) },
                    )
                }
            }
            Text(
                clipDefaultsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        if (materialList == null || materialList.isEmpty) {
            Text(
                stringResource(R.string.materials_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(
            stringResource(R.string.materials_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // A list that was asked to leave the uncertain runs out is not a list with nothing to
        // warn about - it is a list that is missing them, and every warning below counts only
        // what survived (finding 161).
        if (materialList.excludedLowConfidenceRuns > 0) {
            Text(
                stringResource(
                    R.string.materials_excluded_low_confidence,
                    materialList.excludedLowConfidenceRuns,
                    shownLengthWithUnit(materialList.excludedLowConfidenceMeters),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = SiteColors.warning,
            )
        }

        materialList.lines.forEach { line ->
            MaterialCard(line)
        }

        // ── Total ───────────────────────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = Jamaica.SurfaceHigh),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.materials_total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    shownLengthWithUnit(materialList.totalWithWasteMeters),
                    style = MaterialTheme.typography.displaySmall,
                    color = SiteColors.accent,
                )
                Text(
                    stringResource(
                        R.string.export_fittings,
                        materialList.totalClips,
                        materialList.totalCouplings,
                        materialList.totalElbows,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** One waste factor: the catalogue default, or the firm's own. */
@Composable
private fun WasteChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun MaterialCard(line: MaterialLine) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    line.kind.routingLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    color = when (line.kind) {
                        ConduitKind.CABLE -> SiteColors.cable
                        ConduitKind.PIPE -> SiteColors.pipe
                        ConduitKind.DUCT -> SiteColors.duct
                    },
                )
                Text(
                    text = stringResource(R.string.run_detail, line.spec.label, line.spec.sizeLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.materials_net, shownLengthWithUnit(line.totalMeters)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.materials_waste, PercentText.of(line.wasteFactor)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.materials_order, shownLengthWithUnit(line.withWasteMeters)),
                    style = MaterialTheme.typography.titleMedium,
                    color = SiteColors.gold,
                )
            }

            // The one number that differs between the tape and the cut: a bend of radius r and
            // sweep δ makes the bent conduit shorter than the measured polyline by
            // 2·r·tan(δ/2) − r·δ. It is tiny (4 cm per 90° on a 20 mm pipe) and well inside the
            // waste factor, so it is never folded in - but it is the figure a fitter cuts to, and
            // until finding 114 it was computed as a negative "allowance" and shown nowhere.
            if (line.bendDeductionMeters > 0.0005f) {
                Text(
                    stringResource(
                        R.string.materials_bend_deduction,
                        shownLengthWithUnit(line.bendDeductionMeters),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                stringResource(
                    R.string.export_fittings,
                    line.clipCount,
                    line.couplingCount,
                    line.elbow90Count + line.elbow45Count + line.otherBendCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                pluralStringResource(R.plurals.home_runs, line.runCount, line.runCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (line.lowConfidenceRuns > 0) {
                Text(
                    stringResource(R.string.materials_low_confidence, line.lowConfidenceRuns),
                    style = MaterialTheme.typography.bodySmall,
                    color = SiteColors.warning,
                )
            }
        }
    }
}
