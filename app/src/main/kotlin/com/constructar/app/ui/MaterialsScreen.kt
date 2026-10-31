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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.bom.MaterialLine
import com.constructar.core.bom.MaterialList
import com.constructar.core.model.ConduitKind
import com.constructar.core.units.LengthFormatter

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
            IconButton(onClick = onBack) {
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

        // ── The firm's waste factor ─────────────────────────────────────────────────
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
                        label = stringResource(R.string.waste_value, (factor * 100f).toInt()),
                        selected = wasteOverride == factor,
                        onClick = { onWasteChange(factor) },
                    )
                }
            }
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
                    LengthFormatter.format(materialList.totalWithWasteMeters),
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
                        stringResource(R.string.materials_net, LengthFormatter.format(line.totalMeters)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.materials_waste,
                            (line.wasteFactor * 100f).toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.materials_order, LengthFormatter.format(line.withWasteMeters)),
                    style = MaterialTheme.typography.titleMedium,
                    color = SiteColors.gold,
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
