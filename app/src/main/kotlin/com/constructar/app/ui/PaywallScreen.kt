/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.constructar.app.BuildConfig
import com.constructar.app.R
import com.constructar.app.ui.theme.Jamaica
import com.constructar.app.ui.theme.SiteColors
import com.constructar.core.licence.Coin
import com.constructar.core.licence.Entitlements
import com.constructar.core.licence.LicenceCheck
import com.constructar.core.licence.Plan
import com.constructar.core.licence.Product
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The single place where money is asked for.
 *
 * Three principles shaped this screen:
 *
 *  * **no surprises** — the free column and the Pro column sit next to each other, so the
 *    operator can see exactly what EUR 10 buys before scrolling to an address;
 *  * **no cards, no accounts** — the app has no INTERNET permission, so a store billing
 *    library cannot work here even if it were wanted. Payment is a crypto transfer to one of
 *    three published addresses, and the licence arrives as a key that is verified on device;
 *  * **reversible** — nothing is removed when a subscription lapses. A free build keeps every
 *    room it ever measured and exports it watermarked; only *new* rooms and the BIM formats
 *    need Pro.
 */
@Composable
fun PaywallScreen(
    entitlements: Entitlements,
    deviceTag: String,
    trialUsed: Boolean,
    trialDaysLeft: Long,
    onBack: () -> Unit,
    onActivate: (String) -> LicenceCheck,
    onStartTrial: () -> Unit,
) {
    val context = LocalContext.current
    var keyText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<LicenceCheck?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    var trialRequested by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.paywall_back),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.paywall_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.paywall_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.Star, contentDescription = null, tint = SiteColors.gold, modifier = Modifier.size(28.dp))
        }

        if (entitlements.isPro) {
            StatusCard(entitlements)
        }

        // ── What is free, what is Pro ───────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FeatureColumn(
                title = stringResource(R.string.paywall_free_title),
                lines = listOf(
                    stringResource(R.string.paywall_free_1),
                    stringResource(R.string.paywall_free_2),
                ),
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FeatureColumn(
                title = stringResource(R.string.paywall_pro_title),
                lines = listOf(
                    stringResource(R.string.paywall_pro_1),
                    stringResource(R.string.paywall_pro_2),
                    stringResource(R.string.paywall_pro_3),
                    stringResource(R.string.paywall_pro_4),
                ),
                accent = Jamaica.Lime,
                modifier = Modifier.weight(1f),
            )
        }

        // ── The three products ──────────────────────────────────────────────────────
        ProductCard(
            title = stringResource(R.string.paywall_month_title),
            price = stringResource(R.string.paywall_price_month, price(Product.MONTHLY)),
            hint = null,
        )
        ProductCard(
            title = stringResource(R.string.paywall_year_title),
            price = stringResource(R.string.paywall_price_year, price(Product.YEARLY)),
            hint = stringResource(R.string.paywall_year_hint),
            highlighted = true,
        )
        ProductCard(
            title = stringResource(R.string.paywall_lifetime_title),
            price = stringResource(R.string.paywall_price_lifetime, price(Product.LIFETIME)),
            hint = stringResource(R.string.paywall_lifetime_hint),
        )

        // ── Trial ───────────────────────────────────────────────────────────────────
        if (!entitlements.isPro) {
            when {
                trialRequested || (trialUsed && trialDaysLeft > 0) -> Text(
                    stringResource(R.string.paywall_trial_active, trialDaysLeft),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Jamaica.Lime,
                )
                trialUsed -> Text(
                    stringResource(R.string.paywall_trial_used),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> GloveButton(
                    label = stringResource(R.string.paywall_trial_button),
                    icon = Icons.Default.Star,
                    tone = ActionTone.SECONDARY,
                    onClick = {
                        trialRequested = true
                        onStartTrial()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── Crypto payment ──────────────────────────────────────────────────────────
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = SiteColors.gold)
                    Text(
                        stringResource(R.string.paywall_crypto_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    stringResource(R.string.paywall_crypto_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Coin.entries.forEach { coin ->
                    CoinRow(
                        coin = coin,
                        copied = copied == coin.address,
                        onCopy = {
                            copyToClipboard(context, coin.address)
                            copied = coin.address
                        },
                        onOpen = { openWallet(context, coin.paymentUri) },
                    )
                }
            }
        }

        // ── Steps ───────────────────────────────────────────────────────────────────
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.paywall_steps_title), style = MaterialTheme.typography.titleMedium)
                StepLine(1, stringResource(R.string.paywall_step_1))
                StepLine(2, stringResource(R.string.paywall_step_2, BuildConfig.DEVELOPER_EMAIL))
                StepLine(3, stringResource(R.string.paywall_step_3))
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.paywall_device_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    deviceTag,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Jamaica.Lime,
                )
                Text(
                    stringResource(R.string.paywall_device_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Activation ──────────────────────────────────────────────────────────────
        OutlinedTextField(
            value = keyText,
            onValueChange = {
                keyText = it
                result = null
            },
            label = { Text(stringResource(R.string.paywall_key_label)) },
            singleLine = false,
            minLines = 2,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        GloveButton(
            label = stringResource(R.string.paywall_activate),
            icon = Icons.Default.Check,
            onClick = { result = onActivate(keyText) },
            enabled = keyText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        result?.let { check -> ActivationResult(check) }

        Text(
            stringResource(R.string.export_offline_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatusCard(entitlements: Entitlements) {
    val planName = when (entitlements.plan) {
        Plan.MONTHLY -> stringResource(R.string.paywall_month_title)
        Plan.YEARLY -> stringResource(R.string.paywall_year_title)
        Plan.LIFETIME -> stringResource(R.string.paywall_lifetime_title)
        Plan.TRIAL -> stringResource(R.string.plan_trial_days, entitlements.daysLeft ?: 0L)
        Plan.FREE -> stringResource(R.string.plan_free)
    }
    val validity = if (entitlements.expiresEpochDay <= 0L) {
        stringResource(R.string.paywall_active_forever)
    } else {
        stringResource(R.string.paywall_active_until, formatEpochDay(entitlements.expiresEpochDay))
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Jamaica.GreenDim.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Jamaica.Lime)
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.paywall_active_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "$planName · $validity",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FeatureColumn(
    title: String,
    lines: List<String>,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.Top) {
                Text("·", color = accent, style = MaterialTheme.typography.bodyMedium)
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ProductCard(
    title: String,
    price: String,
    hint: String?,
    highlighted: Boolean = false,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) Jamaica.SurfaceHigh else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (highlighted) Jamaica.Lime else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                price,
                style = MaterialTheme.typography.titleMedium,
                color = if (highlighted) Jamaica.Lime else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun CoinRow(
    coin: Coin,
    copied: Boolean,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(coin.symbol, style = MaterialTheme.typography.labelMedium, color = SiteColors.gold)
        Text(coin.address, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onCopy) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(if (copied) R.string.paywall_copied else R.string.paywall_copy),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            TextButton(onClick = onOpen) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.paywall_open_wallet),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StepLine(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = Jamaica.Lime,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActivationResult(check: LicenceCheck) {
    val (message, colour) = when (check) {
        is LicenceCheck.Valid -> stringResource(R.string.paywall_result_ok) to Jamaica.Lime
        LicenceCheck.BadSignature -> stringResource(R.string.paywall_err_signature) to SiteColors.danger
        LicenceCheck.DeviceMismatch -> stringResource(R.string.paywall_err_device) to SiteColors.danger
        LicenceCheck.Expired -> stringResource(R.string.paywall_err_expired) to SiteColors.warning
        LicenceCheck.Malformed -> stringResource(R.string.paywall_err_malformed) to SiteColors.danger
    }
    Text(message, style = MaterialTheme.typography.bodyLarge, color = colour)
}

/** `10,00 €` in the operator's locale, always euro regardless of the phone's currency setting. */
private fun price(product: Product): String {
    val format = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
        currency = Currency.getInstance("EUR")
    }
    return format.format(product.eur)
}

/** Epoch days are UTC by contract, so the displayed date has to be too. */
private fun formatEpochDay(epochDay: Long): String {
    val format = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return format.format(Date(epochDay * 86_400_000L))
}

private fun copyToClipboard(context: Context, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(value.take(8), value))
}

/**
 * Opens the operator's wallet if one is installed.
 *
 * Best-effort on purpose: a phone without a wallet must not crash in the middle of a purchase,
 * so a missing handler simply leaves the address on screen to be copied by hand.
 */
private fun openWallet(context: Context, uri: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    }
}
