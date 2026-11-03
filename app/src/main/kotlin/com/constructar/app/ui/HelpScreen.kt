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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.constructar.app.BuildConfig
import com.constructar.app.data.ProjectRepository
import com.constructar.app.R
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.licence.Entitlements
import com.constructar.core.licence.ThirdPartyNotices

/**
 * Help, accuracy and licence in one place.
 *
 * The accuracy table is here rather than buried in the README because it is the honest answer
 * to the first question a foreman asks: *can I bill from this?* Two centimetres on a detected
 * plane, six on depth alone, one percent on diagonals — stated plainly, with the tips that
 * actually move those numbers.
 */
@Composable
fun HelpScreen(
    entitlements: Entitlements,
    trialDaysLeft: Long,
    deviceTag: String,
    clockRolledBack: Boolean = false,
    integrityFailed: Boolean = false,
    localStateTampered: Boolean = false,
    demoSigningKey: Boolean = false,
    repository: ProjectRepository,
    onBack: () -> Unit,
    onUpgrade: () -> Unit,
    onRestore: (
        texts: List<String>,
        photos: List<Pair<String, android.net.Uri>>,
        done: (com.constructar.app.RestoreCounts) -> Unit,
    ) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.paywall_back),
                )
            }
            Text(
                stringResource(R.string.help_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            Image(
                painter = painterResource(R.drawable.constructar_logo),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape),
            )
        }

        Section(title = stringResource(R.string.help_how_title)) {
            Text(stringResource(R.string.help_how_body), style = MaterialTheme.typography.bodyMedium)
        }

        Section(title = stringResource(R.string.help_accuracy_title)) {
            AccuracyLine(label = stringResource(R.string.help_acc_plane), strong = true)
            AccuracyLine(label = stringResource(R.string.help_acc_depth), strong = false)
            AccuracyLine(label = stringResource(R.string.help_acc_angle), strong = false)
        }

        Section(title = stringResource(R.string.help_tips_title)) {
            Bullet(stringResource(R.string.help_tip_scan))
            Bullet(stringResource(R.string.help_tip_light))
            Bullet(stringResource(R.string.help_tip_angle))
            Bullet(stringResource(R.string.help_tip_anchor))
        }

        Section(title = stringResource(R.string.field_title)) {
            Text(stringResource(R.string.field_subtitle), style = MaterialTheme.typography.bodyMedium)
        }

        Section(title = stringResource(R.string.help_offline_title)) {
            Text(stringResource(R.string.help_offline_body), style = MaterialTheme.typography.bodyMedium)
        }

        BackupCard(repository = repository, onRestore = onRestore)

        // Apache-2.0 §4 and BSD-3-Clause make the notice an obligation of distribution, and the
        // release checklist has asked for this section since the first release. The list itself
        // lives in `:core` (`ThirdPartyNotices`), so the UI holds no literals and the fast JVM
        // tests can hold it to what the app links (finding 109).
        Section(title = stringResource(R.string.licences_title)) {
            Text(stringResource(R.string.licences_intro), style = MaterialTheme.typography.bodyMedium)
            ThirdPartyNotices.ALL.forEach { notice ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(notice.title, style = MaterialTheme.typography.labelLarge)
                    Text(
                        notice.copyright,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Section(title = stringResource(R.string.help_licence_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlanChip(entitlements = entitlements, trialDaysLeft = trialDaysLeft, onClick = onUpgrade)
                DeviceCodeLine(
                    tag = deviceTag,
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Everything below is a *diagnosis*, shown only when it applies: a normal
            // installation sees none of it.
            if (clockRolledBack) {
                Text(
                    stringResource(R.string.licence_clock_rollback),
                    style = MaterialTheme.typography.bodySmall,
                    color = SiteColors.warning,
                )
            }
            if (integrityFailed) {
                Text(
                    stringResource(R.string.licence_integrity_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = SiteColors.danger,
                )
            }
            // The diagnosis the store has always computed and no screen ever showed: without it
            // the paywall's "already used" is the only thing the operator hears (finding 139).
            if (localStateTampered) {
                Text(
                    stringResource(R.string.paywall_trial_tampered),
                    style = MaterialTheme.typography.bodySmall,
                    color = SiteColors.warning,
                )
            }
            if (demoSigningKey) {
                Text(
                    stringResource(R.string.licence_demo_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = SiteColors.warning,
                )
            }
            if (!entitlements.isPro) {
                GloveButton(
                    label = stringResource(R.string.home_upgrade),
                    icon = Icons.Default.Star,
                    tone = ActionTone.SECONDARY,
                    onClick = onUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                stringResource(R.string.help_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.export_about, stringResource(R.string.app_name), stringResource(R.string.developer_name), BuildConfig.DEVELOPER_EMAIL),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SiteColors.accent)
            content()
        }
    }
}

@Composable
private fun AccuracyLine(label: String, strong: Boolean) {
    Text(
        text = label,
        style = if (strong) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        color = if (strong) Jamaica.Chalk else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("·", color = SiteColors.accent, style = MaterialTheme.typography.bodyMedium)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
